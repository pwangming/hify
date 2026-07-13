package com.hify.infra.outbound;

import java.util.Map;

/** 沙箱执行结果：ok=true 时 outputs 为用户 main 返回的 dict；ok=false 时 error 为失败原因。 */
public record SandboxResult(boolean ok, Map<String, Object> outputs, String error) {
}
