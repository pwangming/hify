package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalNodeExecutorTest {

    private KnowledgeFacade knowledgeFacade;
    private KnowledgeRetrievalNodeExecutor executor;
    private RunContext ctx;

    @BeforeEach
    void setUp() {
        knowledgeFacade = mock(KnowledgeFacade.class);
        executor = new KnowledgeRetrievalNodeExecutor(knowledgeFacade);
        ctx = new RunContext(7L, 42L);
        ctx.putOutput("start", Map.of("q", "退货政策是什么"));
    }

    private GraphNode node() {
        return new GraphNode("kb", "knowledge-retrieval",
                Map.of("datasetIds", List.of(1, 2), "query", "{{start.q}}"));
    }

    @Test
    void 命中两段_拼接text_count正确_inputs含渲染后query() {
        when(knowledgeFacade.retrieve(List.of(1L, 2L), "退货政策是什么")).thenReturn(List.of(
                new RetrievedChunk(11L, 1L, "客服手册", "七天无理由退货", 0.92),
                new RetrievedChunk(12L, 1L, "客服手册", "运费由卖家承担", 0.85)));

        NodeResult result = executor.execute(node(), ctx);

        assertEquals("[1] 七天无理由退货\n[2] 运费由卖家承担", result.outputs().get("text"));
        assertEquals(2, result.outputs().get("count"));
        assertEquals("退货政策是什么", result.inputs().get("query"));
        assertEquals(List.of("1", "2"), result.inputs().get("datasetIds"));
        verify(knowledgeFacade).validateDatasetIds(List.of(1L, 2L));
    }

    @Test
    void 无命中_空text_count为0_节点成功() {
        when(knowledgeFacade.retrieve(anyList(), anyString())).thenReturn(List.of());

        NodeResult result = executor.execute(node(), ctx);

        assertEquals("", result.outputs().get("text"));
        assertEquals(0, result.outputs().get("count"));
    }

    @Test
    void 库被删_validate抛Biz_转NodeExecutionException携带渲染后inputs() {
        org.mockito.Mockito.doThrow(new BizException(CommonError.NOT_FOUND, "知识库不存在"))
                .when(knowledgeFacade).validateDatasetIds(anyList());

        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node(), ctx));

        assertEquals(BizException.class, ex.getCause().getClass());
        assertEquals("退货政策是什么", ex.inputs().get("query"));
    }
}
