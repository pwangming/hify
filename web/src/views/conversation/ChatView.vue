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

// 行内编辑态：正在编辑的用户消息 id 与其文本。null=无。
const editingId = ref<string | null>(null)
const editingText = ref('')

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

// 发送一条文本（新会话首发写回 URL + 刷新侧边栏）。输入框与行内编辑共用。
async function deliver(text: string) {
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

async function onSend() {
  if (sending.value) return
  const text = input.value.trim()
  if (!text) return
  input.value = ''
  await deliver(text)
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

// 轻量编辑（B）：在原消息上行内编辑，历史不动；发送时在底部生成一条新消息。
function startEdit(m: MessageView) {
  editingId.value = m.id
  editingText.value = m.content
}

function cancelEdit() {
  editingId.value = null
}

async function submitEdit() {
  if (sending.value) return
  const text = editingText.value.trim()
  if (!text) return
  editingId.value = null
  await deliver(text) // 底部生成新消息，原消息保持不变
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
          :class="['chat__row', `chat__row--${m.role}`]"
          data-test="msg"
        >
          <div class="chat__bubble">
            <!-- 行内编辑（仅用户消息）：编辑框预填原文 + 发送/取消 -->
            <div v-if="editingId === m.id" class="chat__edit">
              <div :data-test="`edit-input-${m.id}`">
                <el-input v-model="editingText" type="textarea" :rows="2" />
              </div>
              <div class="chat__edit-actions">
                <el-button size="small" @click="cancelEdit">取消</el-button>
                <el-button size="small" type="primary" :data-test="`edit-send-${m.id}`" @click="submitEdit">
                  发送
                </el-button>
              </div>
            </div>
            <div v-else class="chat__bubble-text">{{ m.content }}</div>
          </div>
          <!-- 操作图标：气泡外，hover 显现；用户右下角（复制+编辑）、AI 左下角（复制，回答完成后可用） -->
          <div v-if="editingId !== m.id" class="chat__ops">
            <template v-if="m.role === 'user'">
              <el-icon class="chat__op" :data-test="`copy-msg-${m.id}`" title="复制" @click="copyMsg(m)"><DocumentCopy /></el-icon>
              <el-icon class="chat__op" :data-test="`edit-msg-${m.id}`" title="重新编辑后发送" @click="startEdit(m)"><EditPen /></el-icon>
            </template>
            <el-icon
              v-else-if="canCopy(m, i)"
              class="chat__op"
              :data-test="`copy-msg-${m.id}`"
              title="复制"
              @click="copyMsg(m)"
            ><DocumentCopy /></el-icon>
          </div>
        </div>
      </div>
      <div class="chat__input">
        <div class="chat__input-box" data-test="chat-input">
          <el-input
            v-model="input"
            type="textarea"
            :rows="4"
            :disabled="sending"
            placeholder="输入消息，回车或点发送…"
            @keyup.enter.exact.prevent="onSend"
          />
          <el-button
            class="chat__send"
            type="primary"
            data-test="chat-send"
            :loading="sending"
            @click="onSend"
          >
            发送
          </el-button>
        </div>
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
    padding: 16px 0; // 左右留白下放到 list/input 内部，好让滚动条贴最右
  }

  &__list {
    flex: 1;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 8px;
    // 滚动容器铺满到最右（滚动条贴窗口右边）；消息两侧靠内部 padding 留白，
    // 右侧 50px 即消息与滚动条的间距。
    padding: 0 50px;
  }

  // 每条消息一「行」：气泡 + 气泡外操作图标，按角色左右对齐
  &__row {
    display: flex;
    flex-direction: column;
    gap: 8px; // 气泡与操作图标的距离
    max-width: 70%;

    &--user {
      align-self: flex-end;
      align-items: flex-end;
    }

    &--assistant {
      align-self: flex-start;
      align-items: flex-start;
    }
  }

  &__bubble {
    padding: 8px 12px;
    border-radius: 8px;
    white-space: pre-wrap;
    word-break: break-word;
  }

  &__row--user &__bubble {
    background: var(--el-color-primary-light-8);
  }

  &__row--assistant &__bubble {
    background: var(--el-fill-color-light);
  }

  // 操作图标区：气泡外，默认隐藏，整行 hover 时显现。
  // 左右对齐由 row 的 align-items 决定（用户右下、AI 左下）。
  &__ops {
    display: flex;
    gap: 16px; // 图标之间的间距
    min-height: 18px;
    padding: 0 2px;
    opacity: 0;
    transition: opacity 0.15s;
  }

  &__row:hover &__ops {
    opacity: 1;
  }

  // 行内编辑框
  &__edit-actions {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
    margin-top: 6px;
  }

  &__op {
    cursor: pointer;
    font-size: 18px; // 图标整体放大
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
    padding: 0 50px; // 与消息两侧留白对齐（恢复输入框原宽）
  }

  &__input-box {
    position: relative;
    flex: 1;

    // 给内嵌发送按钮留底部空间 + 圆角更圆润
    :deep(.el-textarea__inner) {
      padding-bottom: 44px;
      border-radius: 12px;
    }
  }

  &__send {
    position: absolute;
    right: 10px;
    bottom: 10px;
    z-index: 1;
  }
}
</style>
