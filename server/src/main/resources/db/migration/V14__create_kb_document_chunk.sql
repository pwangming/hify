-- V14：文档与分段表（knowledge 模块）。原始文件存 bytea（架构拍板：文件不是独立实体，随库备份）。
-- 模块内 FK 允许建（data-model 第 3 条）；FK 不带 on delete cascade——删除走软删，物理级联永不触发，FK 只作引用完整性兜底。
-- kb_chunk 无 embedding 向量列：K3 做向量化时用新迁移补 vector(1024) 列 + HNSW 索引（K2 spec 决策 1）。

create table kb_document (
    id            bigint      generated always as identity primary key,
    dataset_id    bigint      not null references dataset(id),
    name          text        not null check (char_length(name) <= 200),
    file_type     text        not null check (file_type in ('txt', 'md')),
    file_size     bigint      not null,
    content       bytea       not null,
    -- 默认 pending（安全侧：漏赋值时宁可显示待处理）；K2 同步流程插入时显式写 ready
    status        text        not null default 'pending'
                  check (status in ('pending', 'processing', 'ready', 'failed')),
    chunk_count   int         not null default 0,
    chunk_size    int         not null,
    chunk_overlap int         not null,
    deleted       boolean     not null default false,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now()
);
comment on table kb_document is '知识库文档（knowledge 模块）：元数据+原始文件 bytea；status 四态为 K3 异步向量化预留；chunk_size/overlap 记录分段实际参数';
create index kb_document_dataset_idx on kb_document (dataset_id);

create table kb_chunk (
    id          bigint      generated always as identity primary key,
    document_id bigint      not null references kb_document(id),
    dataset_id  bigint      not null,
    position    int         not null,
    content     text        not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table kb_chunk is '文档分段（knowledge 模块）：dataset_id 为 K4 检索用冗余列；embedding 向量列 K3 迁移补加';
create index kb_chunk_document_idx on kb_chunk (document_id);
create index kb_chunk_dataset_idx on kb_chunk (dataset_id);
