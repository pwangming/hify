package com.hify.infra.outbound;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 出站 HTTP 客户端配置（CLAUDE.md：外部调用必须有超时且外化）。HTTP 节点/自定义工具/MCP 共用。 */
@Component
@ConfigurationProperties(prefix = "hify.outbound.http")
public class OutboundProperties {

    /** 连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;
    /** 响应读取超时（毫秒）。 */
    private int readTimeoutMs = 15000;
    /** 响应体大小上限（字节），超出截断——防大响应撑爆 node_run jsonb 与内存。 */
    private int maxResponseBytes = 65536;

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
}
