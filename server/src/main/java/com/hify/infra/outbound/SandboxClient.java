package com.hify.infra.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱内网调用（server→sandbox）。与 OutboundHttpClient 分开：目标是可信内网服务，
 * 不走 SSRF 校验（SsrfValidator 会把容器服务名当内网拦掉）。双超时 + 并发信号量。
 * 沙箱正常应答（含业务失败 ok:false）如实返回；只有网络/超时/响应超限/协议异常才抛 BizException。
 */
@Component
public class SandboxClient {

    private final SandboxProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final Semaphore semaphore;

    public SandboxClient(SandboxProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
        this.semaphore = new Semaphore(props.getMaxConcurrency());
    }

    /** 提交用户代码执行。inputs 值均为字符串（上游变量已渲染）。 */
    public SandboxResult run(String code, Map<String, String> inputs) {
        String reqBody;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            payload.put("inputs", inputs);
            payload.put("timeoutMs", props.getExecTimeoutMs());
            reqBody = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new BizException(CommonError.INTERNAL_ERROR, "沙箱请求序列化失败");
        }

        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(props.getReadTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱调用被中断");
        }
        if (!acquired) {
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱繁忙，请稍后再试");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/run"))
                    .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.body().length > props.getMaxOutputBytes()) {
                throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱返回超出大小上限");
            }
            return objectMapper.readValue(resp.body(), SandboxResult.class);
        } catch (IOException e) {
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱调用失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱调用被中断");
        } finally {
            semaphore.release();
        }
    }
}
