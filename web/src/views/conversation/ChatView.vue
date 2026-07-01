<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { DocumentCopy, EditPen } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useConversationStore } from '@/stores/conversation'
import type { MessageView } from '@/types/conversation'
import ConversationSidebar from './ConversationSidebar.vue'

const route = useRoute()
const router = useRouter()
const appId = route.params.appId as string

const store = useConversationStore()
const { conversations, messages, currentId, sending } = storeToRefs(store)

const input = ref('')

// URL query.c 是当前会话 id 的「真相」：刷新/切换都以它为准载入历史。
const queryCid = computed(() => (route.query.c as string | undefined) ?? null)

watch(
  queryCid,
  async (cid) => {
    store.abort()                       // 切会话先止血在途流
    if (!cid) {
      store.newConversation()
      return
    }
    if (cid === currentId.value) return // 已是当前会话（如刚发完新会话首条，URL 写回不必重新拉取）
    await store.loadMessages(cid)
  },
  { immediate: true },
)

onMounted(() => store.loadConversations(appId))
onBeforeUnmount(() => store.abort())

function selectConversation(id: string) {
  if (id !== currentId.value) router.push({ query: { c: id } })
}

function startNew() {
  store.newConversation()
  if (queryCid.value) router.replace({ query: {} })
}

async function onSend() {
  const text = input.value.trim()
  if (!text || sending.value) return
  input.value = ''
  const wasNew = currentId.value === null
  try {
    const cid = await store.send(appId, text)
    if (wasNew) {
      // 新会话拿到 id：写回 URL（replace 不增历史栈）并刷新侧边栏
      await router.replace({ query: { c: cid } })
      await store.loadConversations(appId)
    }
  } catch {
    // 错误已由 store 的 onError 行内展示，此处静默兜底防止未处理 rejection
  }
}

// 复制可见性：用户气泡恒可复制；AI 气泡「正在流式的最后一条」不可复制（内容未完），done 后自然显现。
function canCopy(m: MessageView, i: number): boolean {
  if (m.role === 'user') return true
  return !(sending.value && i === messages.value.length - 1)
}

async function copyMsg(m: MessageView) {
  await navigator.clipboard.writeText(m.content)
  ElMessage.success('已复制')
}

// 轻量编辑（B）：把原文回填输入框供修改，历史不动，改完当作新消息发送。
function editMsg(m: MessageView) {
  input.value = m.content
}

function onRenameConv(payload: { id: string; title: string }) {
  store.renameConversation(payload.id, payload.title)
}

async function onDeleteConv(id: string) {
  await store.deleteConversation(id)
  if (!store.currentId && queryCid.value) router.replace({ query: {} }) // 删的是当前会话 → 清 URL 的 ?c=
}
</script>

<template>
  <div class="chat">
    <ConversationSidebar
      :conversations="conversations"
      :current-id="currentId"
      @select="selectConversation"
      @new="startNew"
      @rename="onRenameConv"
      @delete="onDeleteConv"
    />
    <div class="chat__main">
      <div class="chat__list">
        <div
          v-for="(m, i) in messages"
          :key="m.id"
          :class="['chat__bubble', `chat__bubble--${m.role}`]"
          data-test="msg"
        >
          <div class="chat__bubble-text">{{ m.content }}</div>
          <div :class="['chat__bubble-ops', `chat__bubble-ops--${m.role}`]">
            <el-icon
              v-if="canCopy(m, i)"
              class="chat__op"
              :data-test="`copy-msg-${m.id}`"
              title="复制"
              @click="copyMsg(m)"
            ><DocumentCopy /></el-icon>
            <el-tooltip v-if="m.role === 'user'" content="重新编辑后发送" placement="top">
              <el-icon class="chat__op" :data-test="`edit-msg-${m.id}`" @click="editMsg(m)"><EditPen /></el-icon>
            </el-tooltip>
          </div>
        </div>
      </div>
      <div class="chat__input">
        <div data-test="chat-input">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            :disabled="sending"
            placeholder="输入消息，回车或点发送…"
            @keyup.enter.exact.prevent="onSend"
          />
        </div>
        <el-button type="primary" data-test="chat-send" :loading="sending" @click="onSend">
          发送
        </el-button>
      </div>
      <p class="chat__disclaimer" data-test="ai-disclaimer">本回答由 AI 生成，请谨慎甄别</p>
    </div>
  </div>
</template>

<style scoped lang="scss">
.chat {
  display: flex;
  height: 100%;

  &__main {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 12px;
    padding: 16px;
  }

  &__list {
    flex: 1;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__bubble {
    max-width: 70%;
    padding: 8px 12px;
    border-radius: 8px;
    white-space: pre-wrap;
    word-break: break-word;

    &--user {
      align-self: flex-end;
      background: var(--el-color-primary-light-8);
    }

    &--assistant {
      align-self: flex-start;
      background: var(--el-fill-color-light);
    }
  }

  // 气泡操作区：默认隐藏，气泡 hover 时显现；用户右下角、AI 左下角
  &__bubble-ops {
    display: flex;
    gap: 8px;
    margin-top: 4px;
    opacity: 0;
    transition: opacity 0.15s;

    &--user {
      justify-content: flex-end;
    }

    &--assistant {
      justify-content: flex-start;
    }
  }

  &__bubble:hover &__bubble-ops {
    opacity: 1;
  }

  &__op {
    cursor: pointer;
    color: var(--el-text-color-secondary);

    &:hover {
      color: var(--el-color-primary);
    }
  }

  &__disclaimer {
    margin: 0;
    text-align: center;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  &__input {
    display: flex;
    gap: 8px;
    align-items: flex-end;

    > [data-test="chat-input"] {
      flex: 1;
    }
  }
}
</style>
