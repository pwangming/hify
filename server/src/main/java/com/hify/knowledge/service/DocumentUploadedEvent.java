package com.hify.knowledge.service;

/** 文档已上传，上传事务提交后由 DocumentProcessJob 接手异步处理。 */
public record DocumentUploadedEvent(Long documentId) {
}
