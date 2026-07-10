package com.hify.workflow.service.engine;

import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.dto.GraphNode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索节点：渲染 query → 校验库存在（10005）→ KnowledgeFacade.retrieve → 输出 {text, count}。
 * 检索失败＝节点失败（不降级，spec §3：工作流要可排查，降级会让下游拿空资料一本正经地编）；
 * 无命中是业务结果不是错误（text=""/count=0，节点成功）。topK/阈值走 knowledge 全局配置，节点不暴露。
 */
@Component
public class KnowledgeRetrievalNodeExecutor implements NodeExecutor {

    private final KnowledgeFacade knowledgeFacade;

    public KnowledgeRetrievalNodeExecutor(KnowledgeFacade knowledgeFacade) {
        this.knowledgeFacade = knowledgeFacade;
    }

    @Override
    public String type() {
        return NodeType.KNOWLEDGE_RETRIEVAL.value();
    }

    @Override
    public NodeResult execute(GraphNode node, RunContext ctx) {
        List<Long> datasetIds = ((Collection<?>) node.data().get("datasetIds")).stream()
                .map(v -> Long.parseLong(String.valueOf(v))).toList();   // validator 已保证合法
        String query = ctx.render(String.valueOf(node.data().get("query")));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("datasetIds", datasetIds.stream().map(String::valueOf).toList());
        inputs.put("query", query);

        try {
            knowledgeFacade.validateDatasetIds(datasetIds);
            List<RetrievedChunk> chunks = knowledgeFacade.retrieve(datasetIds, query);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append('[').append(i + 1).append("] ").append(chunks.get(i).content());
            }
            return new NodeResult(inputs, Map.of("text", sb.toString(), "count", chunks.size()));
        } catch (Exception e) {
            // 渲染已成功、检索才失败：渲染后的输入随异常带出，落 node_run.inputs 供排障（同 LLM 节点模式）
            throw new NodeExecutionException(inputs, e);
        }
    }
}
