<script setup lang="ts">
// 页面标题区：标题 + 可选描述 + 右侧操作区（默认插槽）。渲染在内容区灰底上。
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <div class="page-header">
    <div class="page-header__text">
      <h2 class="page-header__title">{{ title }}</h2>
      <p v-if="description" class="page-header__desc">{{ description }}</p>
    </div>
    <div class="page-header__actions">
      <slot />
    </div>
  </div>
</template>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: $spacing-lg;

  &__title {
    margin: 0;
    font-size: $font-size-xl;
    font-weight: 600;
    color: $color-text-primary;
  }

  &__desc {
    margin: $spacing-xs 0 0;
    font-size: $font-size-sm;
    color: $color-text-secondary;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: $spacing-md;
  }
}
</style>
