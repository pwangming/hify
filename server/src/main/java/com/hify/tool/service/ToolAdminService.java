package com.hify.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.security.CurrentUser;
import com.hify.tool.dto.AuthHeaderInput;
import com.hify.tool.dto.CreateToolRequest;
import com.hify.tool.dto.OperationView;
import com.hify.tool.dto.ToolPreviewResponse;
import com.hify.tool.dto.ToolAdminDetailResponse;
import com.hify.tool.dto.ToolAdminResponse;
import com.hify.tool.dto.UpdateToolRequest;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.openapi.OpenApiSpecParser;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import com.hify.tool.service.openapi.ParsedOpenApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义工具注册表 admin CRUD（Admin 专属）。openapi 行可增删改；builtin 行只可启停、不可删改。
 * 凭据加密走 infra SecretCipher，只存密文；解析走 OpenApiSpecParser。@Transactional 只在写方法，内无外部 IO。
 */
@Service
public class ToolAdminService {

    private final ToolMapper toolMapper;
    private final OpenApiSpecParser parser;
    private final SecretCipher cipher;

    public ToolAdminService(ToolMapper toolMapper, OpenApiSpecParser parser, SecretCipher cipher) {
        this.toolMapper = toolMapper;
        this.parser = parser;
        this.cipher = cipher;
    }

    @Transactional
    public ToolAdminResponse create(CreateToolRequest req, CurrentUser current) {
        assertNameFree(req.name(), null);
        OpenApiToolSpec spec = buildSpec(req.specText(), req.authHeaders());
        Tool row = new Tool();
        row.setName(req.name());
        row.setDescription(req.description());
        row.setSource("openapi");
        row.setEnabled(true);
        row.setOwnerId(current.userId());
        row.setSpec(spec);
        toolMapper.insert(row);
        return toResponse(row);
    }

    public List<ToolAdminResponse> list() {
        return toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                        .orderByAsc(Tool::getSource)
                        .orderByAsc(Tool::getName))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ToolAdminDetailResponse get(Long id) {
        Tool row = require(id);
        OpenApiToolSpec spec = row.getSpec();
        if (spec == null) {
            return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                    Boolean.TRUE.equals(row.getEnabled()), null, List.of(), List.of(), null);
        }
        List<OperationView> operations = spec.operations() == null ? List.of() : spec.operations().stream()
                .map(op -> new OperationView(op.opName(), op.method(), op.pathTemplate(), op.description()))
                .toList();
        List<String> authHeaderNames = spec.authHeaders() == null ? List.of() : spec.authHeaders().stream()
                .map(OpenApiToolSpec.AuthHeader::name)
                .toList();
        return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                Boolean.TRUE.equals(row.getEnabled()), spec.baseUrl(), operations, authHeaderNames, spec.rawSpec());
    }

    public ToolPreviewResponse preview(String specText) {
        ParsedOpenApi parsed = parser.parse(specText);
        List<OperationView> operations = parsed.operations() == null ? List.of() : parsed.operations().stream()
                .map(op -> new OperationView(op.opName(), op.method(), op.pathTemplate(), op.description()))
                .toList();
        return new ToolPreviewResponse(parsed.baseUrl(), operations);
    }

    @Transactional
    public ToolAdminResponse update(Long id, UpdateToolRequest req) {
        Tool row = require(id);
        assertOpenApi(row, "修改");
        assertNameFree(req.name(), id);
        row.setName(req.name());
        row.setDescription(req.description());
        row.setSpec(buildSpec(req.specText(), req.authHeaders()));
        toolMapper.updateById(row);
        return toResponse(row);
    }

    @Transactional
    public void delete(Long id) {
        Tool row = require(id);
        assertOpenApi(row, "删除");
        toolMapper.deleteById(id);
    }

    @Transactional
    public void enable(Long id) {
        setEnabled(id, true);
    }

    @Transactional
    public void disable(Long id) {
        setEnabled(id, false);
    }

    private void setEnabled(Long id, boolean enabled) {
        Tool row = require(id);
        row.setEnabled(enabled);
        toolMapper.updateById(row);
    }

    private OpenApiToolSpec buildSpec(String specText, List<AuthHeaderInput> headers) {
        ParsedOpenApi parsed = parser.parse(specText);
        List<OpenApiToolSpec.AuthHeader> encHeaders = new ArrayList<>();
        if (headers != null) {
            for (AuthHeaderInput h : headers) {
                encHeaders.add(new OpenApiToolSpec.AuthHeader(h.name(), cipher.encrypt(h.value())));
            }
        }
        return new OpenApiToolSpec(parsed.baseUrl(), encHeaders, parsed.operations(), specText);
    }

    private Tool require(Long id) {
        Tool row = toolMapper.selectById(id);
        if (row == null) {
            throw new BizException(CommonError.NOT_FOUND, "工具不存在");
        }
        return row;
    }

    private void assertOpenApi(Tool row, String action) {
        if (!"openapi".equals(row.getSource())) {
            throw new BizException(CommonError.PARAM_INVALID, "内置工具不可" + action);
        }
    }

    private void assertNameFree(String name, Long excludeId) {
        LambdaQueryWrapper<Tool> w = new LambdaQueryWrapper<Tool>().eq(Tool::getName, name);
        if (excludeId != null) {
            w.ne(Tool::getId, excludeId);
        }
        if (toolMapper.selectCount(w) > 0) {
            throw new BizException(CommonError.CONFLICT, "工具名称已存在");
        }
    }

    private ToolAdminResponse toResponse(Tool row) {
        Integer count = row.getSpec() == null || row.getSpec().operations() == null
                ? null
                : row.getSpec().operations().size();
        return new ToolAdminResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                Boolean.TRUE.equals(row.getEnabled()), count, row.getOwnerId(), row.getCreateTime(), row.getUpdateTime());
    }
}
