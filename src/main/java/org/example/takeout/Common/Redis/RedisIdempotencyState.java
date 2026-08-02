package org.example.takeout.Common.Redis;

public record RedisIdempotencyState(
        StateType type,
        Long orderId
) {

    public enum StateType {
        MISSING,
        PROCESSING,
        SUCCEEDED
    }

    public static RedisIdempotencyState parse(String value) {
        if (value == null) {
            return new RedisIdempotencyState(StateType.MISSING, null);
        }

        if ("PROCESSING".equals(value)) {
            return new RedisIdempotencyState(StateType.PROCESSING, null);
        }

        if (value.startsWith("SUCCEEDED:")) {
            Long orderId = Long.valueOf(
                    value.substring("SUCCEEDED:".length())
            );
            return new RedisIdempotencyState(
                    StateType.SUCCEEDED,
                    orderId
            );
        }

        throw new IllegalStateException(
                "未知 Redis 幂等状态: " + value
        );
    }
}
