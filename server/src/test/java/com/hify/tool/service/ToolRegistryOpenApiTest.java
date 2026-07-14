package com.hify.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.builtin.BuiltinTool;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryOpenApiTest {

    @Test
    void getToolCallbacks_expandsOpenApiRow_intoOnePerOperation() {
        ToolMapper mapper = mock(ToolMapper.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.decrypt("ENC")).thenReturn("secret");

        Tool row = new Tool();
        row.setId(9L);
        row.setName("petstore");
        row.setSource("openapi");
        row.setEnabled(true);
        row.setSpec(new OpenApiToolSpec(
                "https://api.example.com",
                List.of(new OpenApiToolSpec.AuthHeader("X-API-Key", "ENC")),
                List.of(
                        new OpenApiToolSpec.Operation("getPet", "GET", "/pets/{id}", "查", "{}",
                                List.of(new OpenApiToolSpec.Param("id", "path", true))),
                        new OpenApiToolSpec.Operation("addPet", "POST", "/pets", "加", "{}", List.of())),
                "raw"));
        when(mapper.selectList(any())).thenReturn(List.of(row));

        ToolRegistry registry = new ToolRegistry(mapper, List.<BuiltinTool>of(),
                cipher, mock(OutboundHttpClient.class), new ObjectMapper());

        List<ToolCallback> callbacks = registry.getToolCallbacks(List.of(9L));
        assertThat(callbacks).hasSize(2);
        assertThat(callbacks).extracting(c -> c.getToolDefinition().name())
                .containsExactlyInAnyOrder("petstore__getPet", "petstore__addPet");
    }
}
