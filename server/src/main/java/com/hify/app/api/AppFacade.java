package com.hify.app.api;

import com.hify.app.api.dto.AppRuntimeView;

import java.util.Optional;

/**
 * app 模块对外门面。签名只用 api/dto + JDK 类型。
 */
public interface AppFacade {

    /**
     * 取一个「可发起对话」的应用视图。可运行 = 应用存在 + type=chat + status=enabled + 已绑定 modelId。
     * 任一不满足返回 {@link Optional#empty()}；调用方据空抛自身错误码（conversation 抛 17001）。
     * 注意：本方法不校验模型是否可用（供应商启停由 conversation 经 ProviderFacade.getChatClient 校验，抛 12002）。
     */
    Optional<AppRuntimeView> findRunnableChatApp(Long appId);
}
