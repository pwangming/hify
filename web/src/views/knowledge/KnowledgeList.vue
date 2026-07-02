<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { listDatasets, createDataset, updateDataset, deleteDataset } from '@/api/knowledge'
import type { Dataset, DatasetForm } from '@/types/knowledge'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const NAME_MAX = 50
const DESC_MAX = 200

const userStore = useUserStore()
const router = useRouter()

const datasets = ref<Dataset[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)

/** 团队共享制：仅 owner 或 Admin 可改/删（与后端 10004 双保险）。 */
function canModify(dataset: Dataset): boolean {
  return userStore.isAdmin || dataset.ownerId === userStore.user?.id
}

function openDetail(row: Dataset) {
  router.push(`/knowledge/${row.id}`)
}

async function load() {
  loading.value = true
  try {
    const res = await listDatasets({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    datasets.value = res.list
    total.value = Number(res.total) // 后端以 string 下发
  } finally {
    loading.value = false
  }
}
onMounted(load)

function onSearch() {
  page.value = 1
  load()
}
function onPageChange(p: number) {
  page.value = p
  load()
}

async function onDelete(row: Dataset) {
  try {
    await ElMessageBox.confirm(`确定删除知识库「${row.name}」？此操作不可恢复。`, '删除确认', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteDataset(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

// —— 创建 / 编辑弹窗（共用）——
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<DatasetForm>({ name: '', description: '' })

const rules: FormRules<DatasetForm> = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: NAME_MAX, message: `名称不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}
function openEdit(row: Dataset) {
  editingId.value = row.id
  form.name = row.name
  form.description = row.description ?? ''
  dialogVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 兜底：happy-dom 下 el-form.validate 对空必填会误判通过（同 UserList/ProviderList/AppList）。
  if (!form.name || form.name.length > NAME_MAX) return
  if (form.description.length > DESC_MAX) return
  try {
    if (editingId.value === null) {
      await createDataset({ ...form })
      ElMessage.success('知识库已创建')
    } else {
      await updateDataset(editingId.value, { ...form })
      ElMessage.success('知识库已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 失败（如重名）由 request 拦截器统一 toast；弹窗保持打开 */
  }
}
</script>

<template>
  <div class="knowledge-list">
    <PageHeader title="知识库" description="团队共享：全员可见，编辑/删除仅创建者与管理员">
      <el-input
        v-model="keyword"
        data-test="search"
        placeholder="搜索知识库名"
        clearable
        class="knowledge-list__search"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-button type="primary" data-test="create-open" @click="openCreate">新建知识库</el-button>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="datasets" data-test="dataset-table">
        <el-table-column label="名称">
          <template #default="{ row }">
            <el-link
              type="primary"
              :underline="false"
              :data-test="`open-${(row as Dataset).id}`"
              @click="openDetail(row as Dataset)"
              >{{ (row as Dataset).name }}</el-link
            >
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="归属">
          <template #default="{ row }">
            <el-tag :type="(row as Dataset).ownerId === userStore.user?.id ? 'success' : 'info'">
              {{ (row as Dataset).ownerId === userStore.user?.id ? '我创建' : '其他成员' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间">
          <template #default="{ row }">{{ formatDateTime((row as Dataset).createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <template v-if="canModify(row as Dataset)">
              <el-button
                :data-test="`edit-${(row as Dataset).id}`"
                size="small"
                @click="openEdit(row as Dataset)"
                >编辑</el-button
              >
              <el-button
                :data-test="`delete-${(row as Dataset).id}`"
                size="small"
                type="danger"
                @click="onDelete(row as Dataset)"
                >删除</el-button
              >
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="knowledge-list__pager"
        layout="prev, pager, next, total"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="onPageChange"
      />
    </ContentCard>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建知识库' : '编辑知识库'"
      width="520"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="70px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" data-test="form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            data-test="form-desc"
            type="textarea"
            :rows="3"
            maxlength="200"
            placeholder="这个知识库装什么内容（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" data-test="form-submit" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.knowledge-list__search {
  width: 220px;
}
.knowledge-list__pager {
  margin-top: $spacing-md;
  justify-content: flex-end;
}
</style>
