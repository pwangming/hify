package com.hify.tool.service.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiToolCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OpenApiToolCallback callback(OpenApiToolSpec.Operation op, OutboundHttpClient http) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name("api__" + op.opName()).description(op.description())
                .inputSchema(op.inputSchema()).build();
        return new OpenApiToolCallback(def, op, "https://api.example.com/v1",
                Map.of("X-API-Key", "secret"), http, mapper);
    }

    @Test
    void call_substitutesPath_injectsAuthHeader_appendsQuery() {
        OpenApiToolSpec.Operation op = new OpenApiToolSpec.Operation(
                "getPetById", "GET", "/pets/{petId}", "查",
                "{}", List.of(
                        new OpenApiToolSpec.Param("petId", "path", true),
                        new OpenApiToolSpec.Param("verbose", "query", false)));
        OutboundHttpClient http = mock(OutboundHttpClient.class);
        when(http.send(any(), any(), any(), any()))
                .thenReturn(new OutboundResponse(200, "{\"name\":\"Fido\"}", Map.of()));

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);

        String result = callback(op, http).call("{\"petId\":42,\"verbose\":true}");

        verify(http).send(eq("GET"), url.capture(), headers.capture(), eq(null));
        assertThat(url.getValue()).isEqualTo("https://api.example.com/v1/pets/42?verbose=true");
        assertThat(headers.getValue()).containsEntry("X-API-Key", "secret");
        assertThat(headers.getValue()).doesNotContainKey("Content-Type");
        assertThat(result).contains("HTTP 200").contains("Fido");
    }

    @Test
    void call_addsJsonContentType_forBody() {
        OpenApiToolSpec.Operation op = new OpenApiToolSpec.Operation(
                "addPet", "POST", "/pets", "加", "{}",
                List.of(new OpenApiToolSpec.Param("name", "body", true)));
        OutboundHttpClient http = mock(OutboundHttpClient.class);
        when(http.send(any(), any(), any(), any()))
                .thenReturn(new OutboundResponse(201, "ok", Map.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        callback(op, http).call("{\"name\":\"Fido\"}");

        verify(http).send(eq("POST"), any(), headers.capture(), any());
        assertThat(headers.getValue()).containsEntry("Content-Type", "application/json");
    }

    @Test
    void call_respectsProvidedContentType_forBody() {
        OpenApiToolSpec.Operation op = new OpenApiToolSpec.Operation(
                "addPet", "POST", "/pets", "加", "{}",
                List.of(new OpenApiToolSpec.Param("name", "body", true),
                        new OpenApiToolSpec.Param("Content-Type", "header", false)));
        OutboundHttpClient http = mock(OutboundHttpClient.class);
        when(http.send(any(), any(), any(), any()))
                .thenReturn(new OutboundResponse(201, "ok", Map.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        callback(op, http).call("{\"name\":\"Fido\",\"Content-Type\":\"application/xml\"}");

        verify(http).send(eq("POST"), any(), headers.capture(), any());
        assertThat(headers.getValue()).containsEntry("Content-Type", "application/xml");
    }

    @Test
    void call_buildsJsonBody_forBodyParams() {
        OpenApiToolSpec.Operation op = new OpenApiToolSpec.Operation(
                "addPet", "POST", "/pets", "加", "{}",
                List.of(new OpenApiToolSpec.Param("name", "body", true)));
        OutboundHttpClient http = mock(OutboundHttpClient.class);
        when(http.send(any(), any(), any(), any()))
                .thenReturn(new OutboundResponse(201, "ok", Map.of()));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        callback(op, http).call("{\"name\":\"Fido\"}");

        verify(http).send(eq("POST"), eq("https://api.example.com/v1/pets"), any(), body.capture());
        assertThat(body.getValue()).contains("\"name\":\"Fido\"");
    }

    @Test
    void call_missingRequiredParam_returnsErrorText_noThrow() {
        OpenApiToolSpec.Operation op = new OpenApiToolSpec.Operation(
                "getPetById", "GET", "/pets/{petId}", "查", "{}",
                List.of(new OpenApiToolSpec.Param("petId", "path", true)));
        OutboundHttpClient http = mock(OutboundHttpClient.class);
        String result = callback(op, http).call("{}");
        assertThat(result).startsWith("错误：").contains("petId");
    }

    @Test
    void call_invalidJson_returnsErrorText() {
        OpenApiToolSpec.Operation op = new OpenApiToolSpec.Operation(
                "x", "GET", "/x", "x", "{}", List.of());
        OutboundHttpClient http = mock(OutboundHttpClient.class);
        assertThat(callback(op, http).call("not json")).startsWith("错误：");
    }
}
