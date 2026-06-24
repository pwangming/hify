package com.hify.provider.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

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
}
