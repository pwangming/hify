package com.hify.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** MCP 客户端超时配置（CLAUDE.md：外部调用必须有超时且外化）。见 application.yml 的 hify.tool.mcp。 */
@Component
@ConfigurationProperties(prefix = "hify.tool.mcp")
public class McpProperties {

    /** 建立 TCP 连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;
    /** 单次 JSON-RPC 请求超时（毫秒）：listTools / callTool。 */
    private int requestTimeoutMs = 30000;
    /** initialize 握手超时（毫秒）。 */
    private int initializationTimeoutMs = 10000;

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public int getInitializationTimeoutMs() { return initializationTimeoutMs; }
    public void setInitializationTimeoutMs(int initializationTimeoutMs) {
        this.initializationTimeoutMs = initializationTimeoutMs;
    }
}
