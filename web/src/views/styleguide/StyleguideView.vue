<script setup lang="ts">
import { ref } from 'vue'

// 纯展示页：对照方案 A 的设计 token，给视觉验收用。color 用 Element Plus 的 CSS 变量，
// 直接反映 index.scss 覆盖后的实际主题（所见即真实生效值）。

const primarySwatches = [
  { name: 'primary #5e6ad2', css: 'var(--el-color-primary)' },
  { name: 'light-3', css: 'var(--el-color-primary-light-3)' },
  { name: 'light-5', css: 'var(--el-color-primary-light-5)' },
  { name: 'light-7', css: 'var(--el-color-primary-light-7)' },
  { name: 'light-8', css: 'var(--el-color-primary-light-8)' },
  { name: 'light-9', css: 'var(--el-color-primary-light-9)' },
  { name: 'dark-2', css: 'var(--el-color-primary-dark-2)' },
]
const semanticSwatches = [
  { name: 'success #16a34a', css: 'var(--el-color-success)' },
  { name: 'warning #d97706', css: 'var(--el-color-warning)' },
  { name: 'danger #dc2626', css: 'var(--el-color-danger)' },
  { name: 'info #6b7280', css: 'var(--el-color-info)' },
]
const bgSwatches = [
  { name: 'bg-page #fafafa', css: 'var(--el-bg-color-page)' },
  { name: 'bg-card #ffffff', css: 'var(--el-bg-color)' },
  { name: 'hover #f5f5f5', css: 'var(--el-fill-color-light)' },
  { name: 'muted #f0f1f3', css: 'var(--el-fill-color)' },
]
const borderSwatches = [
  { name: 'border #e5e6eb', css: 'var(--el-border-color)' },
  { name: 'light #f0f0f2', css: 'var(--el-border-color-light)' },
  { name: 'strong #d4d6dc', css: 'var(--el-border-color-dark)' },
]
const textColors = [
  { name: 'primary #1a1a1a', css: 'var(--el-text-color-primary)' },
  { name: 'regular #404040', css: 'var(--el-text-color-regular)' },
  { name: 'secondary #6b7280', css: 'var(--el-text-color-secondary)' },
  { name: 'placeholder #9ca3af', css: 'var(--el-text-color-placeholder)' },
]

const switchVal = ref(true)
const selectVal = ref('chat')
const inputVal = ref('')
const tableData = ref([
  { id: '1001', name: '客服知识库', type: 'chat', status: '已发布' },
  { id: '1002', name: '工单自动分类', type: 'workflow', status: '草稿' },
  { id: '1003', name: '产品问答 Agent', type: 'chat', status: '已发布' },
])
</script>

<template>
  <div class="sg">
    <h1 class="sg__h1">样式预览 · 方案 A 开发者工具风</h1>
    <p class="sg__lead">下面每一块都对照 <code>variables.scss</code> 的设计 token。看着不对的地方告诉我，改一处全站生效。</p>

    <!-- 色板 -->
    <section class="sg__section">
      <h2 class="sg__h2">主色 Primary（克制使用：仅关键操作/选中/链接）</h2>
      <div class="sg__swatches">
        <div v-for="s in primarySwatches" :key="s.name" class="sg__swatch">
          <div class="sg__chip" :style="{ background: s.css }" />
          <span class="sg__chip-label">{{ s.name }}</span>
        </div>
      </div>
    </section>

    <section class="sg__section">
      <h2 class="sg__h2">功能色 Semantic</h2>
      <div class="sg__swatches">
        <div v-for="s in semanticSwatches" :key="s.name" class="sg__swatch">
          <div class="sg__chip" :style="{ background: s.css }" />
          <span class="sg__chip-label">{{ s.name }}</span>
        </div>
      </div>
    </section>

    <section class="sg__section">
      <h2 class="sg__h2">中性色阶 Neutrals（背景 / 边框）</h2>
      <div class="sg__swatches">
        <div v-for="s in [...bgSwatches, ...borderSwatches]" :key="s.name" class="sg__swatch">
          <div class="sg__chip sg__chip--bordered" :style="{ background: s.css }" />
          <span class="sg__chip-label">{{ s.name }}</span>
        </div>
      </div>
    </section>

    <!-- 文字色阶 -->
    <section class="sg__section">
      <h2 class="sg__h2">文字色阶 Text</h2>
      <p v-for="t in textColors" :key="t.name" :style="{ color: t.css }" class="sg__text-row">
        {{ t.name }} —— 永远相信美好的事情即将发生 The quick brown fox 1234567890
      </p>
    </section>

    <!-- 按钮 -->
    <section class="sg__section">
      <h2 class="sg__h2">按钮 Button</h2>
      <div class="sg__row">
        <el-button>默认</el-button>
        <el-button type="primary">主要</el-button>
        <el-button type="success">成功</el-button>
        <el-button type="warning">警告</el-button>
        <el-button type="danger">危险</el-button>
        <el-button type="info">信息</el-button>
      </div>
      <div class="sg__row">
        <el-button type="primary" plain>朴素</el-button>
        <el-button type="primary" round>圆角</el-button>
        <el-button type="primary" :loading="true">加载中</el-button>
        <el-button type="primary" disabled>禁用</el-button>
        <el-button type="primary" text>文字</el-button>
        <el-button type="primary" link>链接</el-button>
      </div>
    </section>

    <!-- 表单控件 -->
    <section class="sg__section">
      <h2 class="sg__h2">表单控件 Form</h2>
      <div class="sg__row sg__row--form">
        <el-input v-model="inputVal" placeholder="请输入" style="width: 220px" />
        <el-select v-model="selectVal" style="width: 160px">
          <el-option label="对话应用" value="chat" />
          <el-option label="工作流应用" value="workflow" />
        </el-select>
        <el-switch v-model="switchVal" />
      </div>
    </section>

    <!-- 卡片 + 阴影 -->
    <section class="sg__section">
      <h2 class="sg__h2">卡片 + 阴影 Shadow（克制，多靠边框分层）</h2>
      <div class="sg__row sg__row--cards">
        <div class="sg__card sg__card--sm">shadow-sm<br /><small>卡片轻微浮起</small></div>
        <div class="sg__card sg__card--md">shadow-md<br /><small>下拉 / 悬浮</small></div>
        <div class="sg__card sg__card--lg">shadow-lg<br /><small>弹窗 / 抽屉</small></div>
      </div>
    </section>

    <!-- 圆角 -->
    <section class="sg__section">
      <h2 class="sg__h2">圆角 Radius</h2>
      <div class="sg__row sg__row--cards">
        <div class="sg__radius sg__radius--sm">sm 4px</div>
        <div class="sg__radius sg__radius--md">md 6px</div>
        <div class="sg__radius sg__radius--lg">lg 8px</div>
        <div class="sg__radius sg__radius--bubble">bubble 12px</div>
      </div>
    </section>

    <!-- 过渡动效 -->
    <section class="sg__section">
      <h2 class="sg__h2">过渡动效 Transition（鼠标悬停下面方块看效果）</h2>
      <div class="sg__row sg__row--cards">
        <div class="sg__hover">hover 我<br /><small>浮起 + 主色边框</small></div>
      </div>
    </section>

    <!-- 标签 -->
    <section class="sg__section">
      <h2 class="sg__h2">标签 Tag</h2>
      <div class="sg__row">
        <el-tag>默认</el-tag>
        <el-tag type="success">已发布</el-tag>
        <el-tag type="warning">草稿</el-tag>
        <el-tag type="danger">已停用</el-tag>
        <el-tag type="info">归档</el-tag>
      </div>
    </section>

    <!-- 表格（密度示意 + 等宽 id） -->
    <section class="sg__section">
      <h2 class="sg__h2">表格 Table（后台默认密度）</h2>
      <el-table :data="tableData" border>
        <el-table-column prop="id" label="ID" width="120">
          <template #default="{ row }">
            <span class="mono">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="type" label="类型" width="140" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === '已发布' ? 'success' : 'warning'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default>
            <el-button type="primary" link size="small">编辑</el-button>
            <el-button type="danger" link size="small">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped lang="scss">
.sg {
  max-width: 960px;

  &__h1 {
    margin: 0 0 $spacing-sm;
    font-size: $font-size-xl;
    font-weight: 600;
    color: $color-text-primary;
  }

  &__lead {
    margin: 0 0 $spacing-2xl;
    color: $color-text-secondary;
    font-size: $font-size-sm;

    code {
      font-family: $font-family-mono;
      padding: 1px 4px;
      background: $color-bg-muted;
      border-radius: $radius-sm;
    }
  }

  &__section {
    padding: $spacing-xl;
    margin-bottom: $spacing-lg;
    background: $color-bg-card;
    border: 1px solid $color-border;
    border-radius: $radius-md;
  }

  &__h2 {
    margin: 0 0 $spacing-lg;
    font-size: $font-size-base;
    font-weight: 600;
    color: $color-text-primary;
  }

  &__swatches {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-lg;
  }

  &__swatch {
    display: flex;
    flex-direction: column;
    gap: $spacing-xs;
  }

  &__chip {
    width: 96px;
    height: 56px;
    border-radius: $radius-sm;

    &--bordered {
      border: 1px solid $color-border;
    }
  }

  &__chip-label {
    font-family: $font-family-mono;
    font-size: $font-size-xs;
    color: $color-text-secondary;
  }

  &__text-row {
    margin: 0 0 $spacing-sm;
    font-size: $font-size-base;
  }

  &__row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: $spacing-md;

    & + & {
      margin-top: $spacing-md;
    }

    &--form {
      gap: $spacing-lg;
    }

    &--cards {
      gap: $spacing-xl;
    }
  }

  &__card {
    width: 200px;
    padding: $spacing-lg;
    background: $color-bg-card;
    border: 1px solid $color-border-light;
    border-radius: $radius-md;
    font-size: $font-size-sm;
    color: $color-text-regular;

    small {
      color: $color-text-secondary;
    }

    &--sm {
      box-shadow: $shadow-sm;
    }
    &--md {
      box-shadow: $shadow-md;
    }
    &--lg {
      box-shadow: $shadow-lg;
    }
  }

  &__radius {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 120px;
    height: 64px;
    background: $color-primary-light-9;
    border: 1px solid $color-primary-light-7;
    color: $color-primary-dark-2;
    font-size: $font-size-sm;

    &--sm {
      border-radius: $radius-sm;
    }
    &--md {
      border-radius: $radius-md;
    }
    &--lg {
      border-radius: $radius-lg;
    }
    &--bubble {
      border-radius: $radius-bubble;
    }
  }

  &__hover {
    width: 200px;
    padding: $spacing-lg;
    background: $color-bg-card;
    border: 1px solid $color-border;
    border-radius: $radius-md;
    font-size: $font-size-sm;
    color: $color-text-regular;
    cursor: pointer;
    // 过渡 token 实战：颜色/阴影/位移统一节奏
    transition:
      box-shadow $transition-base $ease-standard,
      border-color $transition-base $ease-standard,
      transform $transition-base $ease-standard;

    small {
      color: $color-text-secondary;
    }

    &:hover {
      transform: translateY(-2px);
      box-shadow: $shadow-md;
      border-color: $color-primary;
    }
  }
}
</style>
