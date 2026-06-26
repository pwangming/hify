<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { sendMessage } from '@/api/conversation'
import type { MessageView } from '@/types/conversation'

const route = useRoute()
const appId = route.params.appId as string

const messages = ref<MessageView[]>([])
const conversationId = ref<string | null>(null)
const input = ref('')
const sending = ref(false)

async function onSend() {
  const text = input.value.trim()
  if (!text || sending.value) return
  // 本地先渲染用户气泡（id 用本地占位，不与后端冲突）
  messages.value.push({
    id: `local-${Date.now()}`,
    role: 'user',
    content: text,
    promptTokens: null,
    completionTokens: null,
    createTime: new Date().toISOString(),
  })
  input.value = ''
  sending.value = true
  try {
    const res = await sendMessage(appId, conversationId.value, text)
    conversationId.value = res.conversationId
    messages.value.push(res.message)
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <div class="chat">
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
</template>

<style scoped lang="scss">
.chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 16px;
  gap: 12px;

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
