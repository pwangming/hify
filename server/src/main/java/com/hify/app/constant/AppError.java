package com.hify.app.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * app 模块特有错误码（16xxx 段）。通用语义（不存在/冲突/权限/校验）复用 CommonError。
 */
public enum AppError implements ErrorCode {

    /** 本轮仅支持创建对话型应用；传 type=workflow 时拒绝。 */
    APP_TYPE_NOT_SUPPORTED(16001, HttpStatus.BAD_REQUEST, "暂仅支持创建对话型应用");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    AppError(int code, HttpStatus status, String defaultMessage) {
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
