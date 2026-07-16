package com.hify.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

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
    /**
     * MCP 内网白名单（T4b 决策 1/2）：命中的 host 跳过 SSRF 禁内网校验。
     * 精确 host 匹配、忽略大小写（localhost 与 127.0.0.1 是两个条目）；仅 MCP 出站生效——
     * HTTP 节点/内置 HTTP 工具的 URL 非 admin 受控（成员可填、模型可选），维持无差别禁内网。
     */
    private List<String> allowedPrivateHosts = List.of();

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public int getInitializationTimeoutMs() { return initializationTimeoutMs; }
    public void setInitializationTimeoutMs(int initializationTimeoutMs) {
        this.initializationTimeoutMs = initializationTimeoutMs;
    }
    public List<String> getAllowedPrivateHosts() { return allowedPrivateHosts; }
    public void setAllowedPrivateHosts(List<String> allowedPrivateHosts) {
        this.allowedPrivateHosts = allowedPrivateHosts;
    }
}
