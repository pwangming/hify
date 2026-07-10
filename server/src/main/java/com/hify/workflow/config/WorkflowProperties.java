package com.hify.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** workflow 模块配置外化（CLAUDE.md：不硬编码）。 */
@Component
@ConfigurationProperties(prefix = "hify.workflow")
public class WorkflowProperties {

    /** 单个画布的节点数上限（防巨型 graph 存库与失控执行）。 */
    private int maxNodes = 50;

    public int getMaxNodes() { return maxNodes; }
    public void setMaxNodes(int maxNodes) { this.maxNodes = maxNodes; }
}
