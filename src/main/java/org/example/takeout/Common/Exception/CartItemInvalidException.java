package org.example.takeout.Common.Exception;

import java.util.List;

public class CartItemInvalidException extends RuntimeException {
    public CartItemInvalidException(String message, List<?>list) {
        super(message);
    }
}
