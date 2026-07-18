-- V27：留账清理轮：废弃 daily_usage，补充调用观测列。
alter table llm_call_log
    add column duration_ms integer,
    add column status text not null default 'success'
        check (status in ('success', 'failed')),
    add column error_code text;

drop table daily_usage;
