package org.example.takeout.User.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Product.Service.ProductService;
import org.example.takeout.Product.VO.ProductVO;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrowseHistoryService {
    private final StringRedisTemplate redisTemplate;
    private final ProductService productService;

    private static final String PRODUCT_VIEW_HISTORY_KEY_PREFIX = "history:product:";
    private static final int MAX_HISTORY_SIZE = 50;
    private static final Duration HISTORY_TTL = Duration.ofDays(30);

    private static final DefaultRedisScript<Long>RECORD_HISTORY_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local productId = ARGV[1]
            local expireTime= ARGV[2]
            local limitSize = ARGV[3]

            redis.call('LREM', key, 0, productId)
            redis.call('LPUSH',key,productId)
            redis.call('LTRIM', KEYS[1], 0, tonumber(limitSize) - 1)
            redis.call('EXPIRE',key,tonumber(expireTime) )
            return 1
            """, Long.class);

    public ProductVO recordView(Long userId, Long productId){
        validatePositiveId(userId, "用户ID");
        validatePositiveId(productId, "商品ID");
        ProductVO product = productService.getProductDetail(productId);
        String viewKey = PRODUCT_VIEW_HISTORY_KEY_PREFIX + userId;
        try {
            redisTemplate.execute(
                    RECORD_HISTORY_SCRIPT,
                    List.of(viewKey),
                    productId.toString(),
                    String.valueOf(HISTORY_TTL.toSeconds()),
                    String.valueOf(MAX_HISTORY_SIZE)
            );
        }catch (DataAccessException e) {
            log.warn("浏览历史写入失败，userId={}, productId={}",
                    userId, productId, e);
        }
        return product;
    }

    public List<ProductVO> getHistory(Long userId) {
        List<Long> productIds = getRecentViewedProductIds(userId);
        List<ProductVO> products = new ArrayList<>(productIds.size());
        for (Long productId : productIds) {
            try {
                products.add(productService.getProductDetail(productId));
            } catch (BusinessException e) {
                // 历史记录可能仍引用已删除商品，仅忽略明确的不存在错误。
                if (e.getCodeEnum() != ResultCodeEnum.BUSINESS_ERROR
                        || !"商品不存在".equals(e.getMessage())) {
                    throw e;
                }
            }
        }
        return products;
    }

    public List<Long> getRecentViewedProductIds(Long userId){
        validatePositiveId(userId, "用户ID");
        String key = PRODUCT_VIEW_HISTORY_KEY_PREFIX + userId;

        List<String> ids;
        try {
            ids = redisTemplate.opsForList()
                    .range(key, 0, -1);
        } catch (DataAccessException e) {
            throw new BusinessException(
                    ResultCodeEnum.SERVICE_UNAVAILABLE,
                    "浏览历史暂时不可用"
            );
        }

        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        return ids.stream()
                .map(Long::valueOf)
                .toList();
    }

    public void clearHistory(Long userId) {
        validatePositiveId(userId, "用户ID");
        String key = PRODUCT_VIEW_HISTORY_KEY_PREFIX + userId;

        try {
            redisTemplate.delete(key);
        } catch (DataAccessException e) {
            log.error("清空浏览历史失败，userId={}", userId, e);

            throw new BusinessException(
                    ResultCodeEnum.SERVICE_UNAVAILABLE,
                    "清空浏览历史失败，请稍后重试"
            );
        }
    }

    private static void validatePositiveId(Long id, String fieldName) {
        if (id == null) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, fieldName + "不能为空");
        }
        if (id <= 0) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, fieldName + "必须大于0");
        }
    }
}
