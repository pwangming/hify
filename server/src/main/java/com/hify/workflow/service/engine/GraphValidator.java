package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.workflow.config.WorkflowProperties;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.constant.WorkflowError;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 画布图校验 + 拓扑排序（保存草稿与触发运行共用，spec §4）。
 * 变量引用规则按执行语义定义：引用的节点必须排在拓扑序中本节点之前（顺序执行=前面的输出必已就绪）。
 */
@Component
public class GraphValidator {

    /** {{nodeId.field}}，与 RunContext.render 同一语法。 */
    static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w-]+)\\.([\\w-]+)\\s*}}");

    /** condition 节点 operator 白名单（spec §2），与 ConditionEvaluator 支持的一致。 */
    static final Set<String> CONDITION_OPERATORS =
            Set.of("==", "!=", ">", ">=", "<", "<=", "contains", "notContains");

    /** http 节点 method 白名单（spec §2）。 */
    static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    private final WorkflowProperties props;

    public GraphValidator(WorkflowProperties props) {
        this.props = props;
    }

    public List<GraphNode> validateAndOrder(GraphDef graph) {
        if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
            throw invalid("至少需要一个节点");
        }
        List<GraphNode> nodes = graph.nodes();
        List<GraphEdge> edges = graph.edges() == null ? List.of() : graph.edges();
        if (nodes.size() > props.getMaxNodes()) {
            throw invalid("节点数超过上限 " + props.getMaxNodes());
        }

        Map<String, GraphNode> byId = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            if (n.id() == null || n.id().isBlank()) {
                throw invalid("存在缺少 id 的节点");
            }
            if (byId.put(n.id(), n) != null) {
                throw invalid("节点 id 重复：" + n.id());
            }
            if (!NodeType.supported(n.type())) {
                throw invalid("未知节点类型：" + n.type());
            }
            if (NodeType.LLM.value().equals(n.type())) {
                requireLlmField(n, "modelId");
                requireLlmField(n, "userPrompt");
            }
            if (NodeType.KNOWLEDGE_RETRIEVAL.value().equals(n.type())) {
                requireKnowledgeRetrievalFields(n);
            }
            if (NodeType.CONDITION.value().equals(n.type())) {
                requireConditionFields(n);
            }
            if (NodeType.HTTP.value().equals(n.type())) {
                requireHttpFields(n);
            }
        }
        requireExactlyOne(nodes, NodeType.START.value());
        requireExactlyOne(nodes, NodeType.END.value());

        Map<String, List<String>> next = new HashMap<>();
        Map<String, List<String>> prev = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        byId.keySet().forEach(id -> inDegree.put(id, 0));
        for (GraphEdge e : edges) {
            if (!byId.containsKey(e.source()) || !byId.containsKey(e.target())) {
                throw invalid("连线引用不存在的节点：" + e.source() + " → " + e.target());
            }
            next.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
            prev.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e.source());
            inDegree.merge(e.target(), 1, Integer::sum);
        }

        // 分支出边规则：condition 恰好两条出边且 handle 为 true/false 各一；普通节点出边不得带 handle
        for (GraphNode n : nodes) {
            List<GraphEdge> outs = edges.stream().filter(e -> n.id().equals(e.source())).toList();
            if (NodeType.CONDITION.value().equals(n.type())) {
                if (outs.size() != 2) {
                    throw invalid("condition 节点 " + n.id() + " 必须恰好两条出边，当前 " + outs.size() + " 条");
                }
                Set<String> handles = new HashSet<>();
                outs.forEach(e -> handles.add(e.sourceHandle()));
                if (!handles.equals(Set.of("true", "false"))) {
                    throw invalid("condition 节点 " + n.id() + " 的出边 sourceHandle 必须为 true/false 各一条");
                }
            } else {
                for (GraphEdge e : outs) {
                    if (e.sourceHandle() != null) {
                        throw invalid("非 condition 节点 " + n.id() + " 的出边不得带 sourceHandle");
                    }
                }
            }
        }

        // 连通性：从 start 正向可达 ∩ 到 end 反向可达，缺一即游离
        Set<String> fromStart = reach(NodeType.START.value(), next);
        Set<String> toEnd = reach(NodeType.END.value(), prev);
        for (String id : byId.keySet()) {
            if (!fromStart.contains(id) || !toEnd.contains(id)) {
                throw invalid("节点游离在 start→end 路径之外：" + id);
            }
        }

        // Kahn 拓扑排序（按 nodes 声明序出队，结果确定）
        List<GraphNode> ordered = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        byId.keySet().stream().filter(id -> inDegree.get(id) == 0).forEach(queue::add);
        Map<String, Integer> degree = new HashMap<>(inDegree);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            ordered.add(byId.get(id));
            for (String t : next.getOrDefault(id, List.of())) {
                if (degree.merge(t, -1, Integer::sum) == 0) {
                    queue.add(t);
                }
            }
        }
        if (ordered.size() < byId.size()) {
            throw invalid("图中存在环");
        }

        // 变量引用只许指向拓扑序更早的节点
        Set<String> seen = new HashSet<>();
        for (GraphNode n : ordered) {
            for (String ref : referencedNodeIds(n.data())) {
                if (!seen.contains(ref)) {
                    throw invalid("节点 " + n.id() + " 引用了未就绪的节点输出：" + ref);
                }
            }
            seen.add(n.id());
        }
        return ordered;
    }

    private void requireLlmField(GraphNode n, String field) {
        Object v = n.data() == null ? null : n.data().get(field);
        if (v == null || String.valueOf(v).isBlank()) {
            throw invalid("llm 节点 " + n.id() + " 缺少 " + field);
        }
        if ("modelId".equals(field)) {
            try {
                Long.parseLong(String.valueOf(v));
            } catch (NumberFormatException e) {
                throw invalid("llm 节点 " + n.id() + " 的 modelId 不是合法数字");
            }
        }
    }

    /** knowledge-retrieval 节点只做格式校验；datasetIds 存在性留到运行时（spec §2：库随时可被删，保存时校验给不了保证）。 */
    private void requireKnowledgeRetrievalFields(GraphNode n) {
        Object query = n.data() == null ? null : n.data().get("query");
        if (query == null || String.valueOf(query).isBlank()) {
            throw invalid("knowledge-retrieval 节点 " + n.id() + " 缺少 query");
        }
        Object raw = n.data().get("datasetIds");
        if (!(raw instanceof Collection<?> ids) || ids.isEmpty()) {
            throw invalid("knowledge-retrieval 节点 " + n.id() + " 缺少非空数组 datasetIds");
        }
        for (Object v : ids) {
            try {
                Long.parseLong(String.valueOf(v));
            } catch (NumberFormatException e) {
                throw invalid("knowledge-retrieval 节点 " + n.id() + " 的 datasetIds 含非法值：" + v);
            }
        }
    }

    /** condition 节点字段校验；left/right 的变量引用合法性走既有引用拓扑序校验，此处只查必填与白名单。 */
    private void requireConditionFields(GraphNode n) {
        for (String field : List.of("left", "operator", "right")) {
            Object v = n.data() == null ? null : n.data().get(field);
            if (v == null || String.valueOf(v).isBlank()) {
                throw invalid("condition 节点 " + n.id() + " 缺少 " + field);
            }
        }
        String op = String.valueOf(n.data().get("operator"));
        if (!CONDITION_OPERATORS.contains(op)) {
            throw invalid("condition 节点 " + n.id() + " 的 operator 非法：" + op);
        }
    }

    /** http 节点字段校验；url 的 scheme 与 SSRF 属运行时校验（url 可含模板变量，保存时只查必填）。 */
    private void requireHttpFields(GraphNode n) {
        Object method = n.data() == null ? null : n.data().get("method");
        if (method == null || String.valueOf(method).isBlank()) {
            throw invalid("http 节点 " + n.id() + " 缺少 method");
        }
        if (!HTTP_METHODS.contains(String.valueOf(method).toUpperCase())) {
            throw invalid("http 节点 " + n.id() + " 的 method 非法：" + method);
        }
        Object url = n.data().get("url");
        if (url == null || String.valueOf(url).isBlank()) {
            throw invalid("http 节点 " + n.id() + " 缺少 url");
        }
        Object headers = n.data().get("headers");
        if (headers != null && !(headers instanceof Map)) {
            throw invalid("http 节点 " + n.id() + " 的 headers 必须是对象");
        }
    }

    private void requireExactlyOne(List<GraphNode> nodes, String type) {
        long count = nodes.stream().filter(n -> type.equals(n.type())).count();
        if (count != 1) {
            throw invalid("必须恰好一个 " + type + " 节点，当前 " + count + " 个");
        }
    }

    private Set<String> reach(String from, Map<String, List<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (visited.add(id)) {
                adjacency.getOrDefault(id, List.of()).forEach(stack::push);
            }
        }
        return visited;
    }

    /** 递归扫 data 中所有字符串值里的 {{x.y}}，收集被引用的节点 id。 */
    private Set<String> referencedNodeIds(Object value) {
        Set<String> refs = new HashSet<>();
        collectRefs(value, refs);
        return refs;
    }

    private void collectRefs(Object value, Set<String> refs) {
        switch (value) {
            case null -> { }
            case String s -> {
                Matcher m = VAR.matcher(s);
                while (m.find()) {
                    refs.add(m.group(1));
                }
            }
            case Map<?, ?> map -> map.values().forEach(v -> collectRefs(v, refs));
            case Collection<?> coll -> coll.forEach(v -> collectRefs(v, refs));
            default -> { }
        }
    }

    private BizException invalid(String reason) {
        return new BizException(WorkflowError.GRAPH_INVALID, "工作流图结构非法：" + reason);
    }
}
