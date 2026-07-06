package com.hify.provider.dto;

/** admin 供应商试连接响应：本次借用的模型显示名 + 样例文本（同 ModelTestResponse.sample 语义）。 */
public record ProviderTestResponse(String modelName, String sample) {
}
