package org.example.takeout.Cart.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
//NOTE：这个类是实验类，不参与任何实际相关业务代码
public class RedisHashCartServiceExperiment {
    private final StringRedisTemplate stringRedisTemplate;

    private static final Long HASH_CART_EXPIRE_MILLIS = 1_800_000L;
    private static final String CART_KEY_PREFIX = "cart:";
    private static final DefaultRedisScript<Long> addScript =new DefaultRedisScript<>(
            """
                    local key = KEYS[1]
                    local field = ARGV[1]
                    local ttl = tonumber(ARGV[2])
                    local quantity = tonumber(redis.call('HGET', key, field) or '0')
                    
                    if quantity >= 99 then
                        return -1
                    end
                    
                    local newQuantity = redis.call('HINCRBY', key,field,1)
                    redis.call('PEXPIRE',key,ttl)
                    return newQuantity
                    """
    , Long.class);

    private final static DefaultRedisScript<Long> decreaseScript=new DefaultRedisScript<>(
            """
                  local key = KEYS[1]
                  local field = ARGV[1]
                  local ttl = tonumber(ARGV[2])
                  local value = redis.call('HGET', key, field)
                  
                  if not value then
                      return -1
                  end

                  local quantity = tonumber(value)

                  if quantity == 1 then
                      redis.call('HDEL', key, field)
                      redis.call('PEXPIRE', key, ttl)
                      return 0
                  end

                  local newQuantity = redis.call('HINCRBY', key, field, -1)
                  redis.call('PEXPIRE', key, ttl)
                  return newQuantity
                  """
    ,Long.class);

    Long add(Long userId, Long productId){
        Long execute =
                stringRedisTemplate.
                        execute(addScript,
                                Collections.singletonList(buildKey(userId)),
                                buildField(productId),
                                String.valueOf(HASH_CART_EXPIRE_MILLIS));
        if (execute == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "购物车操作异常");
        }
        if(execute==-1L){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品增加超过上限");
        }
        return  execute;
    }

    Integer getQuantity(Long userId, Long productId){
        String key = buildKey(userId);
        String field = buildField(productId);
        Object o = stringRedisTemplate.opsForHash().get(key,field);
        if(o==null){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在于购物车");
        }
        refreshExpire(key);
        return Integer.valueOf(String.valueOf(o));
    }

    Map<Long, Integer> getCart(Long userId){

        Map<Long, Integer> map = new HashMap<>();

        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(buildKey(userId));

        entries.forEach((k,v)->{
            Long productId = Long.valueOf(k.toString());
            Integer quantity = Integer.valueOf(String.valueOf(v));
            map.put(productId, quantity);
        });
        refreshExpire(buildKey(userId));
        return map;
    }

    void remove(Long userId, Long productId){
        String key = buildKey(userId);
        String field = buildField(productId);

        stringRedisTemplate.opsForHash().delete(key, field);
        refreshExpire(key);
    }

    private void refreshExpire(String key) {
        stringRedisTemplate.expire(key, Duration.ofMillis(HASH_CART_EXPIRE_MILLIS));
    }

    void clear(Long userId){
        String key = buildKey(userId);
        stringRedisTemplate.delete(key);
    }

    Integer decrease(Long userId, Long productId){
        Long newQuantity = stringRedisTemplate.execute(decreaseScript,
                Collections.singletonList(buildKey(userId)),
                buildField(productId),
                String.valueOf(HASH_CART_EXPIRE_MILLIS));
        if (newQuantity == null) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "购物车操作异常"
            );
        }
        if(newQuantity==-1L){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在于购物车");
        }
        return newQuantity.intValue();
    }



    private String buildKey(Long userId){
        if (userId==null){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "userID为空");
        }
        return CART_KEY_PREFIX + userId;
    }
    private String buildField(Long productId){
        if (productId==null){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "productID为空");
        }
        return productId.toString();
    }
}
