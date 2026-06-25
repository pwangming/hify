package com.hify.provider.api;

import com.hify.provider.api.dto.ModelView;

import java.util.Optional;

/**
 * provider 模块对外门面（一个模块最多一个 Facade，受 Modulith 强制校验）。
 * 其他模块只能经本接口与 provider 同步交互；签名只用 api/dto + JDK 类型
 * （provider 例外允许 Spring AI 类型，留待 C2 的 getChatClient）。
 *
 * <p>C1 只暴露读侧校验；C2 将新增 {@code getChatClient(modelId)} / {@code getEmbeddingModel(modelId)}。
 */
public interface ProviderFacade {

    /**
     * 按 id 取一个「可用」的 chat 模型。可用 = 模型 enabled + type=chat + 所属供应商 enabled。
     * 不存在/停用/非 chat/供应商停用均返回 {@link Optional#empty()}；调用方据空与否决定自身错误码。
     */
    Optional<ModelView> findUsableChatModel(Long modelId);
}
