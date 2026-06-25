-- V8__alter_model_provider_resilience.sql
-- 给 model_provider 增 10 个韧性配置字段，均带默认值（admin 后续可改，C2 用默认值即可跑）。
ALTER TABLE model_provider
    ADD COLUMN max_concurrency        int NOT NULL DEFAULT 10,
    ADD COLUMN batch_concurrency      int NOT NULL DEFAULT 3,
    ADD COLUMN retry_max_attempts     int NOT NULL DEFAULT 3,
    ADD COLUMN cb_failure_rate        int NOT NULL DEFAULT 50,
    ADD COLUMN cb_wait_open_sec       int NOT NULL DEFAULT 30,
    ADD COLUMN connect_timeout_sec    int NOT NULL DEFAULT 5,
    ADD COLUMN response_timeout_sec   int NOT NULL DEFAULT 120,
    ADD COLUMN first_token_timeout_sec int NOT NULL DEFAULT 30,
    ADD COLUMN token_gap_timeout_sec  int NOT NULL DEFAULT 60,
    ADD COLUMN stream_max_duration_sec int NOT NULL DEFAULT 600;

COMMENT ON COLUMN model_provider.max_concurrency        IS '交互池并发上限（信号量许可）';
COMMENT ON COLUMN model_provider.batch_concurrency      IS '批量池并发上限（embedding/后台，C2 未消费）';
COMMENT ON COLUMN model_provider.retry_max_attempts     IS '重试总尝试次数（3=重试2次）';
COMMENT ON COLUMN model_provider.cb_failure_rate        IS '熔断失败率阈值（百分比）';
COMMENT ON COLUMN model_provider.cb_wait_open_sec       IS '熔断打开后等待进入半开的秒数';
COMMENT ON COLUMN model_provider.connect_timeout_sec    IS '建连超时秒';
COMMENT ON COLUMN model_provider.response_timeout_sec   IS '非流式总响应超时秒';
COMMENT ON COLUMN model_provider.first_token_timeout_sec IS '流式首 token 超时秒（C2 未消费）';
COMMENT ON COLUMN model_provider.token_gap_timeout_sec  IS '流式 token 间隔超时秒（C2 未消费）';
COMMENT ON COLUMN model_provider.stream_max_duration_sec IS '流式总时长上限秒（C2 未消费）';
