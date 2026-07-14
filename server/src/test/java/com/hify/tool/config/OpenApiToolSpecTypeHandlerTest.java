package com.hify.tool.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiToolSpecTypeHandlerTest {

    @Test
    void roundTrip_preservesFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OpenApiToolSpec spec = new OpenApiToolSpec(
                "https://api.example.com",
                List.of(new OpenApiToolSpec.AuthHeader("X-API-Key", "ENC")),
                List.of(new OpenApiToolSpec.Operation("getPet", "GET", "/pets/{id}", "查",
                        "{\"type\":\"object\"}",
                        List.of(new OpenApiToolSpec.Param("id", "path", true)))),
                "raw");
        String json = mapper.writeValueAsString(spec);
        OpenApiToolSpec back = mapper.readValue(json, OpenApiToolSpec.class);
        assertThat(back.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(back.authHeaders().get(0).valueEnc()).isEqualTo("ENC");
        assertThat(back.operations().get(0).parameters().get(0).in()).isEqualTo("path");
    }
}
