package com.hify.identity.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * identity 模块特有错误码（11xxx 段，api-standards.md 第 5.2 节登记）。
 *
 * <p>只放本模块特有语义；"资源不存在"等通用情形复用 {@code CommonError}。
 * 通用的"未认证/已过期"（10002/10003）由 infra 安全层处理，本枚举不重复定义。
 */
public enum IdentityError implements ErrorCode {

    /** 用户名或密码错误。刻意不区分"用户不存在"与"密码错"，不泄露账号是否存在。 */
    BAD_CREDENTIALS(11001, HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    /** 账号已停用：用户存在且密码可能正确，但被管理员停用，禁止登录。 */
    ACCOUNT_DISABLED(11002, HttpStatus.FORBIDDEN, "账号已停用");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    IdentityError(int code, HttpStatus status, String defaultMessage) {
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
