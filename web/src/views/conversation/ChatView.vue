<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useConversationStore } from '@/stores/conversation'
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
</script>

<template>
  <div class="chat">
    <ConversationSidebar
      :conversations="conversations"
      :current-id="currentId"
      @select="selectConversation"
      @new="startNew"
    />
    <div class="chat__main">
      <div class="chat__list">
        <div
          v-for="m in messages"
          :key="m.id"
          :class="['chat__bubble', `chat__bubble--${m.role}`]"
          data-test="msg"
        >
          {{ m.content }}
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
