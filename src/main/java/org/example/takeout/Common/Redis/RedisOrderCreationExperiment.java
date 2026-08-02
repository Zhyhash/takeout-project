package org.example.takeout.Common.Redis;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.Getter;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Order.Service.OrderVOBuilder;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;

import static org.example.takeout.Common.Redis.RedisIdempotencyState.StateType.SUCCEEDED;

@Service
public class RedisOrderCreationExperiment {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderVOBuilder orderVOBuilder;

    private static final Duration PROCESSING_TTL =
            Duration.ofSeconds(10);

    private static final Duration SUCCEEDED_TTL =
            Duration.ofMinutes(10);

    private final RedisOrderIdempotencyStore idempotencyStore;
    @Getter
    private final OrderService orderService;

    public RedisOrderCreationExperiment(
            RedisOrderIdempotencyStore idempotencyStore,
            OrderService orderService
    ) {
        this.idempotencyStore = idempotencyStore;
        this.orderService = orderService;
    }

    public CreateOrderVO createOrder(CreateOrderDTO dto) {
        Long userId = UserContextHolder.getUserId();
        String requestId = dto.getRequestId();

        String value;
        try {
            value = idempotencyStore.get(userId, requestId);
        } catch (DataAccessException exception) {
            // Redis 协调不可用时，退回数据库层面的 requestId 幂等。
            return orderService.createOrder(dto);
        }
        RedisIdempotencyState state = RedisIdempotencyState.parse(value);

        if (state.type() == SUCCEEDED) {
            // 直接在这里查询
            Order order = orderMapper.selectOne(
                    Wrappers.<Order>lambdaQuery()
                            .eq(Order::getId, state.orderId())
                            .eq(Order::getUserId, userId)
            );
            if (order == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                        "订单为空");
            }
            return orderVOBuilder.toCreateOrderVO(order);
        }

        boolean acquired = idempotencyStore.tryMarkProcessing(
                userId,
                requestId,
                PROCESSING_TTL
        );

        if (!acquired) {
            // 下一阶段再处理 PROCESSING
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"订单正在创建中");
        }

        try {
            CreateOrderVO result = orderService.createOrder(dto);

            idempotencyStore.markSucceeded(
                    userId,
                    requestId,
                    result.getOrderId(),
                    SUCCEEDED_TTL
            );

            return result;
        } catch (RuntimeException exception) {
            idempotencyStore.clear(userId, requestId);
            throw exception;
        }
    }

}
