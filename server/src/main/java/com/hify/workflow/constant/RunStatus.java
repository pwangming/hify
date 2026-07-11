package com.hify.workflow.constant;

/** 运行/节点状态机，值与 DB check 一致。同步执行无 pending（scaling-path 阶段 2 再加）。 */
public enum RunStatus {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    SKIPPED("skipped");

    private final String value;

    RunStatus(String value) { this.value = value; }

    public String value() { return value; }
}
