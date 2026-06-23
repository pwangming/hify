<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getHealth } from '@/api/health'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

// 页面加载时探后端：HTTP 200 即成功（见 api-standards.md 第 1 节）。
// silent:true 让本页自行展示结果，不弹拦截器的全局 toast。
const connected = ref<boolean | null>(null)
const text = ref('')

onMounted(async () => {
  try {
    const body = await getHealth({ silent: true })
    connected.value = true
    text.value = `后端已连接：${body}`
  } catch {
    connected.value = false
    text.value = '后端未连接'
  }
})
</script>

<template>
  <div>
    <PageHeader title="模型提供商管理" description="配置模型供应商接入与连通性状态" />
    <ContentCard>
      <p v-if="connected !== null" :class="connected ? 'status--ok' : 'status--err'">
        {{ text }}
      </p>
    </ContentCard>
  </div>
</template>

<style scoped lang="scss">
.status--ok {
  color: $color-success;
}

.status--err {
  color: $color-danger;
}
</style>
