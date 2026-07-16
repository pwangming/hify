# T4b：MCP 内网白名单 + MCP admin 注册页 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 admin 能在 `/admin/tool` 页注册/管理 MCP 服务器（含自建内网 MCP——经 yml 白名单放行），T4a 后端契约原样消费、零后端接口改动。

**Architecture:** 后端只加一个配置驱动的白名单判定（`McpClientFactory` 命中即跳过 `SsrfValidator`，仅 MCP 出站生效）；前端把 T3b 的注册抽屉拆成独立组件后加 MCP 分支（类型切换/试连接/编辑态快照），列表加 MCP 标签与刷新按钮。spec：`docs/superpowers/specs/2026-07-15-agent-tool-t4b-mcp-admin-ui-design.md`。

**Tech Stack:** Spring Boot 3（JUnit5+AssertJ+Spring `Binder`）；Vue 3 `<script setup>` + TS + Element Plus 2.9 + vitest 4（happy-dom）。

## Global Constraints

- **TDD**：新代码先写失败测试；每个 Task 观察一次红（类型/签名迁移期编译不过是固有性质，红只在迁移开始前看一次）。
- **后端判定**：在 `server/` 跑 `mvn clean test`，**读末尾 summary 数字**（Tests run / Failures / Errors / Skipped），**严禁 `grep BUILD SUCCESS` 判绿**（`-q` 会静音）。中途可用 `mvn test -Dtest=类名` 快跑单类。
- **前端判定**：在 `web/` 跑 `pnpm vitest run`（全量）；最终回归再跑 `pnpm build`（vue-tsc 类型检查在其中）。
- **只动本计划 Files 列出的文件**；发现必须动计划外文件时立即停下报告，不写兼容层/垫片。
- **零新依赖**（不引 dayjs，时间展示用 `new Date(x).toLocaleString()`）；零 Flyway 迁移；零新错误码；零新后端 API。
- 前端规范硬点：Element Plus 组件优先；SFC 顺序 `<script setup>` → `<template>` → `<style scoped lang="scss">`；scss 间距用 `$spacing-*` 变量；`el-radio` 用 `value` prop（EP 2.9 写法，仓库既有 `ProviderDetail.vue:264` 同款）；交互元素带 `data-test`。
- **openapi 的创建/更新请求 body 不带 `type` 字段**——与 T3b 已上线前端字节级一致，顺带回归后端「type 缺省=openapi」兼容路径。
- id / Long 一律 string（api-standards §4）。
- 每 Task 一个 commit，提交信息见各 Task 末步。

## File Structure（全轮波及面，已全量 grep 枚举、无截断）

**后端（Task 1）**
- 改 `server/src/main/java/com/hify/tool/config/McpProperties.java` —— 加 `allowedPrivateHosts`
- 改 `server/src/main/java/com/hify/tool/service/mcp/McpClientFactory.java` —— 白名单跳过判定
- 改 `server/src/test/java/com/hify/tool/service/mcp/McpClientFactoryTest.java` —— +2 测试
- 建 `server/src/test/java/com/hify/tool/config/McpPropertiesTest.java` —— 绑定契约 2 测试
- 改 `server/src/main/resources/application.yml` —— mcp 块加配置
- 改 `deploy/.env.example`、`docs/architecture/deployment.md`、`CLAUDE.md`、
  `server/src/main/java/com/hify/infra/outbound/SsrfValidator.java`（仅类注释）—— 拍板入档

**前端（Task 2-5）**
- 建 `web/src/views/admin/tool/components/ToolDrawer.vue` —— 注册/编辑抽屉（从 ToolList 拆出；T3b 后 ToolList 已 318 行，加 MCP 必超 frontend-standards §5.5 的 ~300 行线，先拆再加）
- 改 `web/src/views/admin/tool/ToolList.vue` —— 瘦身成列表 + 刷新按钮 + 三类标签
- 改 `web/src/types/tool.ts` —— ToolForm/ToolAdminDetail/ToolPreview 扩展 + McpToolItem/ToolUpsertBody/ToolPreviewBody
- 改 `web/src/api/admin/tool.ts` —— previewTool 签名改对象、createTool/updateTool 收 ToolUpsertBody、新增 refreshTool
- 改 `web/src/views/admin/tool/__tests__/ToolList.spec.ts` —— 既有断言适配 + 新增 MCP 用例

`previewTool` 签名改动的**全部**调用点（`grep -rn "previewTool" web/src`，无 head 截断）：
`ToolList.vue:121`（Task 2 后移入 `ToolDrawer.vue`）、`ToolList.spec.ts:5,17,109,123,134`。
`ToolForm/ToolPreview/ToolAdminDetail` 类型的**全部**消费方：`types/tool.ts`、`api/admin/tool.ts`、
`ToolList.vue`（Task 2 后为 `ToolDrawer.vue`）、`ToolList.spec.ts`。以上全在本计划 Files 内。

---

### Task 1: 后端 MCP 内网白名单（配置 + 判定 + 测试 + 拍板入档）

**Files:**
- Modify: `server/src/main/java/com/hify/tool/config/McpProperties.java`
- Modify: `server/src/main/java/com/hify/tool/service/mcp/McpClientFactory.java`
- Modify: `server/src/main/resources/application.yml`（`hify.tool.mcp` 块，约 141-149 行）
- Modify: `deploy/.env.example`
- Modify: `docs/architecture/deployment.md`（§5 两处）
- Modify: `server/src/main/java/com/hify/infra/outbound/SsrfValidator.java`（仅类注释第 17 行）
- Modify: `CLAUDE.md`（「部署与运维要点」安全行）
- Test: `server/src/test/java/com/hify/tool/service/mcp/McpClientFactoryTest.java`
- Test: Create `server/src/test/java/com/hify/tool/config/McpPropertiesTest.java`

**Interfaces:**
- Consumes: 既有 `McpProperties`（3 个超时字段）、`McpClientFactory.create(url, transport, headers)`、`SsrfValidator.validate(host)`。
- Produces: `McpProperties.getAllowedPrivateHosts(): List<String>` / `setAllowedPrivateHosts(List<String>)`；`McpClientFactory` 对白名单内 host 不再抛 10001。后续 Task 不依赖本 Task 的代码符号（前后端经 HTTP 契约解耦）。

- [x] **Step 1: 写失败测试——McpClientFactoryTest 加 2 个用例**

在 `McpClientFactoryTest` 现有 4 个测试后追加（import 区补 `import java.util.List;`）：

```java
    /** T4b 白名单：命中（忽略大小写）跳过 SSRF 禁内网；create 只造对象不连网，localhost 放行即成功。 */
    @Test
    void create_allowsWhitelistedPrivateHost_caseInsensitive() {
        McpProperties props = new McpProperties();
        props.setAllowedPrivateHosts(List.of("LocalHost"));
        McpClientFactory f = new McpClientFactory(new SsrfValidator(), props);
        try (McpSyncClient c = f.create("http://localhost:9999/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of())) {
            assertThat(c).isNotNull();
        }
    }

    /** T4b 白名单：名单里有别的条目，未命中的内网 host 照旧被拒 10001。 */
    @Test
    void create_rejectsInternalHostNotInWhitelist() {
        McpProperties props = new McpProperties();
        props.setAllowedPrivateHosts(List.of("host.docker.internal"));
        McpClientFactory f = new McpClientFactory(new SsrfValidator(), props);
        assertThatThrownBy(() -> f.create("http://127.0.0.1:8080/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode().code())
                        .isEqualTo(CommonError.PARAM_INVALID.code()));
    }
```

- [x] **Step 2: 写失败测试——新建 McpPropertiesTest（绑定契约）**

```java
package com.hify.tool.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守 allowed-private-hosts 的绑定契约：application.yml 写的是
 * ${HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS:}，env 未配时值为空串——必须绑成空列表
 * （行为与 T4a 完全一致），逗号分隔绑成多条目。
 */
class McpPropertiesTest {

    private McpProperties bind(String value) {
        var source = new MapConfigurationPropertySource(
                Map.of("hify.tool.mcp.allowed-private-hosts", value));
        return new Binder(source)
                .bind("hify.tool.mcp", Bindable.ofInstance(new McpProperties())).get();
    }

    @Test
    void emptyString_bindsToEmptyList() {
        assertThat(bind("").getAllowedPrivateHosts()).isEmpty();
    }

    @Test
    void commaSeparated_bindsToEntries() {
        assertThat(bind("host.docker.internal,192.168.1.10").getAllowedPrivateHosts())
                .containsExactly("host.docker.internal", "192.168.1.10");
    }
}
```

- [x] **Step 3: 观察红（编译失败即本 Task 的红）**

Run: `cd server && mvn test -Dtest=McpPropertiesTest,McpClientFactoryTest`
Expected: **COMPILATION ERROR**——`setAllowedPrivateHosts`/`getAllowedPrivateHosts` 符号不存在。这是迁移前唯一一次观察红；Step 4-5 期间不要再跑测试。

- [x] **Step 4: 实现 McpProperties 新字段**

在 `initializationTimeoutMs` 字段后加（import 区补 `import java.util.List;`）：

```java
    /**
     * MCP 内网白名单（T4b 决策 1/2）：命中的 host 跳过 SSRF 禁内网校验。
     * 精确 host 匹配、忽略大小写（localhost 与 127.0.0.1 是两个条目）；仅 MCP 出站生效——
     * HTTP 节点/内置 HTTP 工具的 URL 非 admin 受控（成员可填、模型可选），维持无差别禁内网。
     */
    private List<String> allowedPrivateHosts = List.of();
```

getter/setter（放在现有 getter/setter 之后，同款单行风格）：

```java
    public List<String> getAllowedPrivateHosts() { return allowedPrivateHosts; }
    public void setAllowedPrivateHosts(List<String> allowedPrivateHosts) {
        this.allowedPrivateHosts = allowedPrivateHosts;
    }
```

- [x] **Step 5: 实现 McpClientFactory 白名单判定**

`validate(String url)` 方法里，把

```java
        ssrfValidator.validate(uri.getHost());   // 内网/回环/元数据 → BizException(10001)，原样抛出
```

改为

```java
        if (!isAllowedPrivateHost(uri.getHost())) {
            ssrfValidator.validate(uri.getHost());   // 内网/回环/元数据 → BizException(10001)，原样抛出
        }
```

并在类末尾（`endpoint` 方法后）加：

```java
    /** T4b 白名单：只豁免禁内网这一条；followRedirects(NEVER) 与三重超时不豁免。 */
    private boolean isAllowedPrivateHost(String host) {
        return props.getAllowedPrivateHosts().stream().anyMatch(h -> h.equalsIgnoreCase(host));
    }
```

类 javadoc 第一行 `造 McpSyncClient：SSRF 校验 → 选传输 → …` 改为
`造 McpSyncClient：SSRF 校验（白名单 host 豁免禁内网，见 McpProperties.allowedPrivateHosts）→ 选传输 → …`。

- [x] **Step 6: application.yml 加配置项**

`hify.tool.mcp` 块末尾（`initialization-timeout-ms` 行后）追加：

```yaml
      # MCP 内网白名单（T4b spec 决策 1/2）：命中的 host 跳过 SSRF 禁内网校验，仅 MCP 出站生效。
      # 精确 host 匹配（忽略大小写），逗号分隔；localhost 与 127.0.0.1 是两个条目。
      # 例：HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS=host.docker.internal,192.168.1.10
      allowed-private-hosts: ${HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS:}
```

- [x] **Step 7: 跑测试转绿**

Run: `cd server && mvn test -Dtest=McpPropertiesTest,McpClientFactoryTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`（4 旧 + 2 新 factory + 2 新 properties）。

- [x] **Step 8: 拍板入档——4 处文档同步**

① `deploy/.env.example` 末尾追加：

```
# --- MCP 内网白名单（T4b）：自建 MCP 服务器的 host，逗号分隔；不配=维持全部禁内网 ---
# HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS=host.docker.internal,192.168.1.10
```

② `docs/architecture/deployment.md` §5，把 SSRF 那条 bullet 里的

```
  3xx 重定向跟随后对新地址重新校验。确需访问内网地址时由 admin 在系统设置中加显式白名单。
  一期拍板（2026-07-11，W3b spec）：3xx 一律**不跟随**（status+Location 原样返回节点输出，
  彻底封死重定向绕过）；内网白名单**暂缓**（一期只调公网，机制预留：SsrfValidator 查
  system_setting 放行）；不做 DNS pinning（校验与连接间的 rebinding 窗口以一期威胁模型
  评估可接受，二期对外开放时收紧）。
```

改为

```
  3xx 重定向跟随后对新地址重新校验。
  一期拍板（2026-07-11，W3b spec）：3xx 一律**不跟随**（status+Location 原样返回节点输出，
  彻底封死重定向绕过）；不做 DNS pinning（校验与连接间的 rebinding 窗口以一期威胁模型
  评估可接受，二期对外开放时收紧）。
  内网白名单（2026-07-15，T4b spec 重议）：**仅对 MCP 出站生效**——
  `hify.tool.mcp.allowed-private-hosts`（yml/.env，精确 host、忽略大小写），命中即跳过禁内网
  校验，重定向禁令与超时不豁免；运维改配置+重启生效。依据「URL 由谁控制」的威胁模型：
  MCP 地址仅 admin 注册（受信）；HTTP 节点 URL 任何成员可填、内置 HTTP 工具的 URL 由模型
  决定（提示注入可操纵）——后两类维持无差别禁内网。原「查 system_setting」预留因模块边界
  （SsrfValidator 在 infra 只依赖 common，system_setting 属 provider）放弃，改在 tool 模块配置收口。
```

同一节 MCP bullet 末句

```
  外化于 `hify.tool.mcp.*`。MCP 服务器地址由 admin 注册，同样仅限公网可达。
```

改为

```
  外化于 `hify.tool.mcp.*`。MCP 服务器地址由 admin 注册；自建内网服务器经上条白名单放行。
```

③ `SsrfValidator.java` 类注释第 17 行

```java
 * 内网白名单为推迟项（spec §1），真有需求时在此查 system_setting 放行。
```

改为

```java
 * 内网白名单已落地于 tool 模块的 MCP 出站（hify.tool.mcp.allowed-private-hosts，T4b 决策 2）；
 * 本类保持无差别禁内网、不读任何配置——HTTP 节点/内置 HTTP 工具的 URL 非 admin 受控，无白名单。
```

④ `CLAUDE.md`「部署与运维要点」安全行，把
`HTTP 节点/自定义工具/MCP 出站统一过 SSRF 防护（禁内网与元数据地址，见 deployment.md 第 5 节）`
改为
`HTTP 节点/自定义工具/MCP 出站统一过 SSRF 防护（禁内网与元数据地址；仅 MCP 可经 yml 白名单放行自建服务器，见 deployment.md 第 5 节）`。

- [ ] **Step 9: Commit**

```bash
git add server/src/main/java/com/hify/tool/config/McpProperties.java \
        server/src/main/java/com/hify/tool/service/mcp/McpClientFactory.java \
        server/src/main/resources/application.yml \
        server/src/test/java/com/hify/tool/config/McpPropertiesTest.java \
        server/src/test/java/com/hify/tool/service/mcp/McpClientFactoryTest.java \
        server/src/main/java/com/hify/infra/outbound/SsrfValidator.java \
        deploy/.env.example docs/architecture/deployment.md CLAUDE.md
git commit -m "feat(tool): MCP 内网白名单 allowed-private-hosts(仅MCP出站·精确host·忽略大小写)+拍板入档"
```

---

### Task 2: 抽屉拆分重构——ToolDrawer.vue（行为不变，既有测试全绿即验收）

纯重构：T3b 后 `ToolList.vue` 已 318 行，Task 4 加 MCP 必超 frontend-standards §5.5 的
~300 行线。本 Task **不写新测试也不改测试**——既有 `ToolList.spec.ts` 12 处断言是安全网，
重构后必须原样全绿（测试经 ToolList 挂载整树驱动，`el-drawer` 桩对子组件同样生效）。

**Files:**
- Create: `web/src/views/admin/tool/components/ToolDrawer.vue`
- Modify: `web/src/views/admin/tool/ToolList.vue`（全文重写，见 Step 2）
- Test: `web/src/views/admin/tool/__tests__/ToolList.spec.ts`（**不改**，跑绿即可）

**Interfaces:**
- Consumes: `@/api/admin/tool` 的 `getTool/createTool/updateTool/previewTool`（签名此刻未变）；`@/types/tool` 的 `ToolForm/ToolAdminItem/ToolOperation`。
- Produces: `ToolDrawer` 组件契约——`v-model:boolean`（开关）、prop `editing: ToolAdminItem | null`（null=新建）、事件 `saved`（保存成功后触发，父组件重载列表）。Task 3/4 在此组件内改造。

- [x] **Step 1: 新建 `components/ToolDrawer.vue`**（表单逻辑自 ToolList 原样搬入，类名前缀 `tool-list__` → `tool-drawer__`）

```vue
<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getTool, createTool, updateTool, previewTool } from '@/api/admin/tool'
import type { ToolAdminItem, ToolForm, ToolOperation } from '@/types/tool'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ editing: ToolAdminItem | null }>()
const emit = defineEmits<{ (e: 'saved'): void }>()

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

// 打开抽屉时按 editing 初始化：null=新建（清空表单），非 null=编辑（拉详情回填；失败关抽屉）
watch(visible, async (v) => {
  if (!v) return
  resetForm()
  if (props.editing === null) return
  try {
    const detail = await getTool(props.editing.id)
    form.name = detail.name
    form.description = detail.description
    form.specText = detail.rawSpec ?? ''
    form.authHeaders = detail.authHeaderNames.map((name) => ({ name, value: '' }))
    previewOps.value = detail.operations
  } catch {
    visible.value = false
  }
})

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
    if (props.editing === null) {
      await createTool({ ...form, authHeaders: [...form.authHeaders] })
      ElMessage.success('工具已注册')
    } else {
      await updateTool(props.editing.id, { ...form, authHeaders: [...form.authHeaders] })
      ElMessage.success('工具已更新')
    }
    visible.value = false
    emit('saved')
  } catch {
    /* 失败(重名/解析)由拦截器 toast；抽屉保持打开让用户改 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-drawer
    v-model="visible"
    data-test="tool-drawer"
    :title="editing === null ? '注册工具' : '编辑工具'"
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
        <div class="tool-drawer__headers">
          <div v-for="(h, i) in form.authHeaders" :key="i" class="tool-drawer__header-row">
            <el-input v-model="h.name" placeholder="头名，如 X-API-Key" :data-test="`header-name-${i}`" />
            <el-input
              v-model="h.value"
              :placeholder="editing === null ? '头值' : '留空=不改'"
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
        <ul class="tool-drawer__ops-preview">
          <li v-for="op in previewOps" :key="op.opName" :data-test="`operation-${op.opName}`">
            <strong>{{ op.opName }}</strong>
            <el-tag size="small">{{ op.method }}</el-tag>
            <code>{{ op.pathTemplate }}</code>
            <span class="tool-drawer__op-desc">{{ op.description }}</span>
          </li>
        </ul>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" data-test="form-submit" :loading="submitting" @click="submitForm">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped lang="scss">
.tool-drawer__headers {
  width: 100%;
}

.tool-drawer__header-row {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  margin-bottom: $spacing-sm;
}

.tool-drawer__ops-preview {
  margin: 0;
  padding-left: 1.2em;

  li {
    line-height: 1.9;
  }

  .tool-drawer__op-desc {
    color: var(--el-text-color-secondary);
    margin-left: 6px;
  }
}
</style>
```

- [x] **Step 2: 重写 `ToolList.vue`**（列表逻辑保留，抽屉换成组件引用）

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listTools, removeTool, enableTool, disableTool } from '@/api/admin/tool'
import type { ToolAdminItem } from '@/types/tool'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'
import ToolDrawer from './components/ToolDrawer.vue'

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

// —— 注册 / 编辑抽屉（表单逻辑在 ToolDrawer 内）——
const drawerVisible = ref(false)
const editingRow = ref<ToolAdminItem | null>(null)

function openCreate() {
  editingRow.value = null
  drawerVisible.value = true
}

function openEdit(row: ToolAdminItem) {
  editingRow.value = row
  drawerVisible.value = true
}
</script>

<template>
  <div class="tool-list">
    <PageHeader title="自定义工具" description="注册 OpenAPI 自定义工具，供 Agent 应用调用">
      <el-button type="primary" data-test="create-open" @click="openCreate">注册工具</el-button>
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
            <div v-if="!isBuiltin(row as ToolAdminItem)" class="tool-list__ops">
              <el-button
                v-if="(row as ToolAdminItem).enabled"
                :data-test="`disable-${(row as ToolAdminItem).id}`"
                size="small"
                @click="onDisable(row as ToolAdminItem)"
              >
                停用
              </el-button>
              <el-button
                v-else
                :data-test="`enable-${(row as ToolAdminItem).id}`"
                size="small"
                type="success"
                @click="onEnable(row as ToolAdminItem)"
              >
                启用
              </el-button>
              <el-button :data-test="`edit-${(row as ToolAdminItem).id}`" size="small" @click="openEdit(row as ToolAdminItem)">
                编辑
              </el-button>
              <el-button
                :data-test="`delete-${(row as ToolAdminItem).id}`"
                size="small"
                type="danger"
                @click="onDelete(row as ToolAdminItem)"
              >
                删除
              </el-button>
            </div>
            <span v-else class="tool-list__builtin-hint">内置工具（只读）</span>
          </template>
        </el-table-column>
      </el-table>
    </ContentCard>

    <ToolDrawer v-model="drawerVisible" :editing="editingRow" @saved="load" />
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
  font-size: 13px;
}
</style>
```

- [x] **Step 3: 跑既有测试验证重构无回归**

Run: `cd web && pnpm vitest run`
Expected: 全绿（389 个，数量不变——本 Task 零测试改动）。若 `ToolList.spec.ts` 任何用例转红，是重构破坏了行为，修实现、**不许改测试**。

- [x] **Step 4: Commit**

```bash
git add web/src/views/admin/tool/components/ToolDrawer.vue web/src/views/admin/tool/ToolList.vue
git commit -m "refactor(web): ToolList 抽屉拆出 ToolDrawer 组件(行为不变,为 T4b MCP 表单腾出 ~300 行空间)"
```

---

### Task 3: 类型与 api 层扩展 + previewTool 签名切换（编译原子单元，一个 Task 内完成）

**Files:**
- Modify: `web/src/types/tool.ts`
- Modify: `web/src/api/admin/tool.ts`
- Modify: `web/src/views/admin/tool/components/ToolDrawer.vue`（previewTool 调用点 + form 初始化 + buildBody）
- Test: `web/src/views/admin/tool/__tests__/ToolList.spec.ts`（previewTool 断言 + 字面量补新必填字段）

**Interfaces:**
- Consumes: Task 2 的 `ToolDrawer` 组件结构。
- Produces（Task 4/5 依赖的精确符号）：
  - `McpToolItem { toolName: string; description: string }`
  - `ToolForm` 增 `type: 'openapi' | 'mcp'`、`url: string`、`transport: string`
  - `ToolUpsertBody { name; description; type?: 'mcp'; specText?; url?; transport?; authHeaders }`
  - `ToolPreviewBody { type?: 'mcp'; specText?; url?; transport?; authHeaders? }`
  - `ToolAdminDetail` 增 `url: string | null; transport: string | null; tools: McpToolItem[]; discoveredAt: string | null`
  - `ToolPreview` 变 `{ baseUrl: string | null; operations: ToolOperation[]; tools: McpToolItem[] }`
  - `previewTool(body: ToolPreviewBody): Promise<ToolPreview>`；`createTool(body: ToolUpsertBody)`；`updateTool(id: string, body: ToolUpsertBody)`；`refreshTool(id: string): Promise<ToolAdminItem>`
  - `ToolDrawer` 内 `buildBody(): ToolUpsertBody`（openapi 分支**不带 type 字段**）

- [x] **Step 1: 改测试（这是本 Task 的失败测试）**

`ToolList.spec.ts` 四处：

① 「注册」用例（约 123 行）断言改为对象：

```ts
    expect(previewTool).toHaveBeenCalledWith({ specText: 'openapi: 3.0.0' })
```

② 同一用例末尾追加（守「openapi 提交 body 不带 type/url」——T3b 字节级兼容）：

```ts
    const body = vi.mocked(createTool).mock.calls[0][0]
    expect(body).not.toHaveProperty('type')
    expect(body).not.toHaveProperty('url')
```

③ 「注册」用例的 `preview` 字面量（约 105 行）补必填字段：

```ts
    const preview: ToolPreview = {
      baseUrl: 'https://api.example.com',
      operations: [{ opName: 'getPet', method: 'GET', pathTemplate: '/pets/{id}', description: '查' }],
      tools: [],
    }
```

④ 「编辑」用例的 `detail` 字面量（约 145 行）补必填字段（`rawSpec` 行后）：

```ts
      url: null,
      transport: null,
      tools: [],
      discoveredAt: null,
```

- [x] **Step 2: 观察红**

Run: `cd web && pnpm vitest run src/views/admin/tool`
Expected: FAIL——`previewTool` 实际被调时收到字符串 `'openapi: 3.0.0'`，断言期望对象。（vitest 不做类型检查，字面量补字段不报错，红来自行为断言。）

- [x] **Step 3: 改 `types/tool.ts`**（`ToolAdminDetail`/`ToolForm`/`ToolPreview` 三个接口整体替换，`ToolPreview` 前插入两个新类型）

```ts
/** admin 详情（对齐后端 ToolAdminDetailResponse）。openapi 行填 baseUrl/operations/rawSpec；mcp 行填 url/transport/tools/discoveredAt；另一边为 null / []。 */
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
  url: string | null
  transport: string | null
  tools: McpToolItem[]
  discoveredAt: string | null
}

/** MCP 工具摘要（对齐后端 McpToolView）。 */
export interface McpToolItem {
  toolName: string
  description: string
}

/** 注册/编辑表单状态（抽屉本地状态；提交 body 用 buildBody 构造，见 ToolUpsertBody）。 */
export interface ToolForm {
  name: string
  description: string
  type: 'openapi' | 'mcp'
  specText: string
  url: string
  transport: string
  authHeaders: AuthHeaderInput[]
}

/** 创建/更新请求 body（对齐后端 Create/UpdateToolRequest）。openapi 不传 type——与 T3b 上线请求字节级一致，顺带回归后端 type 缺省兼容路径。 */
export interface ToolUpsertBody {
  name: string
  description: string
  type?: 'mcp'
  specText?: string
  url?: string
  transport?: string
  authHeaders: AuthHeaderInput[]
}

/** 预览请求 body（对齐后端 PreviewToolRequest）。openapi 只传 specText；mcp 传 type/url/transport/authHeaders。 */
export interface ToolPreviewBody {
  type?: 'mcp'
  specText?: string
  url?: string
  transport?: string
  authHeaders?: AuthHeaderInput[]
}

/** 预览结果（对齐后端 ToolPreviewResponse）。openapi 回 baseUrl+operations；mcp 回 tools；未用到的那边为 null / []。 */
export interface ToolPreview {
  baseUrl: string | null
  operations: ToolOperation[]
  tools: McpToolItem[]
}
```

- [x] **Step 4: 改 `api/admin/tool.ts`**

import 行与三个函数签名改为：

```ts
import type { ToolAdminItem, ToolAdminDetail, ToolPreview, ToolPreviewBody, ToolUpsertBody } from '@/types/tool'
```

```ts
/** 注册自定义工具（openapi/mcp）。后端：POST .../ */
export function createTool(body: ToolUpsertBody) {
  return request.post<ToolAdminItem>(BASE, body)
}

/** 全量更新（头值留空=不改；mcp 行须带 type:'mcp'）。后端：PUT .../{id} */
export function updateTool(id: string, body: ToolUpsertBody) {
  return request.put<ToolAdminItem>(`${BASE}/${id}`, body)
}

/** 预览：openapi 解析文档 / mcp 试连接并列工具，均不落库。后端：POST .../preview */
export function previewTool(body: ToolPreviewBody) {
  return request.post<ToolPreview>(`${BASE}/preview`, body)
}
```

文件末尾新增：

```ts
/** 重新发现 MCP 工具清单（仅 mcp 行；凭据用库中密文）。后端：POST .../{id}/refresh */
export function refreshTool(id: string) {
  return request.post<ToolAdminItem>(`${BASE}/${id}/refresh`)
}
```

- [x] **Step 5: 改 `ToolDrawer.vue`（调用点适配，UI 不变）**

① import 补类型：`import type { ToolAdminItem, ToolForm, ToolOperation, ToolUpsertBody } from '@/types/tool'`

② form 初始化与 resetForm 补三个新字段：

```ts
const form = reactive<ToolForm>({
  name: '',
  description: '',
  type: 'openapi',
  specText: '',
  url: '',
  transport: 'streamable_http',
  authHeaders: [],
})
```

resetForm 内对应补：

```ts
  form.type = 'openapi'
  form.url = ''
  form.transport = 'streamable_http'
```

③ `onPreview` 内 `previewTool(form.specText)` → `previewTool({ specText: form.specText })`。

④ 新增 `buildBody`（放在 `removeHeader` 后），`submitForm` 的两处 `{ ...form, authHeaders: [...form.authHeaders] }` 都换成 `buildBody()`：

```ts
function buildBody(): ToolUpsertBody {
  const authHeaders = [...form.authHeaders]
  if (form.type === 'mcp') {
    return {
      name: form.name,
      description: form.description,
      type: 'mcp',
      url: form.url,
      transport: form.transport,
      authHeaders,
    }
  }
  // openapi 不传 type：与 T3b 上线请求字节级一致，顺带回归后端「type 缺省=openapi」兼容路径
  return { name: form.name, description: form.description, specText: form.specText, authHeaders }
}
```

- [x] **Step 6: 跑测试转绿**

Run: `cd web && pnpm vitest run`
Expected: 全绿（数量不变）。

- [x] **Step 7: Commit**

```bash
git add web/src/types/tool.ts web/src/api/admin/tool.ts \
        web/src/views/admin/tool/components/ToolDrawer.vue \
        web/src/views/admin/tool/__tests__/ToolList.spec.ts
git commit -m "feat(web): tool 类型/api 层扩 mcp 契约——previewTool 收对象、新增 refreshTool、buildBody 守 openapi 不传 type"
```

---

### Task 4: 抽屉 MCP 表单——类型切换 / 试连接 / 编辑态快照

**Files:**
- Modify: `web/src/views/admin/tool/components/ToolDrawer.vue`（Step 3 给出完整最终版）
- Test: `web/src/views/admin/tool/__tests__/ToolList.spec.ts`

**Interfaces:**
- Consumes: Task 3 的 `previewTool(body)` / `buildBody()` / `McpToolItem` / `ToolAdminDetail.tools/discoveredAt`。
- Produces: 抽屉的 `data-test` 锚点（Task 5 不依赖，但人工验收依赖）——`form-type`（radio 组，仅新建渲染）、`type-openapi`/`type-mcp`、`type-readonly`（编辑态只读标签）、`form-url`、`form-transport`、`transport-streamable`/`transport-sse`、`form-preview`（label 随类型变：预览操作/试连接）、`mcp-tool-{toolName}`、`discovered-at`。

- [x] **Step 1: 写失败测试**

① `SAMPLE` 数组追加第三行（mcp 行，Task 5 也用它）：

```ts
  {
    id: '12',
    name: 'deepwiki',
    description: 'DeepWiki MCP',
    source: 'mcp',
    enabled: true,
    operationCount: 3,
    ownerId: '1',
    createTime: '2026-07-15T10:00:00+08:00',
    updateTime: '2026-07-15T10:00:00+08:00',
  },
```

② 文件末尾新增 describe（radio 驱动方式与 `AppList.spec.ts:287` 同款——emit `update:modelValue`，绕开 happy-dom 下 label 点击不联动的问题）：

```ts
describe('ToolList MCP 注册/编辑', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listTools).mockResolvedValue(SAMPLE)
  })

  async function openCreateMcp() {
    const wrapper = mountListWithDrawer()
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    const typeGroup = wrapper.findComponent('[data-test="form-type"]')
    await typeGroup.vm.$emit('update:modelValue', 'mcp')
    await flushPromises()
    return wrapper
  }

  it('新建切到 MCP：出现 url/传输方式，OpenAPI 文本框与操作预览消失', async () => {
    const wrapper = await openCreateMcp()
    expect(wrapper.find('[data-test="form-url"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="form-transport"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="form-spec"]').exists()).toBe(false)
  })

  it('试连接：previewTool 收 mcp body，渲染发现的工具清单', async () => {
    vi.mocked(previewTool).mockResolvedValue({
      baseUrl: null,
      operations: [],
      tools: [{ toolName: 'read_wiki', description: '读 wiki 结构' }],
    })
    const wrapper = await openCreateMcp()
    await wrapper.get('[data-test="form-url"]').setValue('https://mcp.example.com/mcp')
    await wrapper.get('[data-test="form-preview"]').trigger('click')
    await flushPromises()
    expect(previewTool).toHaveBeenCalledWith({
      type: 'mcp',
      url: 'https://mcp.example.com/mcp',
      transport: 'streamable_http',
      authHeaders: [],
    })
    expect(wrapper.find('[data-test="mcp-tool-read_wiki"]').exists()).toBe(true)
  })

  it('mcp 提交：url 空被前端拦；有 url 时 body 带 type/url/transport 不带 specText', async () => {
    vi.mocked(createTool).mockResolvedValue(SAMPLE[2])
    const wrapper = await openCreateMcp()
    await wrapper.get('[data-test="form-name"]').setValue('deepwiki')
    await wrapper.get('[data-test="form-description"]').setValue('wiki 问答')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createTool).not.toHaveBeenCalled()

    await wrapper.get('[data-test="form-url"]').setValue('https://mcp.example.com/mcp')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createTool).toHaveBeenCalledWith({
      name: 'deepwiki',
      description: 'wiki 问答',
      type: 'mcp',
      url: 'https://mcp.example.com/mcp',
      transport: 'streamable_http',
      authHeaders: [],
    })
  })

  it('编辑 mcp 行：回填 url、类型只读、展示快照与 discoveredAt、无试连接按钮', async () => {
    const detail: ToolAdminDetail = {
      id: '12',
      name: 'deepwiki',
      description: 'DeepWiki MCP',
      source: 'mcp',
      enabled: true,
      baseUrl: null,
      operations: [],
      authHeaderNames: ['Authorization'],
      rawSpec: null,
      url: 'https://mcp.deepwiki.com/mcp',
      transport: 'streamable_http',
      tools: [{ toolName: 'read_wiki_structure', description: '读结构' }],
      discoveredAt: '2026-07-15T10:00:00+08:00',
    }
    vi.mocked(getTool).mockResolvedValue(detail)
    const wrapper = mountListWithDrawer()
    await flushPromises()
    await wrapper.get('[data-test="edit-12"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-test="form-url"]').element as HTMLInputElement).value).toBe(
      'https://mcp.deepwiki.com/mcp',
    )
    expect(wrapper.find('[data-test="form-type"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="type-readonly"]').text()).toBe('MCP')
    expect(wrapper.find('[data-test="mcp-tool-read_wiki_structure"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="discovered-at"]').text()).toContain('上次发现于')
    expect(wrapper.find('[data-test="form-preview"]').exists()).toBe(false)
  })
})
```

- [x] **Step 2: 观察红**

Run: `cd web && pnpm vitest run src/views/admin/tool`
Expected: 新 describe 4 个用例全 FAIL（`form-type` 等锚点不存在）；既有用例仍绿。

- [x] **Step 3: 实现——`ToolDrawer.vue` 完整最终版（整文件替换）**

要点：编辑态**不渲染** radio 组、用只读 `el-tag`（`ProviderDetail.vue` 同款先例，绕开 EP
radio 子项 disabled 覆盖组级的坑）；`mcpTools` 一个 ref 双用（新建=试连接结果，编辑=库中快照）；
试连接仅新建 mcp 显示（编辑时鉴权头值为空带不出旧凭据，试连必假失败——spec 决策 5）。

```vue
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getTool, createTool, updateTool, previewTool } from '@/api/admin/tool'
import type { McpToolItem, ToolAdminItem, ToolForm, ToolOperation, ToolUpsertBody } from '@/types/tool'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ editing: ToolAdminItem | null }>()
const emit = defineEmits<{ (e: 'saved'): void }>()

const isEdit = computed(() => props.editing !== null)
const submitting = ref(false)
const previewing = ref(false)
const previewOps = ref<ToolOperation[]>([])
// 新建 mcp = 试连接结果；编辑 mcp = 库中快照（getTool 回填）
const mcpTools = ref<McpToolItem[]>([])
const discoveredAt = ref<string | null>(null)
const form = reactive<ToolForm>({
  name: '',
  description: '',
  type: 'openapi',
  specText: '',
  url: '',
  transport: 'streamable_http',
  authHeaders: [],
})

// 试连接仅「新建」有意义：编辑时鉴权头值为空（明文永不回传），试连必假失败（spec 决策 5）；
// openapi 的预览是纯解析不连网，编辑态照常可用。
const showPreviewButton = computed(() => form.type === 'openapi' || !isEdit.value)
const previewLabel = computed(() => (form.type === 'mcp' ? '试连接' : '预览操作'))

function resetForm() {
  form.name = ''
  form.description = ''
  form.type = 'openapi'
  form.specText = ''
  form.url = ''
  form.transport = 'streamable_http'
  form.authHeaders = []
  previewOps.value = []
  mcpTools.value = []
  discoveredAt.value = null
}

// 打开抽屉时按 editing 初始化：null=新建（清空表单），非 null=编辑（拉详情回填；失败关抽屉）
watch(visible, async (v) => {
  if (!v) return
  resetForm()
  if (props.editing === null) return
  try {
    const detail = await getTool(props.editing.id)
    form.name = detail.name
    form.description = detail.description
    form.type = detail.source === 'mcp' ? 'mcp' : 'openapi'
    form.specText = detail.rawSpec ?? ''
    form.url = detail.url ?? ''
    form.transport = detail.transport ?? 'streamable_http'
    form.authHeaders = detail.authHeaderNames.map((name) => ({ name, value: '' }))
    previewOps.value = detail.operations
    mcpTools.value = detail.tools
    discoveredAt.value = detail.discoveredAt
  } catch {
    visible.value = false
  }
})

function addHeader() {
  form.authHeaders.push({ name: '', value: '' })
}

function removeHeader(i: number) {
  form.authHeaders.splice(i, 1)
}

function buildBody(): ToolUpsertBody {
  const authHeaders = [...form.authHeaders]
  if (form.type === 'mcp') {
    return {
      name: form.name,
      description: form.description,
      type: 'mcp',
      url: form.url,
      transport: form.transport,
      authHeaders,
    }
  }
  // openapi 不传 type：与 T3b 上线请求字节级一致，顺带回归后端「type 缺省=openapi」兼容路径
  return { name: form.name, description: form.description, specText: form.specText, authHeaders }
}

async function onPreview() {
  if (form.type === 'mcp') {
    if (!form.url.trim()) {
      ElMessage.warning('请输入服务器地址')
      return
    }
    previewing.value = true
    try {
      const result = await previewTool({
        type: 'mcp',
        url: form.url,
        transport: form.transport,
        authHeaders: [...form.authHeaders],
      })
      mcpTools.value = result.tools
      ElMessage.success(`连接成功，发现 ${result.tools.length} 个工具`)
    } catch {
      /* 连接失败(13002)/内网被拒(10001)由拦截器 toast；抽屉保持打开 */
    } finally {
      previewing.value = false
    }
    return
  }
  if (!form.specText.trim()) {
    ElMessage.warning('请先粘贴 OpenAPI 文档')
    return
  }
  previewing.value = true
  try {
    const result = await previewTool({ specText: form.specText })
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
  // 后端 @AssertTrue 报错字段名是 payloadValid、无法精准标红（T4a spec §5.2 已知局限），前端先拦一道
  if (form.type === 'openapi' && !form.specText.trim()) {
    ElMessage.warning('请粘贴 OpenAPI 文档')
    return
  }
  if (form.type === 'mcp' && !form.url.trim()) {
    ElMessage.warning('请输入服务器地址')
    return
  }
  submitting.value = true
  try {
    if (props.editing === null) {
      await createTool(buildBody())
      ElMessage.success('工具已注册')
    } else {
      await updateTool(props.editing.id, buildBody())
      ElMessage.success('工具已更新')
    }
    visible.value = false
    emit('saved')
  } catch {
    /* 失败(重名/解析/连接)由拦截器 toast；抽屉保持打开让用户改 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-drawer
    v-model="visible"
    data-test="tool-drawer"
    :title="editing === null ? '注册工具' : '编辑工具'"
    size="600px"
  >
    <el-form label-width="90px">
      <el-form-item label="类型">
        <el-radio-group v-if="!isEdit" v-model="form.type" data-test="form-type">
          <el-radio value="openapi" data-test="type-openapi">OpenAPI</el-radio>
          <el-radio value="mcp" data-test="type-mcp">MCP</el-radio>
        </el-radio-group>
        <el-tag v-else data-test="type-readonly">{{ form.type === 'mcp' ? 'MCP' : 'OpenAPI' }}</el-tag>
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="form.name" data-test="form-name" maxlength="64" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" data-test="form-description" maxlength="500" />
      </el-form-item>
      <el-form-item v-if="form.type === 'openapi'" label="OpenAPI">
        <el-input
          v-model="form.specText"
          data-test="form-spec"
          type="textarea"
          :rows="10"
          placeholder="粘贴 OpenAPI 3.0 文档（JSON 或 YAML）"
        />
      </el-form-item>
      <template v-else>
        <el-form-item label="服务器地址">
          <div class="tool-drawer__url">
            <el-input v-model="form.url" data-test="form-url" placeholder="https://mcp.example.com/mcp" />
            <p class="tool-drawer__hint">
              内网/localhost 地址默认被拒绝，需先在服务端配置
              <code>hify.tool.mcp.allowed-private-hosts</code> 白名单
            </p>
          </div>
        </el-form-item>
        <el-form-item label="传输方式">
          <el-radio-group v-model="form.transport" data-test="form-transport">
            <el-radio value="streamable_http" data-test="transport-streamable">Streamable HTTP（默认）</el-radio>
            <el-radio value="sse" data-test="transport-sse">SSE（兼容旧服务器）</el-radio>
          </el-radio-group>
        </el-form-item>
      </template>
      <el-form-item label="鉴权头">
        <div class="tool-drawer__headers">
          <div v-for="(h, i) in form.authHeaders" :key="i" class="tool-drawer__header-row">
            <el-input v-model="h.name" placeholder="头名，如 X-API-Key" :data-test="`header-name-${i}`" />
            <el-input
              v-model="h.value"
              :placeholder="editing === null ? '头值' : '留空=不改'"
              :data-test="`header-value-${i}`"
            />
            <el-button size="small" text type="danger" @click="removeHeader(i)">删除</el-button>
          </div>
          <el-button size="small" data-test="add-header" @click="addHeader">+ 添加请求头</el-button>
        </div>
      </el-form-item>
      <el-form-item v-if="showPreviewButton">
        <el-button data-test="form-preview" :loading="previewing" @click="onPreview">{{ previewLabel }}</el-button>
      </el-form-item>
      <el-form-item v-if="form.type === 'openapi' && previewOps.length" label="操作列表">
        <ul class="tool-drawer__ops-preview">
          <li v-for="op in previewOps" :key="op.opName" :data-test="`operation-${op.opName}`">
            <strong>{{ op.opName }}</strong>
            <el-tag size="small">{{ op.method }}</el-tag>
            <code>{{ op.pathTemplate }}</code>
            <span class="tool-drawer__op-desc">{{ op.description }}</span>
          </li>
        </ul>
      </el-form-item>
      <el-form-item v-if="form.type === 'mcp' && mcpTools.length" label="工具列表">
        <div class="tool-drawer__mcp-tools">
          <p v-if="discoveredAt" class="tool-drawer__hint" data-test="discovered-at">
            上次发现于 {{ new Date(discoveredAt).toLocaleString() }}
          </p>
          <ul class="tool-drawer__ops-preview">
            <li v-for="t in mcpTools" :key="t.toolName" :data-test="`mcp-tool-${t.toolName}`">
              <strong>{{ t.toolName }}</strong>
              <span class="tool-drawer__op-desc">{{ t.description }}</span>
            </li>
          </ul>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" data-test="form-submit" :loading="submitting" @click="submitForm">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped lang="scss">
.tool-drawer__headers,
.tool-drawer__url,
.tool-drawer__mcp-tools {
  width: 100%;
}

.tool-drawer__hint {
  margin: $spacing-xs 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.tool-drawer__header-row {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  margin-bottom: $spacing-sm;
}

.tool-drawer__ops-preview {
  margin: 0;
  padding-left: 1.2em;

  li {
    line-height: 1.9;
  }

  .tool-drawer__op-desc {
    color: var(--el-text-color-secondary);
    margin-left: 6px;
  }
}
</style>
```

（`$spacing-xs` 已存在于 `styles/variables.scss:76`，经全局注入直接可用。）

- [x] **Step 4: 跑测试转绿**

Run: `cd web && pnpm vitest run`
Expected: 全绿（+4 个新用例）。

- [ ] **Step 5: Commit**

```bash
git add web/src/views/admin/tool/components/ToolDrawer.vue \
        web/src/views/admin/tool/__tests__/ToolList.spec.ts
git commit -m "feat(web): 注册抽屉支持 MCP——类型切换/试连接/编辑态只读类型+快照+discoveredAt(T4b spec 决策5)"
```

---

### Task 5: 列表 MCP 支持（三类标签 / 列名 / 刷新按钮）+ 全量回归

**Files:**
- Modify: `web/src/views/admin/tool/ToolList.vue`
- Test: `web/src/views/admin/tool/__tests__/ToolList.spec.ts`

**Interfaces:**
- Consumes: Task 3 的 `refreshTool(id)`；Task 4 加进 `SAMPLE` 的 mcp 行（id '12'）。
- Produces: 列表 `data-test="refresh-{id}"` 按钮（仅 mcp 行）。本 Task 是最后功能 Task，末尾并入全量回归（防独立收尾 Task 被跳过——C3 教训）。

- [ ] **Step 1: 写失败测试**

① `vi.mock` 工厂补一行（`previewTool: vi.fn(),` 后）：

```ts
  refreshTool: vi.fn(),
```

② import 行补 `refreshTool`：

```ts
import { listTools, getTool, createTool, updateTool, previewTool, refreshTool } from '@/api/admin/tool'
```

③ 「ToolList 列表渲染」describe 内追加 3 个用例：

```ts
  it('mcp 行显示 MCP 标签；刷新按钮仅 mcp 行有', async () => {
    const wrapper = mountList()
    await flushPromises()
    expect(wrapper.text()).toContain('MCP')
    expect(wrapper.find('[data-test="refresh-12"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="refresh-9"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="refresh-1"]').exists()).toBe(false)
  })

  it('点击刷新调 refreshTool 并重载列表', async () => {
    vi.mocked(refreshTool).mockResolvedValue({ ...SAMPLE[2], operationCount: 4 })
    const wrapper = mountList()
    await flushPromises()
    await wrapper.get('[data-test="refresh-12"]').trigger('click')
    await flushPromises()
    expect(refreshTool).toHaveBeenCalledWith('12')
    expect(listTools).toHaveBeenCalledTimes(2)
  })

  it('操作数列头改为 操作/工具数', async () => {
    const wrapper = mountList()
    await flushPromises()
    expect(wrapper.text()).toContain('操作/工具数')
  })
```

- [ ] **Step 2: 观察红**

Run: `cd web && pnpm vitest run src/views/admin/tool`
Expected: 新 3 用例 FAIL（无 MCP 标签文案 / 无 refresh-12 / 列头仍是「操作数」）；其余全绿。

- [ ] **Step 3: 实现 `ToolList.vue`**

① import 行补 `refreshTool`：

```ts
import { listTools, removeTool, enableTool, disableTool, refreshTool } from '@/api/admin/tool'
```

② `isBuiltin` 后加标签辅助与刷新逻辑：

```ts
function sourceLabel(row: ToolAdminItem): string {
  if (row.source === 'builtin') return '内置'
  return row.source === 'mcp' ? 'MCP' : '自定义'
}

function sourceTagType(row: ToolAdminItem): 'info' | 'primary' | 'warning' {
  if (row.source === 'builtin') return 'info'
  return row.source === 'mcp' ? 'warning' : 'primary'
}

// 刷新=重新发现 MCP 工具清单（凭据用库中密文），非破坏性动作，无需二次确认
const refreshingId = ref<string | null>(null)

async function onRefresh(row: ToolAdminItem) {
  refreshingId.value = row.id
  try {
    const updated = await refreshTool(row.id)
    ElMessage.success(`已刷新，共 ${updated.operationCount ?? 0} 个工具`)
    await load()
  } catch {
    /* 13002 等由 request 拦截器统一 toast */
  } finally {
    refreshingId.value = null
  }
}
```

③ 模板「类型」列改用辅助函数：

```vue
        <el-table-column label="类型">
          <template #default="{ row }">
            <el-tag :type="sourceTagType(row as ToolAdminItem)">
              {{ sourceLabel(row as ToolAdminItem) }}
            </el-tag>
          </template>
        </el-table-column>
```

④ 「操作数」列改名：

```vue
        <el-table-column label="操作/工具数">
```

⑤ 操作列 `width="320"` 改 `width="380"`，并在「编辑」按钮前插入刷新按钮：

```vue
              <el-button
                v-if="(row as ToolAdminItem).source === 'mcp'"
                :data-test="`refresh-${(row as ToolAdminItem).id}`"
                size="small"
                :loading="refreshingId === (row as ToolAdminItem).id"
                @click="onRefresh(row as ToolAdminItem)"
              >
                刷新
              </el-button>
```

- [ ] **Step 4: 跑测试转绿**

Run: `cd web && pnpm vitest run`
Expected: 全绿（+3 个新用例）。

- [ ] **Step 5: 全量回归（三件套，读完整输出）**

```bash
cd server && mvn clean test
```
Expected: 末尾 summary `Tests run: 696, Failures: 0, Errors: 0, Skipped: 0`（T4a 时 692 + 本轮后端 4 新）+ BUILD SUCCESS。**读数字，不 grep**。

```bash
cd web && pnpm vitest run
```
Expected: 全绿（T3b 时 389 + 本轮 7 新 = 396）。

```bash
cd web && pnpm build
```
Expected: vue-tsc 无类型错误、构建成功——这一步同时守住 Task 3 中测试字面量与类型定义的一致性（vitest 运行期不查类型）。

- [ ] **Step 6: Commit**

```bash
git add web/src/views/admin/tool/ToolList.vue \
        web/src/views/admin/tool/__tests__/ToolList.spec.ts
git commit -m "feat(web): 工具列表 MCP 标签+刷新按钮+列名改 操作/工具数;全量回归通过(mvn 696/vitest 396/build)"
```

---

## 计划外事项（执行方无需做，留给终审/验收）

- self-check 入档与 memory 更新由终审方（Claude）在验收后执行。
- 人工验收两条线（spec §6）：公网 DeepWiki 走新 UI 全流程；本地/自建 MCP 验白名单
  （不配→10001 → 配 `HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS` → **重打包+换进程重启** → 注册成功）。
- 验收前必须重启服务（W2 教训：改后端不重启，验收对着旧进程）。
