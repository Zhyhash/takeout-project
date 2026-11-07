package org.example.takeout.Payment.Gateway;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.StripeException;
import lombok.Getter;

/**
 * 支付网关错误。消息里只放安全的结构化信息（分类、HTTP 状态码、Stripe 错误码），
 * 绝不包含请求体、响应体或任何密钥；原始异常挂在 cause 上，只在服务端日志里展开。
 */
@Getter
public final class StripeGatewayException extends RuntimeException {

    public enum Kind {
        CONFIGURATION,
        INVALID_WEBHOOK,
        /**
         * Stripe 给出了明确答复并拒绝本次请求（参数非法、状态不允许、鉴权失败等）。
         * <p>远端状态是确定的，用同样的参数重试不会有不同结果，应该先核对真实状态。</p>
         */
        REQUEST_REJECTED,
        /**
         * 没有拿到 Stripe 的确定答复：连接/读取超时、5xx、限流、响应缺少必要字段。
         * <p>远端可能已经执行、也可能完全没收到，本地状态不可信，必须通过对账核实。</p>
         */
        OUTCOME_UNKNOWN
    }

    private final Kind kind;

    /** Stripe 的结构化错误码，例如 payment_intent_unexpected_state；没有则为 null。 */
    private final String stripeErrorCode;

    /** Stripe 返回的 HTTP 状态码；没有收到响应时为 null。 */
    private final Integer httpStatus;

    public StripeGatewayException(Kind kind) {
        this(kind, null, null, null);
    }

    public StripeGatewayException(Kind kind, String stripeErrorCode, Integer httpStatus, Throwable cause) {
        super(describe(kind, stripeErrorCode, httpStatus), cause);
        this.kind = kind;
        this.stripeErrorCode = stripeErrorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * 把 Stripe SDK 的异常翻译成本模块的两种语义。
     * <p>只有 Stripe 明确拒绝的几类异常才算"确定失败"；其余一律视为"结果未知"，
     * 因为本地无法据此判断远端到底有没有执行。</p>
     */
    public static StripeGatewayException fromStripeFailure(StripeException failure) {
        Kind kind;
        if (failure instanceof ApiConnectionException) {
            // 连接失败或读取超时：请求可能根本没到 Stripe，也可能已被执行但响应丢失。
            kind = Kind.OUTCOME_UNKNOWN;
        } else if (failure instanceof InvalidRequestException
                || failure instanceof CardException
                || failure instanceof AuthenticationException
                || failure instanceof PermissionException) {
            kind = Kind.REQUEST_REJECTED;
        } else {
            // ApiException(5xx)、RateLimitException 以及其它未知类型：没有可信的业务结论。
            kind = Kind.OUTCOME_UNKNOWN;
        }
        return new StripeGatewayException(kind, failure.getCode(), httpStatusOf(failure), failure);
    }

    /** SDK 用 0 表示"没有拿到 HTTP 响应"，这里统一归一化为 null，让"无响应"有唯一表示。 */
    private static Integer httpStatusOf(StripeException failure) {
        Integer status = failure.getStatusCode();
        return status == null || status == 0 ? null : status;
    }

    /** 本次失败是否可能已经在远端生效，需要通过对账确认。 */
    public boolean isOutcomeUnknown() {
        return kind == Kind.OUTCOME_UNKNOWN;
    }

    private static String describe(Kind kind, String stripeErrorCode, Integer httpStatus) {
        String detail = (httpStatus == null ? "" : " httpStatus=" + httpStatus)
                + (stripeErrorCode == null ? "" : " code=" + stripeErrorCode);
        return switch (kind) {
            case CONFIGURATION -> "Stripe test payment configuration is invalid";
            case INVALID_WEBHOOK -> "Stripe webhook signature is invalid";
            case REQUEST_REJECTED -> "Stripe rejected the payment request;" + detail;
            case OUTCOME_UNKNOWN -> "Stripe payment outcome is unknown;" + detail;
        };
    }
}
