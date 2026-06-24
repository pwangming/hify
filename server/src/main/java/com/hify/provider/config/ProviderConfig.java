package com.hify.provider.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** provider 模块的本模块配置：注册 ConfigurationProperties 绑定。 */
@Configuration
@EnableConfigurationProperties(ProviderCryptoProperties.class)
public class ProviderConfig {
}
