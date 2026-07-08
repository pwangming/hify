<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
import {
  getDataset, listDocuments, uploadDocument, deleteDocument, listChunks, retryDocument, retrieveTest,
} from '@/api/knowledge'
import type { Dataset, KbDocument, Chunk, DocumentStatus, RetrievedChunk } from '@/types/knowledge'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const MAX_SIZE = 50 * 1024 * 1024
const CHUNK_PAGE_SIZE = 10
const POLL_INTERVAL_MS = 3000
const NAME_MAX = 200

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const datasetId = String(route.params.id)

const dataset = ref<Dataset | null>(null)
const docs = ref<KbDocument[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const uploading = ref(false)
let pollTimer: number | null = null

/** 团队共享制：仅 owner 或 Admin 可上传/删除（与后端 10004 双保险）。 */
const canModify = computed(
  () => !!dataset.value && (userStore.isAdmin || dataset.value.ownerId === userStore.user?.id),
)

async function loadDataset() {
  dataset.value = await getDataset(datasetId)
}
async function loadDocs(background = false) {
  if (!background) loading.value = true
  try {
    const res = await listDocuments(datasetId, { page: page.value, size: size.value })
    docs.value = res.list
    total.value = Number(res.total)
  } finally {
    if (!background) loading.value = false
  }
  syncPolling()
}
onMounted(() => {
  loadDataset()
  loadDocs()
})
onUnmounted(stopPolling)

function syncPolling() {
  const active = docs.value.some((d) => d.status === 'pending' || d.status === 'processing')
  if (active && pollTimer === null) {
    pollTimer = window.setInterval(() => loadDocs(true), POLL_INTERVAL_MS)
  } else if (!active && pollTimer !== null) {
    stopPolling()
  }
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

function onPageChange(p: number) {
  page.value = p
  loadDocs()
}

// —— 上传（el-upload 自定义 http-request；前端先拦截扩展名/大小，双保险）——
function beforeUpload(file: File): boolean {
  if (file.name.length > NAME_MAX) {
    ElMessage.error(`文件名不能超过 ${NAME_MAX} 个字符`)
    return false
  }
  if (!/\.(txt|md)$/i.test(file.name)) {
    ElMessage.error('仅支持 txt / md 文件')
    return false
  }
  if (file.size > MAX_SIZE) {
    ElMessage.error('文件不能超过 50MB')
    return false
  }
  return true
}
async function doUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    await uploadDocument(datasetId, options.file as unknown as File)
    ElMessage.success('已上传，正在处理')
    await loadDocs()
  } catch {
    /* 失败（15004/15001 等）由 request 拦截器统一 toast */
  } finally {
    uploading.value = false
  }
}

async function onRetry(row: KbDocument) {
  try {
    await retryDocument(row.id)
    ElMessage.success('已重新开始处理')
    await loadDocs()
  } catch {
    /* 15002 等由 request 拦截器统一 toast */
  }
}

async function onDelete(row: KbDocument) {
  try {
    await ElMessageBox.confirm(
      `确定删除文档「${row.name}」？其全部分段将一并删除。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteDocument(row.id)
    ElMessage.success('已删除')
    await loadDocs()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

// —— 分段预览抽屉 ——
const drawerVisible = ref(false)
const chunkDoc = ref<KbDocument | null>(null)
const chunks = ref<Chunk[]>([])
const chunkTotal = ref(0)
const chunkPage = ref(1)

async function openChunks(row: KbDocument) {
  chunkDoc.value = row
  chunkPage.value = 1
  drawerVisible.value = true
  await loadChunks()
}
async function loadChunks() {
  if (!chunkDoc.value) return
  const res = await listChunks(chunkDoc.value.id, { page: chunkPage.value, size: CHUNK_PAGE_SIZE })
  chunks.value = res.list
  chunkTotal.value = Number(res.total)
}
function onChunkPageChange(p: number) {
  chunkPage.value = p
  loadChunks()
}

const STATUS_LABEL: Record<DocumentStatus, string> = {
  pending: '待处理', processing: '处理中', ready: '就绪', failed: '失败',
}
const STATUS_TAG: Record<DocumentStatus, 'info' | 'warning' | 'success' | 'danger'> = {
  pending: 'info', processing: 'warning', ready: 'success', failed: 'danger',
}

function formatFileSize(bytes: string): string {
  const n = Number(bytes)
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

// —— 命中测试弹窗（检索调试，不走 LLM；12006/12003 等错误由拦截器 toast，不吞）——
const retrieveDialogVisible = ref(false)
const retrieveQuery = ref('')
const retrieveTopK = ref<number | undefined>()
const retrieveThreshold = ref<number | undefined>()
const retrieving = ref(false)
const retrieveTried = ref(false)
const hits = ref<RetrievedChunk[]>([])

function openRetrieveTest() {
  retrieveDialogVisible.value = true
}

async function onRetrieveRun() {
  const q = retrieveQuery.value.trim()
  if (!q) {
    ElMessage.error('请输入测试问题')
    return
  }
  retrieving.value = true
  try {
    hits.value = await retrieveTest(datasetId, {
      query: q,
      topK: retrieveTopK.value || undefined,
      scoreThreshold: retrieveThreshold.value ?? undefined,
    })
    retrieveTried.value = true
  } catch {
    /* 未配 embedding 模型/供应商故障由拦截器 toast——排障工具要暴露真实错误 */
  } finally {
    retrieving.value = false
  }
}
</script>

<template>
  <div class="dataset-detail">
    <PageHeader :title="dataset?.name ?? '知识库'" :description="dataset?.description ?? ''">
      <el-button data-test="back" @click="router.push('/knowledge')">返回列表</el-button>
      <el-button data-test="retrieve-open" @click="openRetrieveTest">命中测试</el-button>
      <el-upload
        v-if="canModify"
        accept=".txt,.md"
        :show-file-list="false"
        :before-upload="beforeUpload"
        :http-request="doUpload"
      >
        <el-button type="primary" data-test="upload-open" :loading="uploading">上传文档</el-button>
      </el-upload>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="docs" data-test="doc-table">
        <el-table-column prop="name" label="名称" show-overflow-tooltip />
        <el-table-column label="格式" width="80">
          <template #default="{ row }">
            <el-tag>{{ (row as KbDocument).fileType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatFileSize((row as KbDocument).fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="分段数" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tooltip
              v-if="(row as KbDocument).status === 'failed' && (row as KbDocument).errorMessage"
              :content="(row as KbDocument).errorMessage ?? ''"
            >
              <el-tag type="danger">{{ STATUS_LABEL.failed }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="STATUS_TAG[(row as KbDocument).status]"
                    :data-test="`doc-status-${(row as KbDocument).id}`">
              {{ STATUS_LABEL[(row as KbDocument).status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传时间">
          <template #default="{ row }">{{ formatDateTime((row as KbDocument).createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button
              size="small"
              :data-test="`doc-chunks-${(row as KbDocument).id}`"
              @click="openChunks(row as KbDocument)"
              >查看分段</el-button
            >
            <el-button
              v-if="canModify && (row as KbDocument).status === 'failed'"
              size="small"
              type="warning"
              :data-test="`doc-retry-${(row as KbDocument).id}`"
              @click="onRetry(row as KbDocument)"
              >重试</el-button
            >
            <el-button
              v-if="canModify"
              size="small"
              type="danger"
              :data-test="`doc-delete-${(row as KbDocument).id}`"
              @click="onDelete(row as KbDocument)"
              >删除</el-button
            >
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="dataset-detail__pager"
        layout="prev, pager, next, total"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="onPageChange"
      />
    </ContentCard>

    <el-drawer v-model="drawerVisible" :title="`分段预览 — ${chunkDoc?.name ?? ''}`" size="45%" append-to-body>
      <div v-for="c in chunks" :key="c.id" class="dataset-detail__chunk">
        <div class="dataset-detail__chunk-pos">段 {{ c.position }}</div>
        <div class="dataset-detail__chunk-content">{{ c.content }}</div>
      </div>
      <el-pagination
        size="small"
        layout="prev, pager, next, total"
        :total="chunkTotal"
        :current-page="chunkPage"
        :page-size="CHUNK_PAGE_SIZE"
        @current-change="onChunkPageChange"
      />
    </el-drawer>

    <el-dialog v-model="retrieveDialogVisible" title="命中测试" width="640">
      <div class="dataset-detail__retrieve-form">
        <el-input
          v-model="retrieveQuery"
          data-test="retrieve-query"
          placeholder="输入一句话，查看检索会命中哪些分段（不走 LLM）"
          maxlength="1000"
          @keyup.enter="onRetrieveRun"
        />
        <el-input-number
          v-model="retrieveTopK"
          data-test="retrieve-topk"
          :min="1"
          :max="20"
          placeholder="topK"
          controls-position="right"
        />
        <el-input-number
          v-model="retrieveThreshold"
          data-test="retrieve-threshold"
          :min="0"
          :max="1"
          :step="0.05"
          placeholder="阈值"
          controls-position="right"
        />
        <el-button type="primary" data-test="retrieve-run" :loading="retrieving" @click="onRetrieveRun"
          >测试</el-button
        >
      </div>
      <div class="dataset-detail__retrieve-hint">topK/阈值留空则使用系统全局配置</div>
      <template v-if="retrieveTried">
        <div v-if="hits.length === 0" class="dataset-detail__retrieve-empty">
          无命中分段（可尝试降低阈值或换个问法）
        </div>
        <div v-for="h in hits" :key="h.chunkId" class="dataset-detail__chunk">
          <div class="dataset-detail__chunk-pos">
            <el-tag size="small" type="success">{{ h.score.toFixed(2) }}</el-tag>
            <span class="dataset-detail__retrieve-doc">{{ h.documentName }}</span>
          </div>
          <div class="dataset-detail__chunk-content">{{ h.content }}</div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.dataset-detail__pager {
  margin-top: $spacing-md;
  justify-content: flex-end;
}
.dataset-detail__chunk {
  margin-bottom: $spacing-md;
  padding: $spacing-sm;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}
.dataset-detail__chunk-pos {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 4px;
}
.dataset-detail__chunk-content {
  white-space: pre-wrap;
  word-break: break-all;
}
.dataset-detail__retrieve-form {
  display: flex;
  gap: $spacing-sm;
  align-items: center;
}
.dataset-detail__retrieve-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 4px 0 $spacing-md;
}
.dataset-detail__retrieve-empty {
  color: var(--el-text-color-secondary);
  padding: $spacing-md 0;
}
.dataset-detail__retrieve-doc {
  margin-left: $spacing-sm;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
