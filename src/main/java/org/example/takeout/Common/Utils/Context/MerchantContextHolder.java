package org.example.takeout.Common.Utils.Context;

public class MerchantContextHolder {
    private static final ThreadLocal<Long> CONTEXT= new ThreadLocal<>();

    public static Long getMerchantId() {
        return CONTEXT.get();
    }

    public static void setMerchantId(Long id) {
        CONTEXT.set(id);
    }

    public static void clear(){
        CONTEXT.remove();
    }
}
