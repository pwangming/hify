-- V17__alter_model_provider_last_test.sql
-- 供应商试连接：记录最近一次手动测试结果，NULL=从未测试（设计见 specs/2026-07-06）。
ALTER TABLE model_provider
    ADD COLUMN last_test_status varchar(8) CHECK (last_test_status IN ('ok', 'fail')),
    ADD COLUMN last_test_at     timestamptz,
    ADD COLUMN last_test_error  text;

COMMENT ON COLUMN model_provider.last_test_status IS '最近一次试连接结果 ok/fail，NULL=从未测试';
COMMENT ON COLUMN model_provider.last_test_at     IS '最近一次试连接时间';
COMMENT ON COLUMN model_provider.last_test_error  IS '最近一次试连接失败原因（成功置 NULL）';
