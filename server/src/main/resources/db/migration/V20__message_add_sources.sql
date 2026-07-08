-- V20：message 加来源快照列（conversation 模块）。绑库应用回答的引用依据，随消息一起读/删（会话删级联）。
-- 只新增列，不改 V10；default '[]' 保证存量历史消息读出为空数组，前端不渲染卡片。无新索引（不反查）。
alter table message add column sources jsonb not null default '[]';
comment on column message.sources is '引用来源快照数组 [{chunkId,documentId,documentName,score,preview}]；未绑库/降级/无命中恒为 []';
