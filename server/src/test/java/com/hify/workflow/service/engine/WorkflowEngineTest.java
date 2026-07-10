package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.service.WorkflowRunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEngineTest {

    private WorkflowRunStore store;
    private NodeExecutor llmExecutor;
    private WorkflowEngine engine;
    private RunContext ctx;

    private final GraphNode start = new GraphNode("start", "start",
            Map.of("inputs", List.of(Map.of("name", "query", "required", true))));
    private final GraphNode llm = new GraphNode("llm_1", "llm",
            Map.of("modelId", "3", "userPrompt", "{{start.query}}"));
    private final GraphNode end = new GraphNode("end", "end",
            Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))));

    @BeforeEach
    void setUp() {
        store = mock(WorkflowRunStore.class);
        AtomicLong seq = new AtomicLong();
        when(store.createNodeRun(anyLong(), anyString(), anyString()))
                .thenAnswer((Answer<Long>) inv -> seq.incrementAndGet());
        llmExecutor = mock(NodeExecutor.class);
        when(llmExecutor.type()).thenReturn("llm");
        engine = new WorkflowEngine(List.of(llmExecutor), store);
        ctx = new RunContext(7L, 42L);
    }

    @Test
    void 全链成功_end渲染最终输出_逐节点落日志() {
        when(llmExecutor.execute(eq(llm), any())).thenAnswer(inv -> {
            return new NodeResult(Map.of("userPrompt", "我要退货"), Map.of("text", "退款类"));
        });

        EngineResult result = engine.execute(100L, List.of(start, llm, end), Map.of("query", "我要退货"), ctx);

        assertTrue(result.succeeded());
        assertEquals("退款类", result.outputs().get("answer"));
        assertNull(result.errorMessage());
        // 三个节点各开工一次、成功收尾一次
        verify(store).createNodeRun(100L, "start", "start");
        verify(store).createNodeRun(100L, "llm_1", "llm");
        verify(store).createNodeRun(100L, "end", "end");
        verify(store).finishNodeRun(eq(1L), eq(true), any(), any(), isNull(), anyLong());
        verify(store).finishNodeRun(eq(2L), eq(true), any(), any(), isNull(), anyLong());
        verify(store).finishNodeRun(eq(3L), eq(true), isNull(), any(), isNull(), anyLong());
    }

    @Test
    void 中途节点失败_run失败_后续节点不执行() {
        when(llmExecutor.execute(eq(llm), any()))
                .thenThrow(new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "模型不可用"));

        EngineResult result = engine.execute(100L, List.of(start, llm, end), Map.of("query", "hi"), ctx);

        assertFalse(result.succeeded());
        assertEquals("llm_1", result.failedNodeId());
        assertTrue(result.errorMessage().contains("模型不可用"));
        // llm 节点失败收尾；end 节点从未开工
        verify(store).finishNodeRun(eq(2L), eq(false), isNull(), isNull(), eq("模型不可用"), anyLong());
        verify(store, never()).createNodeRun(100L, "end", "end");
    }

    @Test
    void 非Biz异常_包一层节点执行异常前缀() {
        when(llmExecutor.execute(eq(llm), any()))
                .thenThrow(new IllegalStateException("变量引用的字段不存在：start.q"));

        EngineResult result = engine.execute(100L, List.of(start, llm, end), Map.of("query", "hi"), ctx);

        assertFalse(result.succeeded());
        assertTrue(result.errorMessage().contains("节点执行异常"));
        assertTrue(result.errorMessage().contains("变量引用的字段不存在"));
    }

    @Test
    void NodeExecutionException_渲染后inputs落进失败日志_文案取cause() {
        Map<String, Object> rendered = Map.of("modelId", "3", "userPrompt", "分类：hi");
        when(llmExecutor.execute(eq(llm), any())).thenThrow(new NodeExecutionException(rendered,
                new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "模型不可用")));

        EngineResult result = engine.execute(100L, List.of(start, llm, end), Map.of("query", "hi"), ctx);

        assertFalse(result.succeeded());
        assertTrue(result.errorMessage().contains("模型不可用"));
        // 排障关键：失败也要能看到实际发出去的提示词
        verify(store).finishNodeRun(eq(2L), eq(false), eq(rendered), isNull(), eq("模型不可用"), anyLong());
    }

    @Test
    void start输出即触发入参_可被下游引用() {
        when(llmExecutor.execute(eq(llm), any())).thenAnswer(inv -> {
            RunContext c = inv.getArgument(1);
            // 引擎必须先把 start 输出放进 ctx，llm 才能渲染 {{start.query}}
            return new NodeResult(Map.of(), Map.of("text", c.render("{{start.query}}")));
        });

        EngineResult result = engine.execute(100L, List.of(start, llm, end), Map.of("query", "我要退货"), ctx);

        assertTrue(result.succeeded());
        assertEquals("我要退货", result.outputs().get("answer"));
    }
}
