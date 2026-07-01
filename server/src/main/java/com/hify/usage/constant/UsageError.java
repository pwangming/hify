package com.hify.usage.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * usage 模块特有错误码（14xxx 段）。仅放该模块特有业务语义（配额），通用语义复用 CommonError。
 */
public enum UsageError implements ErrorCode {

    /** 今日 Token 配额已用尽——按用户/天封顶，防失控调用刷爆账单（配额只在 conversation/workflow 入口查）。 */
    QUOTA_EXCEEDED(14001, HttpStatus.TOO_MANY_REQUESTS, "今日 Token 配额已用尽");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    UsageError(int code, HttpStatus status, String defaultMessage) {
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
