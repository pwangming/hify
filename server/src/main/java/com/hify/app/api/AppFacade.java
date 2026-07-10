package com.hify.app.api;

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

    /**
     * 取一个「工作流应用」视图：应用存在（未删）且 type=workflow 才有值，<b>不过滤启停</b>——
     * enabled 原样透传，草稿编辑不看启停、触发运行由 workflow 侧拒绝 disabled。
     */
    Optional<WorkflowAppView> findWorkflowApp(Long appId);
}
