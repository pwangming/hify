package com.hify.provider.api.dto;

/**
 * provider 对外公开的模型视图（首个 api/dto）。供 app 校验 model_id、前端选择器展示。
 * id 为 Long（infra 全局序列化为 string）。providerName 给前端分组/展示用。
 *
 * <p>「可用」语义由 {@code ProviderFacade.findUsableChatModel} 保证：模型 enabled + type=chat + 供应商 enabled。
 */
public record ModelView(Long id, String name, String type, String providerName) {
}
