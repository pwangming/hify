# T3b：OpenAPI 自定义工具 admin 注册表页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 admin 能在管理控制台注册/预览/编辑/启停/删除 OpenAPI 自定义工具，并在 Agent 应用里勾选试聊，端到端打通。

**Architecture:** 复用 T3a 已交付的 `/api/v1/admin/tool/tools` 后端契约；本轮补 2 处后端小改动（预览接口 + update「头值留空=不改」），再照 `ProviderList.vue` 的「列表 + 抽屉」模式写前端 `ToolList.vue`（用 el-drawer，不用 dialog）。

**Tech Stack:** 后端 Spring Boot 3 + MyBatis-Plus + swagger-parser（已引入）；前端 Vue 3 `<script setup>` + Element Plus + vitest + happy-dom。

## Global Constraints

- 设计文档：`docs/superpowers/specs/2026-07-15-agent-tool-t3b-openapi-admin-ui-design.md`（与之冲突的代码视为错误）。
- admin 路由固定 `/api/v1/admin/tool/tools`；一期**不用 PATCH**，启停走动作子资源 POST。
- Long 一律序列化为字符串；前端 `id`/`ownerId` 按 `string` 接。
- 后端只增不改已发布契约：preview 是新路由；update「空值语义」是放宽行为。
- 判定后端测试结果**必须 `mvn clean test` 全量**（不带 clean 的定向 `-Dtest=` 跑会因 swagger 类 `NoClassDefFoundError` 假红，见 memory `mvn-quiet-verify-pitfall`）。
- 前端新代码先写失败测试（TDD），测试放 `__tests__/`；优先用 Element Plus 组件，不自造。
- SSRF 防护：验收用**公网** OpenAPI 工具，禁内网/元数据地址，别用 httpbin。

---

### Task 1：后端 preview 接口（只解析不落库）

**Files:**
- Create: `server/src/main/java/com/hify/tool/dto/PreviewToolRequest.java`
- Create: `server/src/main/java/com/hify/tool/dto/ToolPreviewResponse.java`
- Modify: `server/src/main/java/com/hify/tool/service/ToolAdminService.java`（新增 `preview` 方法）
- Modify: `server/src/main/java/com/hify/tool/controller/AdminToolController.java`（新增 `POST /preview`）
- Test: `server/src/test/java/com/hify/tool/service/ToolAdminServiceTest.java`（加 2 例）
- Test: `server/src/test/java/com/hify/tool/controller/AdminToolControllerTest.java`（加 1 例）

**Interfaces:**
- Consumes: `OpenApiSpecParser.parse(String) → ParsedOpenApi(String baseUrl, List<OpenApiToolSpec.Operation> operations)`；`OperationView(String opName, String method, String pathTemplate, String description)`（T3a 既有）。
- Produces:
  - `ToolAdminService.preview(String specText) → ToolPreviewResponse`
  - `ToolPreviewResponse(String baseUrl, List<OperationView> operations)`
  - `PreviewToolRequest(String specText)`
  - 路由 `POST /api/v1/admin/tool/tools/preview`

- [x] **Step 1：写失败测试（service 层，加进 ToolAdminServiceTest）**

```java
@Test
void preview_parsesWithoutPersisting() {
    when(parser.parse("SPEC")).thenReturn(parsed());
    com.hify.tool.dto.ToolPreviewResponse resp = service.preview("SPEC");
    assertThat(resp.baseUrl()).isEqualTo("https://api.example.com");
    assertThat(resp.operations()).extracting(o -> o.opName()).containsExactly("getPet");
    org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any());
}

@Test
void preview_parseFailure_propagates() {
    when(parser.parse("BAD")).thenThrow(new BizException(
            com.hify.tool.constant.ToolError.SPEC_PARSE_FAILED, "解析失败"));
    assertThatThrownBy(() -> service.preview("BAD"))
            .isInstanceOf(BizException.class)
            .satisfies(e -> assertThat(((BizException) e).errorCode())
                    .isEqualTo(com.hify.tool.constant.ToolError.SPEC_PARSE_FAILED));
}
```

- [x] **Step 2：跑测试确认失败**

Run: `cd server && mvn -q clean test -Dtest=ToolAdminServiceTest`
Expected: 编译失败（`preview` / `ToolPreviewResponse` 未定义）。

- [x] **Step 3：建 DTO**

`PreviewToolRequest.java`：
```java
package com.hify.tool.dto;

import jakarta.validation.constraints.NotBlank;

/** 预览：仅粘贴 OpenAPI 文档，服务端只解析不落库。 */
public record PreviewToolRequest(@NotBlank String specText) {}
```

`ToolPreviewResponse.java`：
```java
package com.hify.tool.dto;

import java.util.List;

/** 预览结果：baseUrl + 解析出的操作摘要（不含鉴权/inputSchema 细节）。 */
public record ToolPreviewResponse(String baseUrl, List<OperationView> operations) {}
```

- [x] **Step 4：ToolAdminService 加 preview 方法**（放在 `get(...)` 之后、`update(...)` 之前；非 `@Transactional`）

```java
public ToolPreviewResponse preview(String specText) {
    ParsedOpenApi parsed = parser.parse(specText);
    List<OperationView> operations = parsed.operations() == null ? List.of() : parsed.operations().stream()
            .map(op -> new OperationView(op.opName(), op.method(), op.pathTemplate(), op.description()))
            .toList();
    return new ToolPreviewResponse(parsed.baseUrl(), operations);
}
```

加 import：`com.hify.tool.dto.PreviewToolRequest`（controller 用）、`com.hify.tool.dto.ToolPreviewResponse`。

- [x] **Step 5：AdminToolController 加 preview 端点**（放在 `list()` 之后）

```java
@PostMapping("/preview")
public Result<ToolPreviewResponse> preview(@Valid @RequestBody PreviewToolRequest request) {
    return Result.ok(toolAdminService.preview(request.specText()));
}
```

加 import：`com.hify.tool.dto.PreviewToolRequest`、`com.hify.tool.dto.ToolPreviewResponse`。

- [x] **Step 6：controller 测试加 1 例（AdminToolControllerTest）**

```java
@Test
void 预览_admin_200且返回操作() throws Exception {
    when(toolAdminService.preview(any())).thenReturn(
            new com.hify.tool.dto.ToolPreviewResponse("https://api.example.com",
                    java.util.List.of(new com.hify.tool.dto.OperationView("getPet", "GET", "/pets/{id}", "查"))));

    mockMvc.perform(post("/api/v1/admin/tool/tools/preview")
                    .header("Authorization", "Bearer " + adminToken())
                    .contentType("application/json")
                    .content("{\"specText\":\"openapi: 3.0.0\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.baseUrl").value("https://api.example.com"))
            .andExpect(jsonPath("$.data.operations[0].opName").value("getPet"));
}
```

- [x] **Step 7：跑测试确认通过**

Run: `cd server && mvn -q clean test -Dtest=ToolAdminServiceTest,AdminToolControllerTest`
Expected: 全绿。

- [x] **Step 8：提交**

```bash
git add server/src/main/java/com/hify/tool/dto/PreviewToolRequest.java \
        server/src/main/java/com/hify/tool/dto/ToolPreviewResponse.java \
        server/src/main/java/com/hify/tool/service/ToolAdminService.java \
        server/src/main/java/com/hify/tool/controller/AdminToolController.java \
        server/src/test/java/com/hify/tool/service/ToolAdminServiceTest.java \
        server/src/test/java/com/hify/tool/controller/AdminToolControllerTest.java
git commit -m "feat(tool): admin 工具预览接口 POST /preview(只解析不落库)"
```

---

### Task 2：后端 update「头值留空=不改」

**Files:**
- Modify: `server/src/main/java/com/hify/tool/dto/AuthHeaderInput.java`（去掉 `value` 的 `@NotBlank`）
- Modify: `server/src/main/java/com/hify/tool/service/ToolAdminService.java`（拆 `buildSpecForCreate` / `buildSpecForUpdate`）
- Test: `server/src/test/java/com/hify/tool/service/ToolAdminServiceTest.java`（加 3 例）

**Interfaces:**
- Consumes: `OpenApiToolSpec(String baseUrl, List<AuthHeader> authHeaders, List<Operation> operations, String rawSpec)`；`OpenApiToolSpec.AuthHeader(String name, String valueEnc)`（T3a 既有）。
- Produces：语义变化——update 时 `AuthHeaderInput.value` 为空白 → 按 `name` 保留旧 `valueEnc`；create 时空白 → 抛 `CommonError.PARAM_INVALID`。

- [ ] **Step 1：写失败测试（加进 ToolAdminServiceTest）**

```java
@Test
void update_blankHeaderValue_keepsOldCipher() {
    Tool row = openApiRow(); // 旧头 X-API-Key=ENC
    when(mapper.selectById(9L)).thenReturn(row);
    when(mapper.selectCount(any())).thenReturn(0L);
    when(parser.parse(any())).thenReturn(parsed());

    service.update(9L, new com.hify.tool.dto.UpdateToolRequest("petstore2", "改名", "SPEC",
            List.of(new AuthHeaderInput("X-API-Key", "")))); // 值留空

    ArgumentCaptor<Tool> saved = ArgumentCaptor.forClass(Tool.class);
    org.mockito.Mockito.verify(mapper).updateById(saved.capture());
    assertThat(saved.getValue().getName()).isEqualTo("petstore2");
    assertThat(saved.getValue().getSpec().authHeaders().get(0).valueEnc()).isEqualTo("ENC"); // 保留
    org.mockito.Mockito.verify(cipher, org.mockito.Mockito.never()).encrypt(""); // 没重新加密空串
}

@Test
void update_newHeaderValue_reEncrypts() {
    Tool row = openApiRow();
    when(mapper.selectById(9L)).thenReturn(row);
    when(mapper.selectCount(any())).thenReturn(0L);
    when(parser.parse(any())).thenReturn(parsed());
    when(cipher.encrypt("newk")).thenReturn("NEWENC");

    service.update(9L, new com.hify.tool.dto.UpdateToolRequest("petstore", "x", "SPEC",
            List.of(new AuthHeaderInput("X-API-Key", "newk"))));

    ArgumentCaptor<Tool> saved = ArgumentCaptor.forClass(Tool.class);
    org.mockito.Mockito.verify(mapper).updateById(saved.capture());
    assertThat(saved.getValue().getSpec().authHeaders().get(0).valueEnc()).isEqualTo("NEWENC");
}

@Test
void create_blankHeaderValue_rejected() {
    when(parser.parse(any())).thenReturn(parsed());
    when(mapper.selectCount(any())).thenReturn(0L);
    CreateToolRequest req = new CreateToolRequest("petstore", "宠物", "SPEC",
            List.of(new AuthHeaderInput("X-API-Key", "")));
    assertThatThrownBy(() -> service.create(req, admin))
            .isInstanceOf(BizException.class)
            .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(CommonError.PARAM_INVALID));
}
```

- [ ] **Step 2：跑测试确认失败**

Run: `cd server && mvn -q clean test -Dtest=ToolAdminServiceTest`
Expected: `update_blankHeaderValue_keepsOldCipher` 等失败（当前空值会被加密/或被 @NotBlank 挡）。

- [ ] **Step 3：去掉 AuthHeaderInput.value 的 @NotBlank**

`AuthHeaderInput.java` 改为：
```java
package com.hify.tool.dto;

import jakarta.validation.constraints.NotBlank;

/** 鉴权头输入：name 必填；value 明文——create 必填、update 留空=保留原密文（在 service 分流校验）。 */
public record AuthHeaderInput(@NotBlank String name, String value) {}
```

- [ ] **Step 4：ToolAdminService 拆 buildSpec**

删除现有 `private OpenApiToolSpec buildSpec(...)`，替换为下面两个方法；并把 `create` 里 `buildSpec(req.specText(), req.authHeaders())` 改为 `buildSpecForCreate(...)`、`update` 里改为 `buildSpecForUpdate(req.specText(), req.authHeaders(), row.getSpec())`。

```java
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
    java.util.Map<String, String> oldEncByName = new java.util.HashMap<>();
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
```

- [ ] **Step 5：跑测试确认通过**

Run: `cd server && mvn -q clean test -Dtest=ToolAdminServiceTest`
Expected: 全绿（含原有 6 例 + 新增 3 例）。

- [ ] **Step 6：提交**

```bash
git add server/src/main/java/com/hify/tool/dto/AuthHeaderInput.java \
        server/src/main/java/com/hify/tool/service/ToolAdminService.java \
        server/src/test/java/com/hify/tool/service/ToolAdminServiceTest.java
git commit -m "feat(tool): update 鉴权头值留空=保留原密文(仿 provider apiKey),create 空值拦"
```

---

### Task 3：前端类型 + api 层 + 路由 + ToolList 列表渲染

**Files:**
- Modify: `web/src/types/tool.ts`（在既有 `ToolOption` 后追加 admin 类型）
- Create: `web/src/api/admin/tool.ts`
- Modify: `web/src/router/index.ts`（新增 `/admin/tool` 路由）
- Create: `web/src/views/admin/tool/ToolList.vue`（本轮只做表格 + 加载）
- Test: `web/src/views/admin/tool/__tests__/ToolList.spec.ts`

**Interfaces:**
- Consumes: `request.get/post/put/delete`（`@/api/request`，baseURL 已含 `/api/v1`）；后端 `ToolAdminResponse` / `ToolAdminDetailResponse` / `ToolPreviewResponse` 契约。
- Produces（供 Task 4 复用）：
  - 类型 `ToolAdminItem` / `ToolOperation` / `ToolAdminDetail` / `AuthHeaderInput` / `ToolForm` / `ToolPreview`
  - api：`listTools()` / `getTool(id)` / `createTool(body)` / `updateTool(id,body)` / `removeTool(id)` / `enableTool(id)` / `disableTool(id)` / `previewTool(specText)`
  - 组件 `ToolList.vue`（`data-test="tool-table"`、类型标签、内置行无操作按钮）

- [ ] **Step 1：写失败测试（ToolList.spec.ts）**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import { listTools } from '@/api/admin/tool'
import type { ToolAdminItem } from '@/types/tool'
import ToolList from '@/views/admin/tool/ToolList.vue'

vi.mock('@/api/admin/tool', () => ({
  listTools: vi.fn(),
  getTool: vi.fn(),
  createTool: vi.fn(),
  updateTool: vi.fn(),
  removeTool: vi.fn(),
  enableTool: vi.fn(),
  disableTool: vi.fn(),
  previewTool: vi.fn(),
}))

// el-table 依赖 ResizeObserver，happy-dom 未实现，补桩
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const SAMPLE: ToolAdminItem[] = [
  { id: '1', name: 'http_request', description: '发起 HTTP 请求', source: 'builtin', enabled: true,
    operationCount: null, ownerId: null, createTime: '2026-07-01T10:00:00+08:00', updateTime: '2026-07-01T10:00:00+08:00' },
  { id: '9', name: 'petstore', description: '宠物商店', source: 'openapi', enabled: true,
    operationCount: 3, ownerId: '1', createTime: '2026-07-10T10:00:00+08:00', updateTime: '2026-07-10T10:00:00+08:00' },
]

const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div/>' } }] })

function mountList() {
  return mount(ToolList, { global: { plugins: [ElementPlus, router] } } )
}

describe('ToolList 列表渲染', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listTools).mockResolvedValue(SAMPLE)
  })

  it('渲染内置与自定义行：内置打「内置」标签且无操作按钮，自定义有编辑/删除', async () => {
    const wrapper = mountList()
    await flushPromises()
    // 两行都在
    expect(wrapper.text()).toContain('http_request')
    expect(wrapper.text()).toContain('petstore')
    // 内置行无编辑按钮，自定义行有
    expect(wrapper.find('[data-test="edit-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="edit-9"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="delete-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="delete-9"]').exists()).toBe(true)
  })

  it('operationCount 内置显示 —，自定义显示数字', async () => {
    const wrapper = mountList()
    await flushPromises()
    expect(wrapper.text()).toContain('—')
    expect(wrapper.text()).toContain('3')
  })
})
```

- [ ] **Step 2：跑测试确认失败**

Run: `cd web && pnpm vitest run src/views/admin/tool`
Expected: FAIL（`ToolList.vue` / `@/api/admin/tool` 不存在）。

- [ ] **Step 3：追加前端类型（web/src/types/tool.ts，保留既有 ToolOption）**

```ts
/** admin 列表项（对齐后端 ToolAdminResponse）。id/ownerId 为 string(Long)。 */
export interface ToolAdminItem {
  id: string
  name: string
  description: string
  source: string
  enabled: boolean
  operationCount: number | null
  ownerId: string | null
  createTime: string
  updateTime: string
}

/** 操作摘要（对齐后端 OperationView）。 */
export interface ToolOperation {
  opName: string
  method: string
  pathTemplate: string
  description: string
}

/** admin 详情（对齐后端 ToolAdminDetailResponse）。 */
export interface ToolAdminDetail {
  id: string
  name: string
  description: string
  source: string
  enabled: boolean
  baseUrl: string | null
  operations: ToolOperation[]
  authHeaderNames: string[]
  rawSpec: string | null
}

/** 鉴权头输入（value 明文；编辑时留空=不改）。 */
export interface AuthHeaderInput {
  name: string
  value: string
}

/** 注册/编辑表单（对齐后端 Create/UpdateToolRequest）。 */
export interface ToolForm {
  name: string
  description: string
  specText: string
  authHeaders: AuthHeaderInput[]
}

/** 预览结果（对齐后端 ToolPreviewResponse）。 */
export interface ToolPreview {
  baseUrl: string
  operations: ToolOperation[]
}
```

- [ ] **Step 4：建 api 层（web/src/api/admin/tool.ts）**

```ts
import { request } from '@/api/request'
import type { ToolAdminItem, ToolAdminDetail, ToolForm, ToolPreview } from '@/types/tool'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。
const BASE = '/admin/tool/tools'

/** 列出全部工具（含内置+自定义、含停用）。后端：GET /api/v1/admin/tool/tools */
export function listTools() {
  return request.get<ToolAdminItem[]>(BASE)
}
/** 工具详情（编辑回填）。后端：GET .../{id}（authHeaderNames 只回头名，绝不回明文值） */
export function getTool(id: string) {
  return request.get<ToolAdminDetail>(`${BASE}/${id}`)
}
/** 注册自定义工具。后端：POST .../ */
export function createTool(body: ToolForm) {
  return request.post<ToolAdminItem>(BASE, body)
}
/** 全量更新（头值留空=不改）。后端：PUT .../{id} */
export function updateTool(id: string, body: ToolForm) {
  return request.put<ToolAdminItem>(`${BASE}/${id}`, body)
}
/** 删除自定义工具。后端：DELETE .../{id} */
export function removeTool(id: string) {
  return request.delete<void>(`${BASE}/${id}`)
}
/** 启用。后端：POST .../{id}/enable */
export function enableTool(id: string) {
  return request.post<void>(`${BASE}/${id}/enable`)
}
/** 停用。后端：POST .../{id}/disable */
export function disableTool(id: string) {
  return request.post<void>(`${BASE}/${id}/disable`)
}
/** 预览解析（不落库）。后端：POST .../preview */
export function previewTool(specText: string) {
  return request.post<ToolPreview>(`${BASE}/preview`, { specText })
}
```

- [ ] **Step 5：建 ToolList.vue（本轮只表格 + 加载）**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listTools, removeTool, enableTool, disableTool } from '@/api/admin/tool'
import type { ToolAdminItem } from '@/types/tool'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const tools = ref<ToolAdminItem[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    tools.value = await listTools()
  } finally {
    loading.value = false
  }
}
onMounted(load)

function isBuiltin(row: ToolAdminItem): boolean {
  return row.source === 'builtin'
}

async function confirmDanger(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function onEnable(row: ToolAdminItem) {
  try {
    await enableTool(row.id)
    ElMessage.success('已启用')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onDisable(row: ToolAdminItem) {
  if (!(await confirmDanger(`确定停用工具「${row.name}」？`, '停用确认'))) return
  try {
    await disableTool(row.id)
    ElMessage.success('已停用')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onDelete(row: ToolAdminItem) {
  if (!(await confirmDanger(`确定删除工具「${row.name}」？此操作不可恢复。`, '删除确认'))) return
  try {
    await removeTool(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}
</script>

<template>
  <div class="tool-list">
    <PageHeader title="自定义工具" description="注册 OpenAPI 自定义工具，供 Agent 应用调用">
      <el-button type="primary" data-test="create-open" disabled>注册工具</el-button>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="tools" data-test="tool-table">
        <el-table-column prop="name" label="名称" />
        <el-table-column label="类型">
          <template #default="{ row }">
            <el-tag :type="isBuiltin(row as ToolAdminItem) ? 'info' : 'primary'">
              {{ isBuiltin(row as ToolAdminItem) ? '内置' : '自定义' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="操作数">
          <template #default="{ row }">{{ (row as ToolAdminItem).operationCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="(row as ToolAdminItem).enabled ? 'success' : 'info'">
              {{ (row as ToolAdminItem).enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320">
          <template #default="{ row }">
            <div class="tool-list__ops" v-if="!isBuiltin(row as ToolAdminItem)">
              <el-button
                v-if="(row as ToolAdminItem).enabled"
                :data-test="`disable-${(row as ToolAdminItem).id}`"
                size="small"
                @click="onDisable(row as ToolAdminItem)"
                >停用</el-button
              >
              <el-button
                v-else
                :data-test="`enable-${(row as ToolAdminItem).id}`"
                size="small"
                type="success"
                @click="onEnable(row as ToolAdminItem)"
                >启用</el-button
              >
              <el-button :data-test="`edit-${(row as ToolAdminItem).id}`" size="small" disabled>编辑</el-button>
              <el-button
                :data-test="`delete-${(row as ToolAdminItem).id}`"
                size="small"
                type="danger"
                @click="onDelete(row as ToolAdminItem)"
                >删除</el-button
              >
            </div>
            <span v-else class="tool-list__builtin-hint">内置工具（只读）</span>
          </template>
        </el-table-column>
      </el-table>
    </ContentCard>
  </div>
</template>

<style scoped lang="scss">
.tool-list__ops {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}
.tool-list__builtin-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
```

> 注：`createTime`/`updateTime` 已在类型里，暂不上表（列已够）。`formatDateTime` import 保留给 Task 4 详情用；若 lint 报未使用可在本任务先去掉、Task 4 再加回。

- [ ] **Step 6：加路由（web/src/router/index.ts，插到 `/admin/identity` 路由块之后）**

```ts
{
  path: '/admin/tool',
  name: 'ToolList',
  component: () => import('@/views/admin/tool/ToolList.vue'),
  meta: {
    requiresAuth: true,
    roles: ['admin'],
    title: '自定义工具',
    menu: true,
    icon: 'Connection',
    group: '管理控制台',
  },
},
```

（菜单由 `meta.menu` 自动派生，menu.ts 无需改。）

- [ ] **Step 7：跑测试确认通过**

Run: `cd web && pnpm vitest run src/views/admin/tool`
Expected: PASS（2 例）。

- [ ] **Step 8：跑一次全量前端测试保证没打破别的**

Run: `cd web && pnpm vitest run`
Expected: 全绿。

- [ ] **Step 9：提交**

```bash
git add web/src/types/tool.ts web/src/api/admin/tool.ts web/src/router/index.ts \
        web/src/views/admin/tool/ToolList.vue \
        web/src/views/admin/tool/__tests__/ToolList.spec.ts
git commit -m "feat(web): 自定义工具 admin 列表页(类型/api/路由/表格,内置只读)"
```

---

### Task 4：注册/编辑抽屉（预览 + 创建 + 编辑回填 + 启停删除已在 Task 3）

**Files:**
- Modify: `web/src/views/admin/tool/ToolList.vue`（加 el-drawer 表单 + 预览 + 创建/编辑；启用「注册工具」「编辑」按钮）
- Test: `web/src/views/admin/tool/__tests__/ToolList.spec.ts`（加交互例）

**Interfaces:**
- Consumes: Task 3 的 api（`getTool` / `createTool` / `updateTool` / `previewTool`）与类型（`ToolForm` / `ToolPreview` / `AuthHeaderInput`）。
- Produces：抽屉 `data-test="tool-drawer"`；表单元素 `form-name` / `form-description` / `form-spec` / `form-preview` / `form-submit`；预览结果区渲染 `operation-{opName}`。

- [ ] **Step 1：写失败测试（追加到 ToolList.spec.ts）**

> el-drawer 默认 `append-to-body` 会 teleport 到 body，happy-dom 下不易查。测试里把 `el-drawer` 用透传桩替换，直接内联渲染插槽，绕开 teleport。

```ts
import { getTool, createTool, updateTool, previewTool } from '@/api/admin/tool'
import type { ToolAdminDetail, ToolPreview } from '@/types/tool'

// 透传桩：内联渲染默认插槽 + footer 插槽，绕开 el-drawer 的 teleport
const drawerStub = {
  props: ['modelValue'],
  template: '<div v-if="modelValue" data-test="tool-drawer"><slot/><slot name="footer"/></div>',
}
function mountListWithDrawer() {
  return mount(ToolList, {
    global: { plugins: [ElementPlus, router], stubs: { 'el-drawer': drawerStub } },
  })
}

describe('ToolList 注册/编辑抽屉', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listTools).mockResolvedValue(SAMPLE)
  })

  it('注册：预览渲染操作列表，保存调 createTool', async () => {
    const preview: ToolPreview = {
      baseUrl: 'https://api.example.com',
      operations: [{ opName: 'getPet', method: 'GET', pathTemplate: '/pets/{id}', description: '查' }],
    }
    vi.mocked(previewTool).mockResolvedValue(preview)
    vi.mocked(createTool).mockResolvedValue(SAMPLE[1])

    const wrapper = mountListWithDrawer()
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="tool-drawer"]').exists()).toBe(true)

    await wrapper.get('[data-test="form-name"] input').setValue('petstore')
    await wrapper.get('[data-test="form-description"] input').setValue('宠物')
    await wrapper.get('[data-test="form-spec"] textarea').setValue('openapi: 3.0.0')
    await wrapper.get('[data-test="form-preview"]').trigger('click')
    await flushPromises()
    expect(previewTool).toHaveBeenCalledWith('openapi: 3.0.0')
    expect(wrapper.text()).toContain('getPet')

    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createTool).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'petstore', description: '宠物', specText: 'openapi: 3.0.0' }),
    )
  })

  it('预览失败不关抽屉（后端 13001 由拦截器 toast）', async () => {
    vi.mocked(previewTool).mockRejectedValue(new Error('parse fail'))
    const wrapper = mountListWithDrawer()
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    await wrapper.get('[data-test="form-spec"] textarea').setValue('bad')
    await wrapper.get('[data-test="form-preview"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="tool-drawer"]').exists()).toBe(true) // 仍开着
  })

  it('编辑：回填详情，头名预填且值框空，保存调 updateTool', async () => {
    const detail: ToolAdminDetail = {
      id: '9', name: 'petstore', description: '宠物商店', source: 'openapi', enabled: true,
      baseUrl: 'https://api.example.com',
      operations: [{ opName: 'getPet', method: 'GET', pathTemplate: '/pets/{id}', description: '查' }],
      authHeaderNames: ['X-API-Key'], rawSpec: 'openapi: 3.0.0',
    }
    vi.mocked(getTool).mockResolvedValue(detail)
    vi.mocked(updateTool).mockResolvedValue(SAMPLE[1])

    const wrapper = mountListWithDrawer()
    await flushPromises()
    await wrapper.get('[data-test="edit-9"]').trigger('click')
    await flushPromises()
    expect(getTool).toHaveBeenCalledWith('9')
    expect((wrapper.get('[data-test="form-name"] input').element as HTMLInputElement).value).toBe('petstore')
    // 头名回填、值为空
    expect(wrapper.text()).toContain('X-API-Key')

    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(updateTool).toHaveBeenCalledWith('9', expect.objectContaining({ name: 'petstore' }))
  })
})
```

- [ ] **Step 2：跑测试确认失败**

Run: `cd web && pnpm vitest run src/views/admin/tool`
Expected: FAIL（抽屉/表单元素不存在）。

- [ ] **Step 3：改写 ToolList.vue（在 Task 3 基础上加抽屉与表单逻辑）**

在 `<script setup>` 顶部 import 补齐：
```ts
import { onMounted, reactive, ref } from 'vue'
import { getTool, createTool, updateTool } from '@/api/admin/tool'
import { previewTool } from '@/api/admin/tool'
import type { ToolAdminItem, ToolForm, ToolOperation, AuthHeaderInput } from '@/types/tool'
```
（与 Task 3 已 import 的 `listTools/removeTool/enableTool/disableTool`、`ElMessage/ElMessageBox`、`PageHeader/ContentCard` 合并，去重。）

在 `onDelete` 之后追加抽屉状态与处理：
```ts
// —— 注册 / 编辑抽屉 ——
const drawerVisible = ref(false)
const editingId = ref<string | null>(null) // null=注册，否则=编辑该 id
const submitting = ref(false)
const previewing = ref(false)
const previewOps = ref<ToolOperation[]>([])
const form = reactive<ToolForm>({ name: '', description: '', specText: '', authHeaders: [] })

function resetForm() {
  form.name = ''
  form.description = ''
  form.specText = ''
  form.authHeaders = []
  previewOps.value = []
}

function openCreate() {
  editingId.value = null
  resetForm()
  drawerVisible.value = true
}

async function openEdit(row: ToolAdminItem) {
  editingId.value = row.id
  resetForm()
  drawerVisible.value = true
  try {
    const detail = await getTool(row.id)
    form.name = detail.name
    form.description = detail.description
    form.specText = detail.rawSpec ?? ''
    // 头名回填、值留空（留空=不改）
    form.authHeaders = detail.authHeaderNames.map((name) => ({ name, value: '' }))
    previewOps.value = detail.operations
  } catch {
    drawerVisible.value = false // 拉详情失败则收起，拦截器已 toast
  }
}

function addHeader() {
  form.authHeaders.push({ name: '', value: '' })
}
function removeHeader(i: number) {
  form.authHeaders.splice(i, 1)
}

async function onPreview() {
  if (!form.specText.trim()) {
    ElMessage.warning('请先粘贴 OpenAPI 文档')
    return
  }
  previewing.value = true
  try {
    const result = await previewTool(form.specText)
    previewOps.value = result.operations
    ElMessage.success(`解析成功，共 ${result.operations.length} 个操作`)
  } catch {
    /* 解析失败(13001)由拦截器 toast；抽屉保持打开 */
  } finally {
    previewing.value = false
  }
}

async function submitForm() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入名称')
    return
  }
  if (!form.specText.trim()) {
    ElMessage.warning('请粘贴 OpenAPI 文档')
    return
  }
  submitting.value = true
  try {
    if (editingId.value === null) {
      await createTool({ ...form, authHeaders: [...form.authHeaders] })
      ElMessage.success('工具已注册')
    } else {
      await updateTool(editingId.value, { ...form, authHeaders: [...form.authHeaders] })
      ElMessage.success('工具已更新')
    }
    drawerVisible.value = false
    await load()
  } catch {
    /* 失败(重名/解析)由拦截器 toast；抽屉保持打开让用户改 */
  } finally {
    submitting.value = false
  }
}
```

模板改动：
1. 「注册工具」按钮去掉 `disabled`，加 `@click="openCreate"`。
2. 「编辑」按钮去掉 `disabled`，加 `@click="openEdit(row as ToolAdminItem)"`。
3. 在 `</ContentCard>` 之后、`</div>` 之前插入抽屉：

```vue
    <el-drawer
      v-model="drawerVisible"
      data-test="tool-drawer"
      :title="editingId === null ? '注册工具' : '编辑工具'"
      size="600px"
    >
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="form.name" data-test="form-name" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" data-test="form-description" maxlength="500" />
        </el-form-item>
        <el-form-item label="OpenAPI">
          <el-input
            v-model="form.specText"
            data-test="form-spec"
            type="textarea"
            :rows="10"
            placeholder="粘贴 OpenAPI 3.0 文档（JSON 或 YAML）"
          />
        </el-form-item>
        <el-form-item label="鉴权头">
          <div class="tool-list__headers">
            <div v-for="(h, i) in form.authHeaders" :key="i" class="tool-list__header-row">
              <el-input v-model="h.name" placeholder="头名，如 X-API-Key" :data-test="`header-name-${i}`" />
              <el-input
                v-model="h.value"
                :placeholder="editingId === null ? '头值' : '留空=不改'"
                :data-test="`header-value-${i}`"
              />
              <el-button size="small" text type="danger" @click="removeHeader(i)">删除</el-button>
            </div>
            <el-button size="small" data-test="add-header" @click="addHeader">+ 添加请求头</el-button>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button data-test="form-preview" :loading="previewing" @click="onPreview">预览操作</el-button>
        </el-form-item>
        <el-form-item v-if="previewOps.length" label="操作列表">
          <ul class="tool-list__ops-preview">
            <li v-for="op in previewOps" :key="op.opName" :data-test="`operation-${op.opName}`">
              <strong>{{ op.opName }}</strong>
              <el-tag size="small">{{ op.method }}</el-tag>
              <code>{{ op.pathTemplate }}</code>
              <span class="tool-list__op-desc">{{ op.description }}</span>
            </li>
          </ul>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" data-test="form-submit" :loading="submitting" @click="submitForm">保存</el-button>
      </template>
    </el-drawer>
```

样式追加：
```scss
.tool-list__headers { width: 100%; }
.tool-list__header-row {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  margin-bottom: $spacing-sm;
}
.tool-list__ops-preview {
  margin: 0;
  padding-left: 1.2em;
  li { line-height: 1.9; }
  .tool-list__op-desc { color: var(--el-text-color-secondary); margin-left: 6px; }
}
```

- [ ] **Step 4：跑测试确认通过**

Run: `cd web && pnpm vitest run src/views/admin/tool`
Expected: PASS（Task 3 的 2 例 + 本轮 3 例）。

> 若「注册」用例里 `form-name input` / `form-spec textarea` 取不到（happy-dom 对 el-input 的内部 input/textarea 渲染差异），改用 `wrapper.findComponent` 定位对应 `el-input` 后 `setValue`，或在断言前 `await wrapper.vm.$nextTick()`。核心断言是 `previewTool`/`createTool`/`updateTool` 的调用参数。

- [ ] **Step 5：全量前端测试**

Run: `cd web && pnpm vitest run`
Expected: 全绿。

- [ ] **Step 6：提交**

```bash
git add web/src/views/admin/tool/ToolList.vue \
        web/src/views/admin/tool/__tests__/ToolList.spec.ts
git commit -m "feat(web): 自定义工具注册/编辑抽屉(预览操作+创建+编辑回填头名留空)"
```

---

### Task 5：终审回归 + 端到端冒烟验收

**Files:** 无代码改动（除非发现缺陷）。产出：`docs/self-check.md` 追加本轮自检。

**Interfaces:** 无。

- [ ] **Step 1：后端全量测试**

Run: `cd server && mvn clean test`
Expected: BUILD 全绿（含 Modularity / LayerRules，tool 仍无 provider 依赖）。记录总数（应在 T3a 的 660 基础上 +新增用例）。

- [ ] **Step 2：前端全量测试**

Run: `cd web && pnpm vitest run`
Expected: 全绿。

- [ ] **Step 3：前端构建（类型检查）**

Run: `cd web && pnpm build`
Expected: 构建成功、无 TS 报错。

- [ ] **Step 4：起服务（重打包换进程）**

Run: 按项目既有方式重打包并重启 server + 前端 dev/nginx（见 memory `retrieval-threshold-tuned`：重启=重打包+换进程）。

- [ ] **Step 5：端到端冒烟（手动，用公网 OpenAPI）**

1. admin 登录 → 侧边「管理控制台 / 自定义工具」→ 页面加载，列表含内置工具（只读）。
2. 「注册工具」→ 粘贴一个**公网** OpenAPI 文档（禁内网/元数据地址；如某公网天气/翻译 API）→「预览操作」看到操作列表 → 填鉴权头值 → 保存 → 列表出现该工具。
3. 「编辑」该工具 → 名称/描述/spec 回填、鉴权头名可见、**值框空且占位「留空=不改」**；改个描述、头值不填 → 保存 → 不报错。
4. 详情/编辑抽屉里**不出现任何明文头值**（只有头名）。
5. 到某 Agent 应用配置页勾选该工具 → 试聊触发调用 → 看 **tool_call 轨迹卡片**（复用 T2）。
6. 「停用」→ 状态变停用；Agent 侧不再可选。「删除」→ 二次确认后消失。

- [ ] **Step 6：写自检入档（docs/self-check.md 追加一节）**

记录：改了什么、后端/前端测试数与结果、端到端冒烟每步结论、遇到的坑。参照既有 self-check 体例。

- [ ] **Step 7：提交自检**

```bash
git add docs/self-check.md
git commit -m "docs(self-check): T3b OpenAPI 自定义工具 admin 页 自检入档"
```

---

## Self-Review

**Spec 覆盖核对：**
- §2.1 预览接口 → Task 1 ✅
- §2.2 update 留空=不改（含去 @NotBlank、create 空值拦、方案 A）→ Task 2 ✅
- §3.1 路由与菜单 → Task 3 Step 6 ✅
- §3.2 ToolList 表格（类型标签/操作数—/内置只读）→ Task 3 ✅
- §3.3 注册/编辑抽屉（spec 文本框/动态鉴权头/预览按钮/编辑回填头名留空）→ Task 4 ✅
- §3.4 api 层 7+1 函数 → Task 3 Step 4 ✅
- §3.5 类型 → Task 3 Step 3 ✅
- §4 错误处理（13001/10006 走拦截器 toast、预览失败不关抽屉）→ Task 4 用例 ✅
- §5 测试（前端 5 例 + 后端 preview/update 各例）→ Task 1/2/3/4 ✅
- §6 验收端到端 → Task 5 ✅

**占位符扫描：** 无 TBD/TODO；每步含实际代码或命令。

**类型一致性：** `ToolAdminItem`/`ToolAdminDetail`/`ToolForm`/`ToolPreview`/`AuthHeaderInput`/`ToolOperation` 在 Task 3 定义，Task 4 一致引用；api 函数名 `listTools/getTool/createTool/updateTool/removeTool/enableTool/disableTool/previewTool` 全程一致；后端 `buildSpecForCreate`/`buildSpecForUpdate` 命名一致，`preview`/`ToolPreviewResponse`/`PreviewToolRequest` 一致。

**已知风险（执行时留意）：**
- happy-dom 下 el-input/textarea 取值差异 → Task 4 Step 4 已给兜底（findComponent / 以 api 调用参数为核心断言）。
- el-drawer teleport → 测试用透传桩绕开（Task 4 Step 1）。
- 后端判定务必 `mvn clean test` 全量（swagger classpath 坑）。
