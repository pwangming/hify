<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import BlankLayout from '@/layouts/BlankLayout.vue'

// 按路由 meta.layout 选布局：缺省 default（后台主框架），'blank' 用于登录页等无壳页面。
// 布局内容用 <slot/>，这里把 <RouterView/> 作为插槽塞入——页面随路由切换，外壳不变。
const route = useRoute()
const layout = computed(() => (route.meta.layout === 'blank' ? BlankLayout : DefaultLayout))
</script>

<template>
  <component :is="layout">
    <RouterView />
  </component>
</template>
