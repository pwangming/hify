-- V15：kb_chunk 补 embedding 向量列 + HNSW 索引（兑现 K2 spec 决策 1）；kb_document 补失败原因列。
-- 向量列可空：分段落库时必然无向量（异步补嵌）；「embedding is null」同时是断点续嵌的选段依据。

alter table kb_chunk add column embedding vector(1024);
comment on column kb_chunk.embedding is '1024 维向量（database-standards §2.1：全库统一模型与维度，换模型=全量重嵌）；null=未嵌入';

-- HNSW 余弦索引，m/ef_construction 用默认（database-standards §2.1 原文建法）
create index kb_chunk_embedding_idx on kb_chunk using hnsw (embedding vector_cosine_ops);

alter table kb_document add column error_message text;
comment on column kb_document.error_message is 'status=failed 时的用户可读原因；其余状态为 null';
