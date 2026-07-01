<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listApps } from '@/api/app'
import type { App } from '@/types/app'

const router = useRouter()
const apps = ref<App[]>([])
const loading = ref(false)

// 只展示已启用的对话应用（停用的进不了对话）
const enabledApps = computed(() => apps.value.filter((a) => a.status === 'enabled'))

onMounted(async () => {
  loading.value = true
  try {
    const page = await listApps({ page: 1, size: 100 })
    apps.value = page.list
  } finally {
    loading.value = false
  }
})

function open(a: App) {
  if (!a.modelUsable) return // 模型不可用不可进（与「试聊」按钮 disabled 同源）
  router.push(`/apps/${a.id}/chat`)
}
</script>

<template>
  <div v-loading="loading" class="chat-home">
    <h2 class="chat-home__title">选择一个应用开始对话</h2>
    <div v-if="enabledApps.length" class="chat-home__grid">
      <el-card
        v-for="a in enabledApps"
        :key="a.id"
        shadow="hover"
        :class="['chat-home__card', { 'chat-home__card--disabled': !a.modelUsable }]"
        data-test="chat-app-card"
        @click="open(a)"
      >
        <div class="chat-home__name">{{ a.name }}</div>
        <div class="chat-home__desc">{{ a.description ?? '暂无描述' }}</div>
        <div class="chat-home__model">
          {{ a.modelName ?? '未配置模型' }}
          <span v-if="!a.modelUsable" class="chat-home__muted">（模型不可用）</span>
        </div>
      </el-card>
    </div>
    <el-empty
      v-else
      data-test="chat-empty"
      description="暂无可用的对话应用，请先到「应用管理」创建并启用"
    />
  </div>
</template>

<style scoped lang="scss">
.chat-home {
  padding: 24px;

  &__title {
    margin: 0 0 16px;
    font-size: 18px;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 16px;
  }

  &__card {
    cursor: pointer;

    &--disabled {
      cursor: not-allowed;
      opacity: 0.6;
    }
  }

  &__name {
    font-weight: 600;
    margin-bottom: 6px;
  }

  &__desc {
    min-height: 20px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__model {
    margin-top: 10px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  &__muted {
    color: var(--el-color-danger);
  }
}
</style>
