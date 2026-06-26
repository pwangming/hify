-- V9__fix_model_provider_resilience_zeros.sql
-- 修复存量数据：V8 之后、实体改 Integer 之前新建的供应商，因实体 int 默认 0 被显式 INSERT，
-- 把 10 个韧性字段写成了 0（盖掉 DB DEFAULT），导致 failureRateThreshold=0 等非法值。
-- 这些字段取 0 在业务上均无意义（并发/重试/超时/阈值），故将 0 一律重置回 V8 的默认值。
UPDATE model_provider SET max_concurrency        = 10  WHERE max_concurrency        = 0;
UPDATE model_provider SET batch_concurrency      = 3   WHERE batch_concurrency      = 0;
UPDATE model_provider SET retry_max_attempts     = 3   WHERE retry_max_attempts     = 0;
UPDATE model_provider SET cb_failure_rate        = 50  WHERE cb_failure_rate        = 0;
UPDATE model_provider SET cb_wait_open_sec       = 30  WHERE cb_wait_open_sec       = 0;
UPDATE model_provider SET connect_timeout_sec    = 5   WHERE connect_timeout_sec    = 0;
UPDATE model_provider SET response_timeout_sec   = 120 WHERE response_timeout_sec   = 0;
UPDATE model_provider SET first_token_timeout_sec = 30 WHERE first_token_timeout_sec = 0;
UPDATE model_provider SET token_gap_timeout_sec  = 60  WHERE token_gap_timeout_sec  = 0;
UPDATE model_provider SET stream_max_duration_sec = 600 WHERE stream_max_duration_sec = 0;
