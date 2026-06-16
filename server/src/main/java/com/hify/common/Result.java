package com.hify.common;

import com.hify.common.exception.ErrorCode;
import org.slf4j.MDC;

/**
 * 统一响应信封（api-standards.md 第 3 节）。所有 JSON 接口都返回 {@code Result<T>}，
 * <b>唯一例外是 SSE 流式响应</b>。
 *
 * <p>用 {@code record} 实现：不可变、自动生成构造器与访问器（{@code code()}/{@code message()}…）、
 * 自动 equals/hashCode/toString，刚好契合「只装数据、不带行为」的 DTO 定位。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code code} —— 业务码，0 表示成功，非 0 见 {@link ErrorCode}；注意它<b>不是</b> HTTP 状态码。</li>
 *   <li>{@code message} —— 面向最终用户的可读提示，不含堆栈/SQL/内部类名。</li>
 *   <li>{@code data} —— 成功时的业务数据；失败时为 null（参数校验失败例外，data 装字段错误数组）。</li>
 *   <li>{@code traceId} —— 取自日志 MDC，同时会写响应头 X-Trace-Id，用户报障时凭它 grep 日志。</li>
 * </ul>
 *
 * <p>使用约定：Controller <b>只组装成功响应</b>（调用 {@link #ok}）；失败一律抛
 * {@code BizException}，由 infra 全局异常处理器统一转成失败信封，Controller 不手写失败 Result。
 */
public record Result<T>(int code, String message, T data, String traceId) {

    /** 成功响应。code=0，message 固定 "success"，携带业务数据。 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data, currentTraceId());
    }

    /** 失败响应：用错误码的默认提示。 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.defaultMessage());
    }

    /**
     * 失败响应：用自定义提示。data 为 null。
     * <p>主要供 infra 的全局异常处理器使用；业务代码不直接调它（业务只管抛 BizException）。
     */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null, currentTraceId());
    }

    /** 从日志 MDC 取当前请求的 traceId；无上下文（如单元测试、定时任务外）时为 null。 */
    private static String currentTraceId() {
        return MDC.get("traceId");
    }
}
