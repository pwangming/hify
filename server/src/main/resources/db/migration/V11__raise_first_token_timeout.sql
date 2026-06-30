-- 首次发送常因冷启动/推理模型 >30s 才出首 token，30s 偏紧；放宽到 90s（仍是"卡死快速判死"的硬上限，可按供应商再调）。
ALTER TABLE model_provider ALTER COLUMN first_token_timeout_sec SET DEFAULT 90;
UPDATE model_provider SET first_token_timeout_sec = 90 WHERE first_token_timeout_sec = 30;
