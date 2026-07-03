package com.hify.knowledge.service;

import com.hify.knowledge.mapper.KbDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 启动自愈：把残留 pending/processing 文档置为 failed。 */
@Component
public class DocumentStartupHealer {

    private static final Logger log = LoggerFactory.getLogger(DocumentStartupHealer.class);

    private final KbDocumentMapper documentMapper;

    public DocumentStartupHealer(KbDocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void healZombies() {
        int n = documentMapper.failZombies();
        if (n > 0) {
            log.warn("启动自愈：{} 个处理中断的文档已置为 failed，可在页面点重试恢复", n);
        }
    }
}
