package com.hify.conversation.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * conversation 模块特有错误码（17xxx 段）。通用语义（不存在/权限/校验）复用 CommonError；
 * 模型不可用/熔断/繁忙由 provider 的 12002/12003/12004 透传，不在此重复。
 */
public enum ConversationError implements ErrorCode {

    /** 应用不存在/非对话型/已停用/未绑定模型——无法发起对话。 */
    APP_NOT_RUNNABLE(17001, HttpStatus.BAD_REQUEST, "应用未绑定可用模型或已停用，无法发起对话"),

    /** Agent 应用暂不支持流式对话（T1 只做同步，流式留 T2）。 */
    AGENT_STREAM_UNSUPPORTED(17002, HttpStatus.BAD_REQUEST, "Agent 应用暂不支持流式对话");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    ConversationError(int code, HttpStatus status, String defaultMessage) {
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
