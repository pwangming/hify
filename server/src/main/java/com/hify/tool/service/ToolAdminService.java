package com.hify.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.security.CurrentUser;
import com.hify.tool.dto.AuthHeaderInput;
import com.hify.tool.dto.CreateToolRequest;
import com.hify.tool.dto.McpToolView;
import com.hify.tool.dto.OperationView;
import com.hify.tool.dto.PreviewToolRequest;
import com.hify.tool.dto.ToolPreviewResponse;
import com.hify.tool.dto.ToolAdminDetailResponse;
import com.hify.tool.dto.ToolAdminResponse;
import com.hify.tool.dto.UpdateToolRequest;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.mcp.DiscoveredMcpTools;
import com.hify.tool.service.mcp.McpClientFactory;
import com.hify.tool.service.mcp.McpToolDiscoverer;
import com.hify.tool.service.mcp.McpToolSpec;
import com.hify.tool.service.openapi.OpenApiSpecParser;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import com.hify.tool.service.openapi.ParsedOpenApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义工具注册表 admin CRUD（Admin 专属）。openapi 行可增删改；builtin 行只可启停、不可删改。
 * 凭据加密走 infra SecretCipher，只存密文；解析走 OpenApiSpecParser。@Transactional 只在写方法，内无外部 IO。
 */
@Service
public class ToolAdminService {

    private final ToolMapper toolMapper;
    private final OpenApiSpecParser parser;
    private final SecretCipher cipher;
    private final McpToolDiscoverer discoverer;

    public ToolAdminService(ToolMapper toolMapper, OpenApiSpecParser parser, SecretCipher cipher,
                            McpToolDiscoverer discoverer) {
        this.toolMapper = toolMapper;
        this.parser = parser;
        this.cipher = cipher;
        this.discoverer = discoverer;
    }

    @Transactional
    public ToolAdminResponse create(CreateToolRequest req, CurrentUser current) {
        assertNameFree(req.name(), null);
        boolean mcp = CreateToolRequest.TYPE_MCP.equals(req.typeOrDefault());
        Tool row = new Tool();
        row.setName(req.name());
        row.setDescription(req.description());
        row.setSource(mcp ? "mcp" : "openapi");
        row.setEnabled(true);
        row.setOwnerId(current.userId());
        row.setSpec(mcp
                ? buildMcpSpec(req.url(), req.transport(), req.authHeaders(), null)
                : buildSpecForCreate(req.specText(), req.authHeaders()));
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
        boolean enabled = Boolean.TRUE.equals(row.getEnabled());
        if (row.getSpec() instanceof McpToolSpec spec) {
            List<McpToolView> tools = spec.tools() == null ? List.of() : spec.tools().stream()
                    .map(t -> new McpToolView(t.toolName(), t.description()))
                    .toList();
            return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                    enabled, null, List.of(), authHeaderNames(spec.authHeaders(), null), null,
                    spec.url(), spec.transport(), tools, spec.discoveredAt());
        }
        if (!(row.getSpec() instanceof OpenApiToolSpec spec)) {
            return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                    enabled, null, List.of(), List.of(), null, null, null, List.of(), null);
        }
        List<OperationView> operations = spec.operations() == null ? List.of() : spec.operations().stream()
                .map(op -> new OperationView(op.opName(), op.method(), op.pathTemplate(), op.description()))
                .toList();
        return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                enabled, spec.baseUrl(), operations, authHeaderNames(null, spec.authHeaders()), spec.rawSpec(),
                null, null, List.of(), null);
    }

    public ToolPreviewResponse preview(PreviewToolRequest req) {
        if (CreateToolRequest.TYPE_MCP.equals(req.typeOrDefault())) {
            Map<String, String> plain = new LinkedHashMap<>();
            if (req.authHeaders() != null) {
                for (AuthHeaderInput h : req.authHeaders()) {
                    if (h.value() != null && !h.value().isBlank()) {
                        plain.put(h.name(), h.value());
                    }
                }
            }
            DiscoveredMcpTools found = discoverer.discover(req.url(), transportOrDefault(req.transport()), plain);
            return new ToolPreviewResponse(null, List.of(), found.tools().stream()
                    .map(t -> new McpToolView(t.toolName(), t.description()))
                    .toList());
        }
        ParsedOpenApi parsed = parser.parse(req.specText());
        List<OperationView> operations = parsed.operations() == null ? List.of() : parsed.operations().stream()
                .map(op -> new OperationView(op.opName(), op.method(), op.pathTemplate(), op.description()))
                .toList();
        return new ToolPreviewResponse(parsed.baseUrl(), operations, List.of());
    }

    @Transactional
    public ToolAdminResponse update(Long id, UpdateToolRequest req) {
        Tool row = require(id);
        assertNotBuiltin(row, "修改");
        assertNameFree(req.name(), id);
        row.setName(req.name());
        row.setDescription(req.description());
        if ("mcp".equals(row.getSource())) {
            row.setSpec(buildMcpSpec(req.url(), req.transport(), req.authHeaders(),
                    row.getSpec() instanceof McpToolSpec old ? old : null));
        } else {
            row.setSpec(buildSpecForUpdate(req.specText(), req.authHeaders(),
                    row.getSpec() instanceof OpenApiToolSpec old ? old : null));
        }
        toolMapper.updateById(row);
        return toResponse(row);
    }

    @Transactional
    public void delete(Long id) {
        Tool row = require(id);
        assertNotBuiltin(row, "删除");
        toolMapper.deleteById(id);
    }

    /** 重新发现 MCP 工具清单覆盖快照；鉴权头密文原样保留（admin 没重填凭据，不能弄丢）。 */
    @Transactional
    public ToolAdminResponse refresh(Long id) {
        Tool row = require(id);
        if (!"mcp".equals(row.getSource())) {
            throw new BizException(CommonError.PARAM_INVALID, "只有 MCP 工具支持刷新");
        }
        McpToolSpec old = row.getSpec() instanceof McpToolSpec s ? s : null;
        if (old == null) {
            throw new BizException(CommonError.PARAM_INVALID, "MCP 工具配置为空，无法刷新");
        }
        Map<String, String> plain = decryptHeaders(old.authHeaders());
        DiscoveredMcpTools found = discoverer.discover(old.url(), old.transport(), plain);
        row.setSpec(new McpToolSpec(old.url(), old.transport(), old.authHeaders(),
                found.tools(), OffsetDateTime.now()));
        toolMapper.updateById(row);
        return toResponse(row);
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

    private OpenApiToolSpec buildSpecForCreate(String specText, List<AuthHeaderInput> headers) {
        ParsedOpenApi parsed = parser.parse(specText);
        List<OpenApiToolSpec.AuthHeader> encHeaders = new ArrayList<>();
        if (headers != null) {
            for (AuthHeaderInput h : headers) {
                if (h.value() == null || h.value().isBlank()) {
                    throw new BizException(CommonError.PARAM_INVALID, "鉴权头「" + h.name() + "」的值不能为空");
                }
                encHeaders.add(new OpenApiToolSpec.AuthHeader(h.name(), cipher.encrypt(h.value())));
            }
        }
        return new OpenApiToolSpec(parsed.baseUrl(), encHeaders, parsed.operations(), specText);
    }

    /** update：某头 value 留空 → 按 name 保留旧密文；有值 → 重新加密；不出现的头名 = 删除。 */
    private OpenApiToolSpec buildSpecForUpdate(String specText, List<AuthHeaderInput> headers, OpenApiToolSpec old) {
        ParsedOpenApi parsed = parser.parse(specText);
        Map<String, String> oldEncByName = new HashMap<>();
        if (old != null && old.authHeaders() != null) {
            for (OpenApiToolSpec.AuthHeader h : old.authHeaders()) {
                oldEncByName.put(h.name(), h.valueEnc());
            }
        }
        List<OpenApiToolSpec.AuthHeader> encHeaders = new ArrayList<>();
        if (headers != null) {
            for (AuthHeaderInput h : headers) {
                String enc;
                if (h.value() == null || h.value().isBlank()) {
                    enc = oldEncByName.get(h.name());
                    if (enc == null) {
                        throw new BizException(CommonError.PARAM_INVALID, "鉴权头「" + h.name() + "」的值不能为空");
                    }
                } else {
                    enc = cipher.encrypt(h.value());
                }
                encHeaders.add(new OpenApiToolSpec.AuthHeader(h.name(), enc));
            }
        }
        return new OpenApiToolSpec(parsed.baseUrl(), encHeaders, parsed.operations(), specText);
    }

    private static String transportOrDefault(String transport) {
        return transport == null || transport.isBlank() ? McpClientFactory.TRANSPORT_STREAMABLE_HTTP : transport;
    }

    /** create/update 共用：发现工具 + 加密鉴权头（留空=保留旧密文，与 openapi 侧同款语义）。 */
    private McpToolSpec buildMcpSpec(String url, String transport, List<AuthHeaderInput> headers, McpToolSpec old) {
        String tp = transportOrDefault(transport);
        Map<String, String> oldEncByName = new HashMap<>();
        if (old != null && old.authHeaders() != null) {
            for (McpToolSpec.AuthHeader h : old.authHeaders()) {
                oldEncByName.put(h.name(), h.valueEnc());
            }
        }
        List<McpToolSpec.AuthHeader> encHeaders = new ArrayList<>();
        Map<String, String> plain = new LinkedHashMap<>();
        if (headers != null) {
            for (AuthHeaderInput h : headers) {
                String enc;
                if (h.value() == null || h.value().isBlank()) {
                    enc = oldEncByName.get(h.name());
                    if (enc == null) {
                        throw new BizException(CommonError.PARAM_INVALID, "鉴权头「" + h.name() + "」的值不能为空");
                    }
                    plain.put(h.name(), cipher.decrypt(enc));
                } else {
                    enc = cipher.encrypt(h.value());
                    plain.put(h.name(), h.value());
                }
                encHeaders.add(new McpToolSpec.AuthHeader(h.name(), enc));
            }
        }
        DiscoveredMcpTools found = discoverer.discover(url, tp, plain);
        return new McpToolSpec(url, tp, encHeaders, found.tools(), OffsetDateTime.now());
    }

    private Map<String, String> decryptHeaders(List<McpToolSpec.AuthHeader> headers) {
        Map<String, String> plain = new LinkedHashMap<>();
        if (headers != null) {
            for (McpToolSpec.AuthHeader h : headers) {
                plain.put(h.name(), cipher.decrypt(h.valueEnc()));
            }
        }
        return plain;
    }

    private static List<String> authHeaderNames(List<McpToolSpec.AuthHeader> mcp,
                                                List<OpenApiToolSpec.AuthHeader> openApi) {
        if (mcp != null) {
            return mcp.stream().map(McpToolSpec.AuthHeader::name).toList();
        }
        return openApi == null ? List.of() : openApi.stream().map(OpenApiToolSpec.AuthHeader::name).toList();
    }

    private Tool require(Long id) {
        Tool row = toolMapper.selectById(id);
        if (row == null) {
            throw new BizException(CommonError.NOT_FOUND, "工具不存在");
        }
        return row;
    }

    private void assertNotBuiltin(Tool row, String action) {
        if ("builtin".equals(row.getSource())) {
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
        Integer count = null;
        if (row.getSpec() instanceof OpenApiToolSpec s && s.operations() != null) {
            count = s.operations().size();
        } else if (row.getSpec() instanceof McpToolSpec m && m.tools() != null) {
            count = m.tools().size();     // 字段名保留（改名会弄坏 T3b 前端），mcp 行含义 = 工具数
        }
        return new ToolAdminResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                Boolean.TRUE.equals(row.getEnabled()), count, row.getOwnerId(), row.getCreateTime(), row.getUpdateTime());
    }
}
