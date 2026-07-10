package com.hify.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 启动自愈（deployment.md 预案）：同步执行下，启动时仍 running 的记录必是上次重启遗留，统一置 failed。幂等。 */
@Component
public class ZombieRunResetter {

    private static final Logger log = LoggerFactory.getLogger(ZombieRunResetter.class);

    private final WorkflowRunStore store;

    public ZombieRunResetter(WorkflowRunStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int reset = store.resetZombies();
        if (reset > 0) {
            log.warn("workflow 启动自愈：重置 {} 条遗留 running 记录为 failed", reset);
        }
    }
}
