package org.example.takeout.Common.Exception;

public class RedisCacheUnavailableException extends RuntimeException {

    public RedisCacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
