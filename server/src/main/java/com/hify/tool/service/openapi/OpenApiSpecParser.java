package com.hify.tool.service.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.tool.constant.ToolError;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAPI 文档解析：swagger-parser 解 $ref/YAML/JSON，抽出 baseUrl + 每操作的
 * method/path/描述/合并入参 inputSchema。失败一律抛 {@link ToolError#SPEC_PARSE_FAILED}。
 */
@Component
public class OpenApiSpecParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ParsedOpenApi parse(String specText) {
        OpenAPI openAPI = readOrThrow(specText);
        String baseUrl = baseUrlOrThrow(openAPI);
        List<OpenApiToolSpec.Operation> ops = new ArrayList<>();
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, item) ->
                    item.readOperationsMap().forEach((httpMethod, op) ->
                            ops.add(toOperation(path, httpMethod, op))));
        }
        if (ops.isEmpty()) {
            throw new BizException(ToolError.SPEC_PARSE_FAILED, "文档里没有任何可用接口操作");
        }
        return new ParsedOpenApi(baseUrl, ops);
    }

    private OpenAPI readOrThrow(String specText) {
        try {
            ParseOptions opts = new ParseOptions();
            opts.setResolveFully(true);
            SwaggerParseResult result = new OpenAPIV3Parser().readContents(specText, null, opts);
            OpenAPI openAPI = result.getOpenAPI();
            if (openAPI == null) {
                String msg = (result.getMessages() == null || result.getMessages().isEmpty())
                        ? "无法识别为 OpenAPI 文档" : String.join("; ", result.getMessages());
                throw new BizException(ToolError.SPEC_PARSE_FAILED, msg);
            }
            return openAPI;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ToolError.SPEC_PARSE_FAILED, "解析异常：" + e.getMessage());
        }
    }

    private String baseUrlOrThrow(OpenAPI openAPI) {
        if (openAPI.getServers() == null || openAPI.getServers().isEmpty()) {
            throw new BizException(ToolError.SPEC_PARSE_FAILED, "文档缺少 servers（需绝对 baseUrl）");
        }
        String url = openAPI.getServers().get(0).getUrl();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BizException(ToolError.SPEC_PARSE_FAILED, "servers[0].url 必须是绝对 http/https 地址");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private OpenApiToolSpec.Operation toOperation(String path, PathItem.HttpMethod httpMethod, Operation op) {
        String method = httpMethod.name();
        String opName = (op.getOperationId() != null && !op.getOperationId().isBlank())
                ? sanitize(op.getOperationId())
                : sanitize(method.toLowerCase(Locale.ROOT) + "_" + path);
        String description = firstNonBlank(op.getSummary(), op.getDescription(), opName);

        List<OpenApiToolSpec.Param> params = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                if (p.getName() == null || p.getIn() == null) continue;
                params.add(new OpenApiToolSpec.Param(p.getName(), p.getIn(), Boolean.TRUE.equals(p.getRequired())));
                properties.put(p.getName(), schemaProp(p.getSchema(), p.getDescription()));
                if (Boolean.TRUE.equals(p.getRequired())) required.add(p.getName());
            }
        }
        RequestBody body = op.getRequestBody();
        if (body != null && body.getContent() != null && body.getContent().get("application/json") != null) {
            Schema<?> bodySchema = body.getContent().get("application/json").getSchema();
            if (bodySchema != null && bodySchema.getProperties() != null) {
                List<?> bodyRequired = bodySchema.getRequired() == null ? List.of() : bodySchema.getRequired();
                bodySchema.getProperties().forEach((k, v) -> {
                    String name = String.valueOf(k);
                    boolean req = bodyRequired.contains(name);
                    params.add(new OpenApiToolSpec.Param(name, "body", req));
                    properties.put(name, schemaProp((Schema<?>) v, ((Schema<?>) v).getDescription()));
                    if (req) required.add(name);
                });
            }
        }

        String inputSchema = writeSchema(properties, required);
        return new OpenApiToolSpec.Operation(opName, method, path, description, inputSchema, params);
    }

    private Map<String, Object> schemaProp(Schema<?> schema, String desc) {
        Map<String, Object> prop = new LinkedHashMap<>();
        String type = (schema != null && schema.getType() != null) ? schema.getType() : "string";
        prop.put("type", type);
        String d = firstNonBlank(desc, schema == null ? null : schema.getDescription(), null);
        if (d != null) prop.put("description", d);
        return prop;
    }

    private String writeSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            throw new BizException(ToolError.SPEC_PARSE_FAILED, "构造入参 schema 失败：" + e.getMessage());
        }
    }

    private static String sanitize(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_+", "").replaceAll("_+$", "");
        return s.isBlank() ? "op" : s;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
