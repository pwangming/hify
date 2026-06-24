package com.hify.provider.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * provider 模块特有错误码（12xxx 段，api-standards.md 第 5 节）。
 * 模块段只放该模块特有的业务语义；通用语义（不存在/冲突/校验）一律复用 CommonError。
 */
public enum ProviderError implements ErrorCode {

    /** Anthropic 协议无 embedding 能力，禁止在其下建 embedding 模型。 */
    EMBEDDING_NOT_SUPPORTED(12001, HttpStatus.BAD_REQUEST, "该协议不支持 embedding 模型");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    ProviderError(int code, HttpStatus status, String defaultMessage) {
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
