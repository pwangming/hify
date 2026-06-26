package com.hify.conversation.service;

import org.springframework.stereotype.Service;

/**
 * 配额检查锚点（CLAUDE.md：配额只在 conversation 收消息 / workflow 触发两处）。
 * 本轮 usage 模块为空，先空实现放行；usage 就绪后改为委托 UsageFacade.checkQuota，
 * 配额耗尽抛 14001/429。改这一处即可，不动 ConversationService 控制流。
 */
@Service
public class QuotaGuard {

    public void check(Long userId, Long appId) {
        // TODO(usage 轮): UsageFacade.checkQuota(userId, appId)
    }
}
