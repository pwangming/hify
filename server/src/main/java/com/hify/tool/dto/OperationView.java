package com.hify.tool.dto;

/** 详情里的操作摘要（不含 inputSchema/parameters 细节）。 */
public record OperationView(String opName, String method, String pathTemplate, String description) {}
