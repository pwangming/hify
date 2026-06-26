package com.hify.provider.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** provider 模块的本模块配置：注册 ConfigurationProperties 绑定 + LLM 调用所需的基础 Bean。 */
@Configuration
@EnableConfigurationProperties(ProviderCryptoProperties.class)
public class ProviderConfig {

    /** 单次 RetryTemplate：关掉 Spring AI 自带重试，重试统一交给 Resilience4j（llm-resilience.md §3）。 */
    @Bean
    public RetryTemplate noRetryTemplate() {
        return RetryTemplate.builder().maxAttempts(1).build();
    }

    /** LLM 调用专用虚拟线程池：TimeLimiter 在其上跑可中断的阻塞调用（ResilientChatModel 用）。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService llmCallExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
