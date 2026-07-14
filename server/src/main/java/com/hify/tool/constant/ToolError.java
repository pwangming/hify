package com.hify.tool.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** tool 模块特有错误码（13xxx）。通用语义（不存在/参数错/冲突）复用 CommonError。 */
public enum ToolError implements ErrorCode {

    SPEC_PARSE_FAILED(13001, HttpStatus.BAD_REQUEST, "OpenAPI 文档解析失败");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    ToolError(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override public int code() { return code; }
    @Override public HttpStatus status() { return status; }
    @Override public String defaultMessage() { return defaultMessage; }
}
