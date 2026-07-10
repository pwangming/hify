package com.hify.workflow.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** workflow 模块错误码（18xxx 段，api-standards §5.2）。只放本模块特有语义；不存在/权限/配额复用通用段与 usage 段。 */
public enum WorkflowError implements ErrorCode {

    /** 图结构非法（环/断连/缺 start/end/未知类型/变量引用越界等，message 带具体原因）。 */
    GRAPH_INVALID(18001, HttpStatus.BAD_REQUEST, "工作流图结构非法");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    WorkflowError(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public int code() { return code; }

    @Override
    public HttpStatus status() { return status; }

    @Override
    public String defaultMessage() { return defaultMessage; }
}
