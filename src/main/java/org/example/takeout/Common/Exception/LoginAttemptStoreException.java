package org.example.takeout.Common.Exception;

public class LoginAttemptStoreException extends RuntimeException {

    public LoginAttemptStoreException(String message) {
        super(message);
    }

    public LoginAttemptStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
