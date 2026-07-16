<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getTool, createTool, updateTool, previewTool } from '@/api/admin/tool'
import { ApiError } from '@/api/request'
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

// 10001 的字段错误按拦截器约定不弹全局 toast（留给表单逐项标红）；本抽屉未做逐项标红，
// 必须兜底提示，否则校验失败在界面上是零反应（T4b 验收实测的「点保存无反应」）。
function toastFieldErrors(e: unknown) {
  if (e instanceof ApiError && e.fieldErrors?.length) {
    ElMessage.error(e.fieldErrors.map((f) => `${f.field}：${f.message}`).join('；'))
  }
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
  if (!form.description.trim()) {
    ElMessage.warning('请输入描述')
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
  } catch (e) {
    /* 失败(重名/解析/连接)由拦截器 toast；10001 字段错误在此兜底；抽屉保持打开让用户改 */
    toastFieldErrors(e)
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
