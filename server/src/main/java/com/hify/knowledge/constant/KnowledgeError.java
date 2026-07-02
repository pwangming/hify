package com.hify.knowledge.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * knowledge 模块错误码（15xxx 段，api-standards §5.2）。只放模块特有语义；
 * 资源不存在/权限等通用失败一律用 CommonError。发布后只增不改。
 */
public enum KnowledgeError implements ErrorCode {

    /** 文档内容为空或无法按文本解析。 */
    DOCUMENT_CONTENT_EMPTY(15001, HttpStatus.BAD_REQUEST, "文档内容为空或无法解析"),
    /** 文档格式不支持。15004 的号是 api-standards §5.3 预定义示例，必须用它。 */
    DOCUMENT_FORMAT_UNSUPPORTED(15004, HttpStatus.BAD_REQUEST, "文档格式不支持，当前仅支持 txt/md");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    KnowledgeError(int code, HttpStatus status, String defaultMessage) {
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
