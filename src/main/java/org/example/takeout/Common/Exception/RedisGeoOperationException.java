package org.example.takeout.Common.Exception;

/**
 * Redis GEO（地理空间）数据操作异常。
 * 与通用的缓存读写异常 {@link RedisCacheUnavailableException} 区分开，
 * 用于骑手位置等 GEO 写入失败场景。
 */
public class RedisGeoOperationException extends RuntimeException {

    public RedisGeoOperationException(String message) {
        super(message);
    }

    public RedisGeoOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
