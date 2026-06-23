<script setup lang="ts">
// 白卡片容器：白底 + 轻阴影 + 圆角，包表格/表单，与灰底拉开层次。
</script>

<template>
  <div class="content-card">
    <slot />
  </div>
</template>

<style scoped lang="scss">
.content-card {
  padding: $spacing-lg;
  background: $color-bg-card;
  border: 1px solid $color-border;
  border-radius: $radius-md;
  box-shadow: $shadow-sm;
}
</style>
