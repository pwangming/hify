<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, getCurrentUser } from '@/api/auth'
import { useUserStore } from '@/stores/user'

// 登录页：账号密码 → 拿 token → 拉用户 → 跳回 redirect 目标（或首页）。
// 失败（账号密码错等）由 request 拦截器统一弹 toast，这里只管成功路径与 loading。
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function handleLogin() {
  loading.value = true
  try {
    const { token } = await login({ username: form.username, password: form.password })
    userStore.setToken(token)
    await userStore.loadCurrentUser()
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="login__card">
      <div class="login__title">Hify</div>
      <el-form label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="请输入账号" data-test="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            data-test="password"
          />
        </el-form-item>
        <el-button
          type="primary"
          class="login__submit"
          :loading="loading"
          data-test="submit"
          @click="handleLogin"
        >
          登录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: $color-bg-page;

  &__card {
    width: 360px;
    padding: $spacing-2xl;
    border: 1px solid $color-border;
    border-radius: $radius-md;
    background: $color-bg-card;
  }

  &__title {
    margin-bottom: $spacing-xl;
    font-size: $font-size-xl;
    font-weight: 600;
    text-align: center;
    color: $color-text-primary;
  }

  &__submit {
    width: 100%;
  }
}
</style>
