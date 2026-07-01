package com.hify.conversation.service;

import com.hify.usage.api.UsageFacade;
import org.springframework.stereotype.Service;

/**
 * 配额检查锚点（CLAUDE.md：配额只在 conversation 收消息 / workflow 触发两处）。
 * 委托 usage 模块的 {@link UsageFacade#checkQuota}：某用户今日 Token 用量达上限时抛 14001/429。
 * 在 send/sendStream 最前调用（配额先行、连接建立前抛，流式亦走普通 JSON 信封）。
 */
@Service
public class QuotaGuard {

    private final UsageFacade usageFacade;

    public QuotaGuard(UsageFacade usageFacade) {
        this.usageFacade = usageFacade;
    }

    public void check(Long userId, Long appId) {
        usageFacade.checkQuota(userId, appId);
    }
}
