package com.hify.provider.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

import java.time.OffsetDateTime;

/**
 * 模型供应商表 {@code model_provider} 的映射实体。继承 {@link BaseEntity}，自动带
 * id / create_time / update_time / deleted 四列（填充逻辑在 infra 的 MetaObjectHandler）。
 * protocol / status 存小写字符串（见 model_provider 的 check 约束 / ProviderStatus）。
 */
@TableName("model_provider")
public class ModelProvider extends BaseEntity {

    private String name;
    private String protocol;     // openai / anthropic
    private String baseUrl;
    private String apiKeyCipher; // AES-256-GCM 密文
    private String apiKeyTail;   // 明文后 4 位，仅供掩码展示
    private String status;       // enabled / disabled
    // 韧性配置 10 字段：用 Integer（非 int），新建留空时让 MyBatis-Plus 不写该列、由 DB DEFAULT 兜底；
    // 写成 int 会因基本类型默认 0 被显式 INSERT，盖掉 DB 默认值（曾导致 failureRateThreshold=0 报错）。
    private Integer maxConcurrency;
    private Integer batchConcurrency;
    private Integer retryMaxAttempts;
    private Integer cbFailureRate;
    private Integer cbWaitOpenSec;
    private Integer connectTimeoutSec;
    private Integer responseTimeoutSec;
    private Integer firstTokenTimeoutSec;
    private Integer tokenGapTimeoutSec;
    private Integer streamMaxDurationSec;
    // 最近一次试连接结果（V17）；NULL=从未测试。成功/失败由 ModelConnectionService 落库。
    @TableField("last_test_status")
    private String lastTestStatus;          // ok / fail
    @TableField("last_test_at")
    private OffsetDateTime lastTestAt;
    @TableField("last_test_error")
    private String lastTestError;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyCipher() {
        return apiKeyCipher;
    }

    public void setApiKeyCipher(String apiKeyCipher) {
        this.apiKeyCipher = apiKeyCipher;
    }

    public String getApiKeyTail() {
        return apiKeyTail;
    }

    public void setApiKeyTail(String apiKeyTail) {
        this.apiKeyTail = apiKeyTail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(Integer maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public Integer getBatchConcurrency() {
        return batchConcurrency;
    }

    public void setBatchConcurrency(Integer batchConcurrency) {
        this.batchConcurrency = batchConcurrency;
    }

    public Integer getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(Integer retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public Integer getCbFailureRate() {
        return cbFailureRate;
    }

    public void setCbFailureRate(Integer cbFailureRate) {
        this.cbFailureRate = cbFailureRate;
    }

    public Integer getCbWaitOpenSec() {
        return cbWaitOpenSec;
    }

    public void setCbWaitOpenSec(Integer cbWaitOpenSec) {
        this.cbWaitOpenSec = cbWaitOpenSec;
    }

    public Integer getConnectTimeoutSec() {
        return connectTimeoutSec;
    }

    public void setConnectTimeoutSec(Integer connectTimeoutSec) {
        this.connectTimeoutSec = connectTimeoutSec;
    }

    public Integer getResponseTimeoutSec() {
        return responseTimeoutSec;
    }

    public void setResponseTimeoutSec(Integer responseTimeoutSec) {
        this.responseTimeoutSec = responseTimeoutSec;
    }

    public Integer getFirstTokenTimeoutSec() {
        return firstTokenTimeoutSec;
    }

    public void setFirstTokenTimeoutSec(Integer firstTokenTimeoutSec) {
        this.firstTokenTimeoutSec = firstTokenTimeoutSec;
    }

    public Integer getTokenGapTimeoutSec() {
        return tokenGapTimeoutSec;
    }

    public void setTokenGapTimeoutSec(Integer tokenGapTimeoutSec) {
        this.tokenGapTimeoutSec = tokenGapTimeoutSec;
    }

    public Integer getStreamMaxDurationSec() {
        return streamMaxDurationSec;
    }

    public void setStreamMaxDurationSec(Integer streamMaxDurationSec) {
        this.streamMaxDurationSec = streamMaxDurationSec;
    }

    public String getLastTestStatus() {
        return lastTestStatus;
    }

    public void setLastTestStatus(String lastTestStatus) {
        this.lastTestStatus = lastTestStatus;
    }

    public OffsetDateTime getLastTestAt() {
        return lastTestAt;
    }

    public void setLastTestAt(OffsetDateTime lastTestAt) {
        this.lastTestAt = lastTestAt;
    }

    public String getLastTestError() {
        return lastTestError;
    }

    public void setLastTestError(String lastTestError) {
        this.lastTestError = lastTestError;
    }
}
