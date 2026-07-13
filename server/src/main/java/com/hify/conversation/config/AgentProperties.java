package com.hify.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 循环配置（hify.agent.*）。maxToolIterations：一轮对话内允许的模型调用次数上限
 * （防 Agent 级联/异常循环刷爆 Token 账单）。
 */
@ConfigurationProperties(prefix = "hify.agent")
public record AgentProperties(int maxToolIterations) {
}
