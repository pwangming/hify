package com.hify.tool.service.openapi;

import com.hify.common.exception.BizException;
import com.hify.tool.constant.ToolError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiSpecParserTest {

    private final OpenApiSpecParser parser = new OpenApiSpecParser();

    private static final String PETSTORE_JSON = """
        {"openapi":"3.0.0","info":{"title":"Pet","version":"1.0"},
         "servers":[{"url":"https://api.example.com/v1"}],
         "paths":{"/pets/{petId}":{"get":{"operationId":"getPetById",
           "summary":"根据 id 查宠物",
           "parameters":[{"name":"petId","in":"path","required":true,
             "schema":{"type":"integer"}}]}}}}""";

    @Test
    void parse_json_extractsBaseUrlAndOperation() {
        ParsedOpenApi parsed = parser.parse(PETSTORE_JSON);
        assertThat(parsed.baseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(parsed.operations()).hasSize(1);
        OpenApiToolSpec.Operation op = parsed.operations().get(0);
        assertThat(op.opName()).isEqualTo("getPetById");
        assertThat(op.method()).isEqualTo("GET");
        assertThat(op.pathTemplate()).isEqualTo("/pets/{petId}");
        assertThat(op.description()).contains("查宠物");
        assertThat(op.inputSchema()).contains("petId");
        assertThat(op.parameters()).extracting(OpenApiToolSpec.Param::name).contains("petId");
        assertThat(op.parameters().get(0).in()).isEqualTo("path");
        assertThat(op.parameters().get(0).required()).isTrue();
    }

    @Test
    void parse_yaml_supported() {
        String yaml = """
            openapi: 3.0.0
            info: {title: Pet, version: "1.0"}
            servers: [{url: "https://api.example.com"}]
            paths:
              /ping:
                get:
                  operationId: ping
                  summary: 健康检查
            """;
        ParsedOpenApi parsed = parser.parse(yaml);
        assertThat(parsed.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(parsed.operations()).extracting(OpenApiToolSpec.Operation::opName).contains("ping");
    }

    @Test
    void parse_resolvesRefInRequestBody() {
        String spec = """
            {"openapi":"3.0.0","info":{"title":"T","version":"1"},
             "servers":[{"url":"https://api.example.com"}],
             "paths":{"/pets":{"post":{"operationId":"addPet",
               "requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Pet"}}}}}}},
             "components":{"schemas":{"Pet":{"type":"object",
               "required":["name"],
               "properties":{"name":{"type":"string","description":"名字"}}}}}}""";
        ParsedOpenApi parsed = parser.parse(spec);
        OpenApiToolSpec.Operation op = parsed.operations().get(0);
        assertThat(op.inputSchema()).contains("name");
        assertThat(op.parameters()).extracting(OpenApiToolSpec.Param::name).contains("name");
        assertThat(op.parameters()).filteredOn(p -> p.name().equals("name"))
                .extracting(OpenApiToolSpec.Param::in).containsOnly("body");
    }

    @Test
    void parse_invalid_throws13001() {
        assertThatThrownBy(() -> parser.parse("这不是 spec"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ToolError.SPEC_PARSE_FAILED));
    }

    @Test
    void parse_noServers_throws13001() {
        String spec = """
            {"openapi":"3.0.0","info":{"title":"T","version":"1"},
             "paths":{"/x":{"get":{"operationId":"x"}}}}""";
        assertThatThrownBy(() -> parser.parse(spec)).isInstanceOf(BizException.class);
    }
}
