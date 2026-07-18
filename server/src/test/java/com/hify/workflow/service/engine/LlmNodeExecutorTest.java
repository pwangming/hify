package com.hify.workflow.service.engine;

import com.hify.common.event.TokenUsedEvent;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.api.ProviderFacade;
import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmNodeExecutorTest {

    private ProviderFacade providerFacade;
    private LlmCaller llmCaller;
    private ApplicationEventPublisher events;
    private LlmNodeExecutor executor;
    private RunContext ctx;

    @BeforeEach
    void setUp() {
        providerFacade = mock(ProviderFacade.class);
        llmCaller = mock(LlmCaller.class);
        events = mock(ApplicationEventPublisher.class);
        executor = new LlmNodeExecutor(providerFacade, llmCaller, events);
        ctx = new RunContext(7L, 42L);
        ctx.putOutput("start", Map.of("query", "我要退货"));
    }

    private GraphNode node(Map<String, Object> data) {
        return new GraphNode("llm_1", "llm", data);
    }

    @Test
    void 渲染提示词_调用模型_输出text_发计量事件() {
        ChatClient client = mock(ChatClient.class);
        when(providerFacade.getChatClient(3L)).thenReturn(client);
        when(llmCaller.call(eq(client), eq("你是客服"), eq("分类：我要退货")))
                .thenReturn(new LlmCallResult("退款类", 10, 5));

        NodeResult result = executor.execute(node(Map.of(
                "modelId", "3", "systemPrompt", "你是客服", "userPrompt", "分类：{{start.query}}")), ctx);

        assertEquals("退款类", result.outputs().get("text"));
        assertEquals("分类：我要退货", result.inputs().get("userPrompt"));
        assertEquals("你是客服", result.inputs().get("systemPrompt"));
        assertEquals("3", result.inputs().get("modelId"));

        ArgumentCaptor<TokenUsedEvent> captor = ArgumentCaptor.forClass(TokenUsedEvent.class);
        verify(events).publishEvent(captor.capture());
        TokenUsedEvent evt = captor.getValue();
        assertEquals(7L, evt.userId());
        assertEquals(42L, evt.appId());
        assertEquals(3L, evt.modelId());
        assertEquals(10, evt.promptTokens());
        assertEquals(5, evt.completionTokens());
        assertEquals(TokenUsedEvent.SOURCE_WORKFLOW, evt.source());
    }

    @Test
    void systemPrompt缺省_传null给LlmCaller() {
        ChatClient client = mock(ChatClient.class);
        when(providerFacade.getChatClient(3L)).thenReturn(client);
        when(llmCaller.call(eq(client), eq(null), eq("hi"))).thenReturn(new LlmCallResult("ok", 1, 1));

        NodeResult result = executor.execute(node(Map.of("modelId", "3", "userPrompt", "hi")), ctx);

        assertEquals("ok", result.outputs().get("text"));
        assertNull(result.inputs().get("systemPrompt"));
    }

    @Test
    void 模型不可用_抛NodeExecutionException携带渲染后inputs_cause为Biz且不发事件() {
        when(providerFacade.getChatClient(3L))
                .thenThrow(new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "模型不可用"));
        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node(Map.of("modelId", "3", "userPrompt", "分类：{{start.query}}")), ctx));
        assertEquals(BizException.class, ex.getCause().getClass());
        assertEquals("分类：我要退货", ex.inputs().get("userPrompt"));   // 渲染后的输入随异常带出
        verify(events, never()).publishEvent(any());
    }
}
