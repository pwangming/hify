package com.hify.infra.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 全局 JSON 序列化/反序列化规范（api-standards.md 第 4 节）。
 *
 * <p>统一在这里配置，业务代码不再在 DTO 上散落 {@code @JsonInclude}/{@code @JsonFormat}。
 * 用 {@link Jackson2ObjectMapperBuilderCustomizer} 而非自己 new ObjectMapper：它是在 Spring Boot
 * 已经配好的 ObjectMapper 基础上「微调」（JavaTimeModule 等已自动注册），改动最小、最不易出错。
 *
 * <p>落地的五条规范：
 * <ul>
 *   <li>Long/long 一律序列化为 JSON 字符串——避免 JS Number 超过 2^53 精度丢失（{@code "id":"42"}）；</li>
 *   <li>null 字段照常输出（{@code ALWAYS}）——前端 TS 类型稳定，字段不会"时有时无"；</li>
 *   <li>时间用 ISO-8601 含时区（关掉"写成时间戳数字"），与 {@code timestamptz}/{@code OffsetDateTime} 对齐；</li>
 *   <li>反序列化遇未知字段忽略不报错——客户端可以比服务端新；</li>
 *   <li>入参字符串全局 trim，trim 后为空串按 null 处理——消灭 null/空串双轨。</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer hifyJacksonCustomizer() {
        return builder -> {
            // Long/long -> 字符串
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);

            // null 字段照常输出
            builder.serializationInclusion(JsonInclude.Include.ALWAYS);

            // 时间不写成时间戳数字（保持 ISO-8601 文本），未知字段不报错
            builder.featuresToDisable(
                    SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            // 入参字符串 trim，空串归一为 null
            builder.deserializerByType(String.class, new TrimToNullStringDeserializer());
        };
    }

    /**
     * 把入参字符串两端空白去掉；若 trim 后为空串，则当作 null。
     * 这样「name=""」「name="  "」「name 不传」三者在后端表现一致，校验与存储都只需处理 null 一种"无值"。
     */
    static class TrimToNullStringDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String value = p.getValueAsString();
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }
}
