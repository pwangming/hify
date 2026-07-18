package com.hify.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * E2E 安全闸：e2e profile 下 reset-db 会 drop/recreate 目标库，
 * 数据源若被配置漂移指向非 hify_e2e 库，必须在启动阶段拦死而不是删完才发现。
 */
@Component
@Profile("e2e")
public class E2eDatasourceGuard {

    public E2eDatasourceGuard(@Value("${spring.datasource.url:}") String url) {
        requireE2eDatabase(url);
    }

    static void requireE2eDatabase(String url) {
        if (url == null || !url.contains("hify_e2e")) {
            throw new IllegalStateException(
                    "e2e profile 只允许连接 hify_e2e 库，当前 spring.datasource.url=" + url);
        }
    }
}
