<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getTool, createTool, updateTool, previewTool } from '@/api/admin/tool'
import type { ToolAdminItem, ToolForm, ToolOperation, ToolUpsertBody } from '@/types/tool'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ editing: ToolAdminItem | null }>()
const emit = defineEmits<{ (e: 'saved'): void }>()

const submitting = ref(false)
const previewing = ref(false)
const previewOps = ref<ToolOperation[]>([])
const form = reactive<ToolForm>({
  name: '',
  description: '',
  type: 'openapi',
  specText: '',
  url: '',
  transport: 'streamable_http',
  authHeaders: [],
})

function resetForm() {
  form.name = ''
  form.description = ''
  form.type = 'openapi'
  form.specText = ''
  form.url = ''
  form.transport = 'streamable_http'
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
  if (!form.specText.trim()) {
    ElMessage.warning('请粘贴 OpenAPI 文档')
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
