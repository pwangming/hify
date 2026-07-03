<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getEmbeddingSetting, saveEmbeddingSetting } from '@/api/admin/provider'
import { reembedAll } from '@/api/admin/knowledge'
import { listEmbeddingModels } from '@/api/provider'
import type { ModelOption } from '@/types/model'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const models = ref<ModelOption[]>([])
const selectedModelId = ref<string | null>(null)
const savedModelId = ref<string | null>(null)
const savedModelName = ref<string | null>(null)
const saving = ref(false)
const reembedding = ref(false)

const configured = computed(() => savedModelId.value !== null)

async function load() {
  const [setting, list] = await Promise.all([getEmbeddingSetting(), listEmbeddingModels()])
  savedModelId.value = setting.modelId
  savedModelName.value = setting.modelName
  selectedModelId.value = setting.modelId
  models.value = list
}

onMounted(load)

async function onSave() {
  if (!selectedModelId.value) return
  saving.value = true
  try {
    const saved = await saveEmbeddingSetting(selectedModelId.value)
    savedModelId.value = saved.modelId
    savedModelName.value = saved.modelName
    ElMessage.success('已保存（模型探测通过，输出 1024 维）')
  } catch {
    /* 失败（12002/12003/12005）由 request 拦截器统一 toast */
  } finally {
    saving.value = false
  }
}

async function onReembed() {
  try {
    await ElMessageBox.confirm(
      '将清空全部分段向量并按当前模型重新嵌入，会调用外部 API 产生费用，且耗时随文档量增长。确定开始？',
      '全量重嵌入',
      { type: 'warning', confirmButtonText: '开始重嵌入' },
    )
  } catch {
    return
  }
  reembedding.value = true
  try {
    await reembedAll()
    ElMessage.success('已开始重嵌入，可到各知识库详情页查看文档状态')
  } catch {
    /* 15003（已在进行中）等由 request 拦截器统一 toast */
  } finally {
    reembedding.value = false
  }
}
</script>

<template>
  <div class="system-settings">
    <PageHeader title="系统设置" description="全局生效的系统级配置（仅管理员）" />

    <ContentCard>
      <h3 class="system-settings__section-title">embedding 模型</h3>
      <p class="system-settings__hint">
        知识库向量化使用的模型，全库统一（输出必须为 1024 维，如通义 text-embedding-v4）。
        保存时会真实调用一次该模型验证维度与连通性。切换模型后需点「全量重嵌入」重建全部向量。
      </p>
      <div class="system-settings__row">
        <el-select
          v-model="selectedModelId"
          placeholder="选择 embedding 模型"
          style="width: 320px"
          data-test="embedding-select"
        >
          <el-option
            v-for="m in models"
            :key="m.id"
            :label="`${m.name}（${m.providerName}）`"
            :value="m.id"
          />
        </el-select>
        <el-button
          type="primary"
          data-test="save-embedding"
          :loading="saving"
          :disabled="!selectedModelId"
          @click="onSave"
        >
          保存
        </el-button>
      </div>
      <p v-if="configured" class="system-settings__current">
        当前生效：{{ savedModelName ?? savedModelId }}
      </p>
      <p v-else class="system-settings__current system-settings__current--empty">
        尚未配置，配置前上传的文档会进入失败态，配置后可在文档列表点重试恢复。
      </p>

      <el-divider />

      <h3 class="system-settings__section-title">全量重嵌入</h3>
      <p class="system-settings__hint">
        对全部文档重新向量化：首次配好模型后执行一次可补齐存量文档；切换模型后必须执行。
      </p>
      <el-button
        type="warning"
        data-test="reembed-all"
        :loading="reembedding"
        :disabled="!configured"
        @click="onReembed"
      >
        全量重嵌入
      </el-button>
    </ContentCard>
  </div>
</template>

<style scoped lang="scss">
.system-settings {
  &__section-title {
    margin: 0 0 $spacing-sm;
    font-size: $font-size-lg;
  }

  &__hint {
    margin: 0 0 $spacing-md;
    max-width: 760px;
    color: var(--el-text-color-secondary);
    font-size: $font-size-sm;
    line-height: 1.7;
  }

  &__row {
    display: flex;
    align-items: center;
    gap: $spacing-sm;
  }

  &__current {
    margin: $spacing-sm 0 0;
    color: var(--el-text-color-secondary);
    font-size: $font-size-sm;
  }

  &__current--empty {
    color: var(--el-color-warning);
  }
}
</style>
