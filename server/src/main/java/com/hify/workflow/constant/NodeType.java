package com.hify.workflow.constant;

import java.util.Arrays;

/** 节点类型，值与 graph jsonb 的 node.type 及 workflow_node_run.node_type check 一致。 */
public enum NodeType {
    START("start"),
    LLM("llm"),
    END("end");

    private final String value;

    NodeType(String value) { this.value = value; }

    public String value() { return value; }

    /** graph 校验用：type 字符串是否为已支持的节点类型。 */
    public static boolean supported(String v) {
        return Arrays.stream(values()).anyMatch(t -> t.value.equals(v));
    }
}
