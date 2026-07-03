package com.hify.knowledge.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全量重嵌入的进程内互斥闸（单机单实例部署，AtomicBoolean 足够）。
 * 独立成 bean 是为了断开 ReembedService ↔ DocumentProcessJob 的循环依赖：
 * service 开闸后派发 job，job 收尾关闸，两者都只依赖本类。
 */
@Component
public class ReembedGate {

    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean tryStart() {
        return running.compareAndSet(false, true);
    }

    public void finish() {
        running.set(false);
    }
}
