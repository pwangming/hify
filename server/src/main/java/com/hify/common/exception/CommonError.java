package com.hify.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 通用错误码（10xxx 段，api-standards.md 第 5.3 节），全模块复用。
 *
 * <p>使用铁律（api-standards.md 第 5.4 条）：
 * <ul>
 *   <li><b>优先复用本枚举</b>。「知识库不存在」直接 {@code new BizException(CommonError.NOT_FOUND, "知识库不存在")}，
 *       不要在每个模块各发明一个「xx 不存在」。</li>
 *   <li>模块段（11xxx~18xxx）只放该模块<b>特有</b>的业务语义（配额耗尽、供应商熔断、状态机冲突）。</li>
 *   <li>错误码一旦发布<b>只增不改不删</b>——前端会硬编码判断（如 10003 触发重新登录）。</li>
 * </ul>
 *
 * <p>枚举成员名用 UPPER_SNAKE_CASE（coding-standards.md 第 1 条），通过显式字段绑定 code/status/message，
 * 不依赖 {@code name()}/{@code ordinal()}（第 4 条）。
 */
public enum CommonError implements ErrorCode {

    /** 兜底：未被识别的异常都归到这里。message 固定「系统繁忙」，真实细节只进日志不出响应。 */
    INTERNAL_ERROR(10000, HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙"),
    /** 参数校验失败；失败时 data 会携带字段错误数组供表单逐项标红。 */
    PARAM_INVALID(10001, HttpStatus.BAD_REQUEST, "参数校验失败"),
    /** 未认证 / 凭证无效。 */
    UNAUTHORIZED(10002, HttpStatus.UNAUTHORIZED, "未认证或凭证无效"),
    /** 凭证已过期（前端据此触发重新登录，刻意与 10002 区分）。 */
    TOKEN_EXPIRED(10003, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"),
    /** 权限不足（已认证但无权操作该资源，如非 owner 改删他人资源）。 */
    FORBIDDEN(10004, HttpStatus.FORBIDDEN, "权限不足"),
    /** 资源不存在。 */
    NOT_FOUND(10005, HttpStatus.NOT_FOUND, "资源不存在"),
    /** 资源冲突（重名等）。 */
    CONFLICT(10006, HttpStatus.CONFLICT, "资源冲突"),
    /** 请求过于频繁（限流）。 */
    TOO_MANY_REQUESTS(10007, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),
    /** 依赖服务不可用（下游不可达等）。 */
    DEPENDENCY_UNAVAILABLE(10008, HttpStatus.SERVICE_UNAVAILABLE, "依赖服务暂不可用");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    CommonError(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
