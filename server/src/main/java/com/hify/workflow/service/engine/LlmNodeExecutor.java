package com.hify.workflow.service.engine;

import com.hify.common.event.TokenUsedEvent;
import com.hify.provider.api.ProviderFacade;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.dto.GraphNode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 节点：渲染提示词 → ProviderFacade 拿带韧性的 ChatClient → 同步调用 → 输出 {text} → 发 TokenUsedEvent。
 * 节点重试固定 0（llm-resilience：防节点×provider 重试风暴）。模型不可用由 getChatClient 抛 BizException，
 * 引擎按节点失败处理（spec §4：模型可用性不在保存时校验）。
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {

    private final ProviderFacade providerFacade;
    private final LlmCaller llmCaller;
    private final ApplicationEventPublisher events;

    public LlmNodeExecutor(ProviderFacade providerFacade, LlmCaller llmCaller, ApplicationEventPublisher events) {
        this.providerFacade = providerFacade;
        this.llmCaller = llmCaller;
        this.events = events;
    }

    @Override
    public String type() {
        return NodeType.LLM.value();
    }

    @Override
    public NodeResult execute(GraphNode node, RunContext ctx) {
        long modelId = Long.parseLong(String.valueOf(node.data().get("modelId")));   // validator 已保证合法
        Object rawSystem = node.data().get("systemPrompt");
        String systemPrompt = rawSystem == null ? null : ctx.render(String.valueOf(rawSystem));
        String userPrompt = ctx.render(String.valueOf(node.data().get("userPrompt")));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("modelId", String.valueOf(modelId));
        inputs.put("systemPrompt", systemPrompt);
        inputs.put("userPrompt", userPrompt);

        try {
            ChatClient client = providerFacade.getChatClient(modelId);
            LlmCallResult result = llmCaller.call(client, systemPrompt, userPrompt);
            events.publishEvent(new TokenUsedEvent(ctx.userId(), ctx.appId(), modelId,
                    result.promptTokens(), result.completionTokens(), TokenUsedEvent.SOURCE_WORKFLOW));
            return new NodeResult(inputs, Map.of("text", result.text()));
        } catch (Exception e) {
            // 渲染已成功、调用才失败：渲染后的输入随异常带出，落 node_run.inputs 供排障
            throw new NodeExecutionException(inputs, e);
        }
    }
}
