package com.hify.knowledge.service;

import com.hify.common.exception.BizException;
import com.hify.knowledge.constant.KnowledgeError;
import com.hify.provider.api.ProviderFacade;
import org.springframework.stereotype.Service;

/** 全量重嵌入启动入口：前置校验 → 互斥闸 → 派发异步任务。 */
@Service
public class ReembedService {

    private final ProviderFacade providerFacade;
    private final ReembedGate gate;
    private final DocumentProcessJob job;

    public ReembedService(ProviderFacade providerFacade, ReembedGate gate, DocumentProcessJob job) {
        this.providerFacade = providerFacade;
        this.gate = gate;
        this.job = job;
    }

    public void start() {
        providerFacade.getEmbeddingModel();
        if (!gate.tryStart()) {
            throw new BizException(KnowledgeError.REEMBED_IN_PROGRESS);
        }
        job.reembedAll();
    }
}
