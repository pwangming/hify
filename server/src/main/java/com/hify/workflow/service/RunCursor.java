package com.hify.workflow.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 运行历史的游标编解码：排序键 (create_time, id) → Base64Url（api-standards §3.1：对客户端不透明，
 * 只能原样回传）。解码失败＝客户端自行构造或篡改，报 10001。
 */
final class RunCursor {

    private RunCursor() {
    }

    record Cursor(OffsetDateTime createTime, Long id) {
    }

    static String encode(OffsetDateTime createTime, Long id) {
        String raw = createTime.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            return new Cursor(OffsetDateTime.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (Exception e) {
            throw new BizException(CommonError.PARAM_INVALID, "无效的分页游标", e);
        }
    }
}
