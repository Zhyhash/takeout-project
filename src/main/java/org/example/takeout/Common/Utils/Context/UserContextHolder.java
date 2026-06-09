package org.example.takeout.Common.Utils.Context;

public class UserContextHolder {
    private static final ThreadLocal<Long> CONTEXT= new ThreadLocal<>();

    public static Long getUserId() {
        return CONTEXT.get();
    }

    public static void setUserId(Long id) {
        CONTEXT.set(id);
    }

    public static void clear(){
        CONTEXT.remove();
    }


}
