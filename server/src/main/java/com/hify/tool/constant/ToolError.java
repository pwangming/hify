package com.hify.tool.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** tool 模块特有错误码（13xxx）。通用语义（不存在/参数错/冲突）复用 CommonError。 */
public enum ToolError implements ErrorCode {

    SPEC_PARSE_FAILED(13001, HttpStatus.BAD_REQUEST, "OpenAPI 文档解析失败"),
    /**
     * MCP 服务器连接或工具发现失败。400 而非 503：只发生在 admin 注册/刷新/预览时，本质是 admin 填错
     * 地址或凭据（用户输入问题），不是「我们依赖的服务挂了」；与 13001 同构。工具执行期连不上按
     * ToolCallback 契约返回错误文本不抛，故不存在执行期 503 场景（T4a spec §7）。
     */
    MCP_CONNECT_FAILED(13002, HttpStatus.BAD_REQUEST, "MCP 服务器连接或工具发现失败");

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
