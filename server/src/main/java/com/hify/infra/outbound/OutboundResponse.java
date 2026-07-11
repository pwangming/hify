package com.hify.infra.outbound;

import java.util.Map;

/** 出站请求结果：status 原样（含 3xx/4xx/5xx），body 已按上限截断，headers 键为小写、多值逗号连接。 */
public record OutboundResponse(int status, String body, Map<String, String> headers) {
}
