package org.example.takeout.Payment.Domain;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;

import java.math.BigDecimal;

/**
 * 人民币金额值对象：构造即校验，只暴露"分"这一个支付网关需要的表示。
 * <p>取代原先挂在编排服务上的静态工具方法 {@code convertCnyYuanToFen}——金额规则属于领域，
 * 不属于"谁在调用 Stripe"。构造时完成校验，也就不再需要"调用一次只为让它抛异常"的写法。</p>
 * <p>注意：第一步保持异常类型不变（仍抛 {@link BusinessException}），
 * 后续可替换为独立的领域异常，再由应用层翻译成业务错误。</p>
 */
public final class Money {

    /** Stripe 账户当前只支持的币种。 */
    public static final String CURRENCY = "cny";

    /** Stripe 单笔支付金额上限（分）。 */
    public static final long MAX_MINOR_UNITS = 99_999_999L;

    private final BigDecimal yuan;
    private final long minorUnits;

    private Money(BigDecimal yuan, long minorUnits) {
        this.yuan = yuan;
        this.minorUnits = minorUnits;
    }

    /**
     * 以人民币元构造，并立即校验可精确到分、为正数且不超过 Stripe 上限。
     *
     * @param yuan 订单金额（元）
     * @throws BusinessException 金额为空、非正数、无法精确到分或超过上限
     */
    public static Money ofYuan(BigDecimal yuan) {
        try {
            long minor = yuan.movePointRight(2).longValueExact();
            if (minor < 1 || minor > MAX_MINOR_UNITS) {
                throw new ArithmeticException("amount out of range");
            }
            return new Money(yuan, minor);
        } catch (ArithmeticException | NullPointerException invalid) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR,
                    "订单人民币金额必须为正数、精确到分且符合 Stripe 金额上限");
        }
    }

    /** 以分为单位的正整数金额，直接用于支付网关请求。 */
    public long minorUnits() {
        return minorUnits;
    }

    /** 原始元金额，仅用于日志与展示。 */
    public BigDecimal yuan() {
        return yuan;
    }

    @Override
    public String toString() {
        return yuan + " " + CURRENCY + " (" + minorUnits + " minor)";
    }
}
