package com.hify.usage.api;

/**
 * usage 模块对外门面。签名只用 JDK 类型（code-organization §4 条2）。
 * 用量落库不走 Facade（走 TokenUsedEvent 事件，禁同步调 usage 写）——本门面只暴露同步的配额检查。
 */
public interface UsageFacade {

    /**
     * 入口配额检查：某用户今日 Token 用量达上限时抛 {@code BizException(14001/429)}，否则放行。
     * 只在 conversation 收消息 / workflow 触发两处调用（code-organization §4 条4）。
     * appId 保留供 per-app 配额扩展，本轮按用户/天封顶。
     */
    void checkQuota(Long userId, Long appId);
}
