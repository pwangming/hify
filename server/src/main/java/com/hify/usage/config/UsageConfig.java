package com.hify.usage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * usage 模块配置。开启配置绑定；{@code @EnableScheduling} 供 llm_call_log 月分区维护任务
 * （{@code PartitionMaintainer}）——应用内首次开启调度支持。
 */
@Configuration
@EnableConfigurationProperties(UsageProperties.class)
@EnableScheduling
public class UsageConfig {
}
