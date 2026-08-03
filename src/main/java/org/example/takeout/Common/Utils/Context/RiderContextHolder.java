package org.example.takeout.Common.Utils.Context;

public class RiderContextHolder {
    private static final ThreadLocal<Long> CONTEXT = new ThreadLocal<>();

    public static Long getRiderId() {
        return CONTEXT.get();
    }

    public static void setRiderId(Long id) {
        CONTEXT.set(id);
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
