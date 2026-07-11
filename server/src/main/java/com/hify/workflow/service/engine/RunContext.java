package com.hify.workflow.service.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 一次运行的内存上下文：节点输出按 nodeId 存放，{{nodeId.field}} 从这里做字符串替换。
 * 非线程安全——引擎单线程顺序执行，一次 run 一个实例，不共享。
 */
public class RunContext {

    private final Long userId;
    private final Long appId;
    private final Map<String, Map<String, Object>> outputs = new HashMap<>();
    private final Set<String> skipped = new HashSet<>();

    public RunContext(Long userId, Long appId) {
        this.userId = userId;
        this.appId = appId;
    }

    public Long userId() { return userId; }

    public Long appId() { return appId; }

    public void putOutput(String nodeId, Map<String, Object> out) {
        outputs.put(nodeId, out == null ? Map.of() : out);
    }

    /** 标记节点被分支跳过：其字段引用渲染为空串（spec §3 汇合语义）。 */
    public void markSkipped(String nodeId) {
        skipped.add(nodeId);
    }

    public Map<String, Object> getOutput(String nodeId) {
        return outputs.get(nodeId);
    }

    /** 模板替换；引用缺失抛 IllegalStateException（引擎捕获后按节点失败处理）。null 模板原样返回。 */
    public String render(String template) {
        if (template == null) {
            return null;
        }
        Matcher m = GraphValidator.VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String nodeId = m.group(1);
            String field = m.group(2);
            if (skipped.contains(nodeId)) {
                m.appendReplacement(sb, "");
                continue;
            }
            Map<String, Object> out = outputs.get(nodeId);
            if (out == null) {
                throw new IllegalStateException("变量引用的节点无输出：" + nodeId);
            }
            if (!out.containsKey(field)) {
                throw new IllegalStateException("变量引用的字段不存在：" + nodeId + "." + field);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(out.get(field))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
