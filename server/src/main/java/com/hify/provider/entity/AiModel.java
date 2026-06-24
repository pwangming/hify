package com.hify.provider.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 具体模型表 {@code ai_model} 的映射实体。继承 {@link BaseEntity}，自动带
 * id / create_time / update_time / deleted 四列。挂在 model_provider 下（provider_id）。
 * type / status 存小写字符串（见 ai_model 的 check 约束 / ModelType / ProviderStatus）。
 */
@TableName("ai_model")
public class AiModel extends BaseEntity {

    private Long providerId;
    private String type;     // chat / embedding
    private String name;     // 显示名
    private String modelKey; // API 模型标识（传给 LLM，如 gpt-4o）
    private String status;   // enabled / disabled

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModelKey() {
        return modelKey;
    }

    public void setModelKey(String modelKey) {
        this.modelKey = modelKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
