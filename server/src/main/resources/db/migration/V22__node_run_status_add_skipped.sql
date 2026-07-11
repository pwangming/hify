-- W3a：node_run 状态机加 skipped（未选中分支节点的落库状态，spec §3）。
-- 分区父表上 drop/add，子分区自动继承。约束名为 V21 内联 check 的 PG 默认命名。
alter table workflow_node_run drop constraint workflow_node_run_status_check;
alter table workflow_node_run add constraint workflow_node_run_status_check
    check (status in ('running', 'succeeded', 'failed', 'skipped'));
