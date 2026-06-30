package com.hify.infra.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

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
 *   <li>时间用 ISO-8601 含时区、固定到秒（如 {@code 2026-06-18T14:50:19+08:00}），与 api-standards 第 4 节示例对齐；
 *       <b>统一归一到同一时区偏移</b>（{@code hify.api.time-zone-offset}，默认 {@code +08:00}）——不论数据库读出的是
 *       UTC（{@code Z}）还是 JVM 现生成的本地偏移，对外一律同一风格；不输出小数秒——纳秒对前端无意义（JS Date 仅到毫秒）；</li>
 *   <li>反序列化遇未知字段忽略不报错——客户端可以比服务端新；</li>
 *   <li>入参字符串全局 trim，trim 后为空串按 null 处理——消灭 null/空串双轨。</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    /** 对外时间统一归一到的时区偏移；外化到 application.yml，默认东八区 +08:00。 */
    private final ZoneOffset apiZoneOffset;

    public JacksonConfig(@Value("${hify.api.time-zone-offset:+08:00}") String timeZoneOffset) {
        this.apiZoneOffset = ZoneOffset.of(timeZoneOffset);
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer hifyJacksonCustomizer() {
        return builder -> {
            // Long/long、Integer/int -> 字符串（前端 Number 只有 2^53 精度，Long 超范围丢精度；
            // Integer 同理统一转字符串，减少前端类型歧义，api-standards.md §4）
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
            builder.serializerByType(Integer.class, ToStringSerializer.instance);
            builder.serializerByType(Integer.TYPE, ToStringSerializer.instance);

            // null 字段照常输出
            builder.serializationInclusion(JsonInclude.Include.ALWAYS);

            // 时间不写成时间戳数字（保持 ISO-8601 文本），未知字段不报错
            builder.featuresToDisable(
                    SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            // OffsetDateTime 先归一到统一偏移、再输出固定到秒的 ISO-8601，不带小数秒。
            // 只定制「输出」；输入沿用 Jackson 默认解析器，小数秒/偏移可有可无，照样能解析。
            builder.serializerByType(OffsetDateTime.class, new SecondPrecisionOffsetDateTimeSerializer(apiZoneOffset));

            // 入参字符串 trim，空串归一为 null
            builder.deserializerByType(String.class, new TrimToNullStringDeserializer());
        };
    }

    /**
     * 把 {@link OffsetDateTime} 先归一到统一时区偏移，再序列化成固定到秒的 ISO-8601 文本
     * （如 {@code 2026-06-18T14:50:19+08:00}）。{@code withOffsetSameInstant} 保持「绝对时刻」不变、只换偏移表示，
     * 所以无论入参是 {@code Z}（数据库读出）还是别的偏移，对外都是同一风格；{@code XXX} 输出形如 {@code +08:00}；
     * 格式不含小数秒，故纳秒被自然丢弃。
     */
    static class SecondPrecisionOffsetDateTimeSerializer extends JsonSerializer<OffsetDateTime> {
        private static final DateTimeFormatter FORMATTER =
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

        private final ZoneOffset zoneOffset;

        SecondPrecisionOffsetDateTimeSerializer(ZoneOffset zoneOffset) {
            this.zoneOffset = zoneOffset;
        }

        @Override
        public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.withOffsetSameInstant(zoneOffset).format(FORMATTER));
        }
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
