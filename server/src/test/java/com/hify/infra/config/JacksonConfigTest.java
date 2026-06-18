package com.hify.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JacksonConfig} 的序列化/反序列化规范测试。
 *
 * <p>不启动 Spring 容器：直接把我们的 customizer 应用到一个 Jackson 构建器上，得到等价的
 * ObjectMapper 再做断言。这样能精确验证「api-standards 第 4 节」的每条规则真的生效。
 */
class JacksonConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig("+08:00").hifyJacksonCustomizer().customize(builder);
        this.objectMapper = builder.build();
    }

    @Test
    void Long序列化为字符串() throws Exception {
        String json = objectMapper.writeValueAsString(new Sample(42L, "hi"));

        assertTrue(json.contains("\"id\":\"42\""), () -> "Long 应序列化为字符串，实际: " + json);
    }

    @Test
    void null字段照常输出() throws Exception {
        String json = objectMapper.writeValueAsString(new Sample(null, null));

        assertTrue(json.contains("\"id\":null"), () -> json);
        assertTrue(json.contains("\"name\":null"), () -> json);
    }

    @Test
    void 时间按ISO8601含时区输出() throws Exception {
        OffsetDateTime time = OffsetDateTime.of(2026, 6, 12, 10, 30, 0, 0, ZoneOffset.ofHours(8));

        String json = objectMapper.writeValueAsString(new WithTime(time));

        assertTrue(json.contains("2026-06-12T10:30:00+08:00"), () -> json);
    }

    @Test
    void 时间固定到秒不输出纳秒() throws Exception {
        // 数据库 timestamptz 带亚秒精度，OffsetDateTime 里会有纳秒（245072034ns）；
        // 序列化必须截到秒，避免输出 .245072034 这种 JS Date 用不到、长度还不统一的小数。
        OffsetDateTime time = OffsetDateTime.of(2026, 6, 18, 14, 50, 19, 245072034, ZoneOffset.ofHours(8));

        String json = objectMapper.writeValueAsString(new WithTime(time));

        assertTrue(json.contains("\"time\":\"2026-06-18T14:50:19+08:00\""),
                () -> "应固定到秒、不含小数，实际: " + json);
    }

    @Test
    void UTC时间归一到东八区输出() throws Exception {
        // 数据库 timestamptz 经 JDBC 读出来是 UTC（偏移 Z）。统一归一后对外应显示成 +08:00 的同一风格，
        // 且是同一时刻：UTC 06:50:19 == 北京 14:50:19。
        OffsetDateTime utc = OffsetDateTime.of(2026, 6, 18, 6, 50, 19, 0, ZoneOffset.UTC);

        String json = objectMapper.writeValueAsString(new WithTime(utc));

        assertTrue(json.contains("\"time\":\"2026-06-18T14:50:19+08:00\""),
                () -> "UTC 应归一到 +08:00，实际: " + json);
    }

    @Test
    void 未知字段忽略不报错() throws Exception {
        Sample s = objectMapper.readValue("{\"id\":\"7\",\"name\":\"a\",\"extra\":\"x\"}", Sample.class);

        assertEquals(7L, s.id());
        assertEquals("a", s.name());
    }

    @Test
    void 入参字符串两端空白被trim() throws Exception {
        Sample s = objectMapper.readValue("{\"name\":\"  ab  \"}", Sample.class);

        assertEquals("ab", s.name());
    }

    @Test
    void 入参纯空白归一为null() throws Exception {
        Sample s = objectMapper.readValue("{\"name\":\"   \"}", Sample.class);

        assertNull(s.name());
    }

    record Sample(Long id, String name) {
    }

    record WithTime(OffsetDateTime time) {
    }
}
