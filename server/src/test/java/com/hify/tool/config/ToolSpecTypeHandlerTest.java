package com.hify.tool.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.tool.service.ToolSpec;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSpecTypeHandlerTest {

    private final ObjectMapper mapper = ToolSpecTypeHandler.specMapper();

    @Test
    void roundTrip_openApi_preservesFieldsAndWritesKind() throws Exception {
        OpenApiToolSpec spec = new OpenApiToolSpec(
                "https://api.example.com",
                List.of(new OpenApiToolSpec.AuthHeader("X-API-Key", "ENC")),
                List.of(new OpenApiToolSpec.Operation("getPet", "GET", "/pets/{id}", "查",
                        "{\"type\":\"object\"}",
                        List.of(new OpenApiToolSpec.Param("id", "path", true)))),
                "raw");

        String json = mapper.writeValueAsString(spec);
        assertThat(json).contains("\"kind\":\"openapi\"");

        ToolSpec back = mapper.readValue(json, ToolSpec.class);
        assertThat(back).isInstanceOf(OpenApiToolSpec.class);
        OpenApiToolSpec o = (OpenApiToolSpec) back;
        assertThat(o.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(o.authHeaders().get(0).valueEnc()).isEqualTo("ENC");
        assertThat(o.operations().get(0).parameters().get(0).in()).isEqualTo("path");
    }

    /** T3a 时期落库的行没有 kind——defaultImpl 必须让它们仍能读出来，否则一上线就炸本地已有数据。 */
    @Test
    void legacyJsonWithoutKind_stillDeserializesAsOpenApi() throws Exception {
        String legacy = """
                {"baseUrl":"https://api.example.com",
                 "authHeaders":[{"name":"X-API-Key","valueEnc":"ENC"}],
                 "operations":[{"opName":"getPet","method":"GET","pathTemplate":"/pets/{id}",
                                "description":"查","inputSchema":"{}","parameters":[]}],
                 "rawSpec":"raw"}
                """;

        ToolSpec back = mapper.readValue(legacy, ToolSpec.class);

        assertThat(back).isInstanceOf(OpenApiToolSpec.class);
        assertThat(((OpenApiToolSpec) back).baseUrl()).isEqualTo("https://api.example.com");
    }
}
