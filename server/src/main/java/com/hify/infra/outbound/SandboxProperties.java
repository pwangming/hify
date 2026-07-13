package com.hify.infra.outbound;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 沙箱调用配置（CLAUDE.md：外部调用必须有超时且外化）。workflow 代码节点与将来 tool 的 Agent 代码工具共用。 */
@Component
@ConfigurationProperties(prefix = "hify.sandbox")
public class SandboxProperties {

    /** 沙箱内网地址。 */
    private String baseUrl = "http://sandbox:8000";
    /** 连接沙箱超时（毫秒）。 */
    private int connectTimeoutMs = 1000;
    /** server 侧读超时（毫秒），须 = 沙箱执行超时 + 余量，保证沙箱先超时返回结构化错误。 */
    private int readTimeoutMs = 7000;
    /** 沙箱子进程执行超时（毫秒），随请求下发。 */
    private int execTimeoutMs = 5000;
    /** server 侧提交并发上限（信号量），防 Agent 级联把单沙箱容器打爆。 */
    private int maxConcurrency = 8;
    /** 沙箱响应体大小上限（字节），超出视为异常——防大响应撑爆 node_run jsonb 与内存。 */
    private int maxOutputBytes = 65536;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getExecTimeoutMs() { return execTimeoutMs; }
    public void setExecTimeoutMs(int execTimeoutMs) { this.execTimeoutMs = execTimeoutMs; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(int maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }
}
