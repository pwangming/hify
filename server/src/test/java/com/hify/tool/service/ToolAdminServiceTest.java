package com.hify.tool.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.security.CurrentUser;
import com.hify.tool.dto.AuthHeaderInput;
import com.hify.tool.dto.CreateToolRequest;
import com.hify.tool.dto.ToolAdminDetailResponse;
import com.hify.tool.dto.ToolAdminResponse;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.openapi.OpenApiSpecParser;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import com.hify.tool.service.openapi.ParsedOpenApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolAdminServiceTest {

    private ToolMapper mapper;
    private OpenApiSpecParser parser;
    private SecretCipher cipher;
    private ToolAdminService service;
    private final CurrentUser admin = new CurrentUser(1L, "admin", "admin");

    @BeforeEach
    void setup() {
        mapper = mock(ToolMapper.class);
        parser = mock(OpenApiSpecParser.class);
        cipher = mock(SecretCipher.class);
        service = new ToolAdminService(mapper, parser, cipher);
    }

    private ParsedOpenApi parsed() {
        return new ParsedOpenApi("https://api.example.com",
                List.of(new OpenApiToolSpec.Operation("getPet", "GET", "/pets/{id}", "查", "{}",
                        List.of(new OpenApiToolSpec.Param("id", "path", true)))));
    }

    @Test
    void create_parsesEncryptsAndInserts_setsOwner() {
        when(parser.parse(any())).thenReturn(parsed());
        when(cipher.encrypt("k")).thenReturn("ENC");
        when(mapper.selectCount(any())).thenReturn(0L);

        CreateToolRequest req = new CreateToolRequest("petstore", "宠物", "SPEC",
                List.of(new AuthHeaderInput("X-API-Key", "k")));
        ToolAdminResponse resp = service.create(req, admin);

        ArgumentCaptor<Tool> saved = ArgumentCaptor.forClass(Tool.class);
        org.mockito.Mockito.verify(mapper).insert(saved.capture());
        Tool row = saved.getValue();
        assertThat(row.getSource()).isEqualTo("openapi");
        assertThat(row.getOwnerId()).isEqualTo(1L);
        assertThat(row.getSpec().authHeaders().get(0).valueEnc()).isEqualTo("ENC");
        assertThat(row.getSpec().rawSpec()).isEqualTo("SPEC");
        assertThat(resp.source()).isEqualTo("openapi");
        assertThat(resp.operationCount()).isEqualTo(1);
    }

    @Test
    void create_duplicateName_conflict() {
        when(parser.parse(any())).thenReturn(parsed());
        when(mapper.selectCount(any())).thenReturn(1L);
        CreateToolRequest req = new CreateToolRequest("petstore", "宠物", "SPEC", List.of());
        assertThatThrownBy(() -> service.create(req, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(CommonError.CONFLICT));
    }

    @Test
    void get_openApiRow_returnsOperations_noSecretValues() {
        Tool row = openApiRow();
        when(mapper.selectById(9L)).thenReturn(row);
        ToolAdminDetailResponse detail = service.get(9L);
        assertThat(detail.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(detail.operations()).extracting(o -> o.opName()).contains("getPet");
        assertThat(detail.authHeaderNames()).containsExactly("X-API-Key");
        assertThat(detail.rawSpec()).isEqualTo("raw");
    }

    @Test
    void get_notFound_throws() {
        when(mapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.get(404L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(CommonError.NOT_FOUND));
    }

    @Test
    void update_builtinRow_rejected() {
        Tool builtin = new Tool();
        builtin.setId(1L);
        builtin.setName("http_request");
        builtin.setSource("builtin");
        when(mapper.selectById(1L)).thenReturn(builtin);
        assertThatThrownBy(() -> service.update(1L,
                new com.hify.tool.dto.UpdateToolRequest("http_request", "x", "SPEC", List.of())))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(CommonError.PARAM_INVALID));
    }

    @Test
    void delete_builtinRow_rejected() {
        Tool builtin = new Tool();
        builtin.setId(1L);
        builtin.setSource("builtin");
        when(mapper.selectById(1L)).thenReturn(builtin);
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(CommonError.PARAM_INVALID));
    }

    @Test
    void list_mapsOperationCount_builtinNull() {
        Tool builtin = new Tool();
        builtin.setId(1L); builtin.setName("http_request"); builtin.setSource("builtin"); builtin.setEnabled(true);
        Tool oa = openApiRow(); oa.setEnabled(true);
        when(mapper.selectList(any())).thenReturn(List.of(builtin, oa));
        List<ToolAdminResponse> list = service.list();
        assertThat(list).filteredOn(r -> r.source().equals("builtin"))
                .allSatisfy(r -> assertThat(r.operationCount()).isNull());
        assertThat(list).filteredOn(r -> r.source().equals("openapi"))
                .allSatisfy(r -> assertThat(r.operationCount()).isEqualTo(1));
    }

    private Tool openApiRow() {
        Tool row = new Tool();
        row.setId(9L);
        row.setName("petstore");
        row.setDescription("宠物");
        row.setSource("openapi");
        row.setEnabled(true);
        row.setOwnerId(1L);
        row.setSpec(new OpenApiToolSpec("https://api.example.com",
                List.of(new OpenApiToolSpec.AuthHeader("X-API-Key", "ENC")),
                List.of(new OpenApiToolSpec.Operation("getPet", "GET", "/pets/{id}", "查", "{}",
                        List.of(new OpenApiToolSpec.Param("id", "path", true)))),
                "raw"));
        return row;
    }
}
