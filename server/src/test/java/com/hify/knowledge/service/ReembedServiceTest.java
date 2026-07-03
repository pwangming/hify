package com.hify.knowledge.service;

import com.hify.common.exception.BizException;
import com.hify.knowledge.constant.KnowledgeError;
import com.hify.provider.api.ProviderFacade;
import com.hify.provider.constant.ProviderError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReembedServiceTest {

    private ProviderFacade providerFacade;
    private ReembedGate gate;
    private DocumentProcessJob job;
    private ReembedService service;

    @BeforeEach
    void setUp() {
        providerFacade = mock(ProviderFacade.class);
        gate = mock(ReembedGate.class);
        job = mock(DocumentProcessJob.class);
        service = new ReembedService(providerFacade, gate, job);
    }

    @Test
    void 启动_未配置embedding模型_12006且不开闸() {
        when(providerFacade.getEmbeddingModel())
                .thenThrow(new BizException(ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED));
        BizException ex = assertThrows(BizException.class, () -> service.start());
        assertEquals(ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED, ex.errorCode());
        verify(gate, never()).tryStart();
    }

    @Test
    void 启动_已有任务在跑_15003() {
        when(gate.tryStart()).thenReturn(false);
        BizException ex = assertThrows(BizException.class, () -> service.start());
        assertEquals(KnowledgeError.REEMBED_IN_PROGRESS, ex.errorCode());
        verify(job, never()).reembedAll();
    }

    @Test
    void 启动_正常_开闸并派发() {
        when(gate.tryStart()).thenReturn(true);
        service.start();
        verify(job).reembedAll();
    }
}
