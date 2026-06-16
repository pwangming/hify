package com.hify.common.exception;

/**
 * 业务异常——全项目<b>唯一</b>允许业务代码主动抛出的异常类型（coding-standards.md 第 7 条）。
 *
 * <p>规则：
 * <ul>
 *   <li>业务失败一律 {@code throw new BizException(错误码, ...)}，禁止自创 RuntimeException 子类、
 *       禁止抛裸 {@code RuntimeException("xxx")}；</li>
 *   <li>Controller 里<b>不写 try-catch、不手写失败 Result</b>——异常抛出后由 infra 的全局异常处理器
 *       统一捕获，读取 {@link #errorCode} 转成 {@code Result} 失败信封并设置 HTTP 状态；</li>
 *   <li>确需携带模块特有的结构化信息时，才在模块的 {@code exception/} 下继承本类。</li>
 * </ul>
 *
 * <p>继承 {@link RuntimeException}（非受检异常）：业务异常会穿透多层调用栈一直冒泡到全局处理器，
 * 用受检异常会逼着每层方法签名都 {@code throws}，毫无收益。
 */
public class BizException extends RuntimeException {

    /** 携带的错误码，决定最终响应的 code 与 HTTP 状态。 */
    private final ErrorCode errorCode;

    /** 用错误码的默认提示作为 message。 */
    public BizException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    /** 用自定义提示覆盖默认提示（更贴合具体场景，如「知识库不存在」）。 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 转译并重抛底层异常时使用：保留原始异常为 cause（coding-standards.md 第 8 条），
     * 排障时堆栈不丢。
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
