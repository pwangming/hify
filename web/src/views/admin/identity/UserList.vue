<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  listUsers, createUser, enableUser, disableUser,
  resetPassword, changeRole, deleteUser,
} from '@/api/admin/user'
import type { AdminUser, CreateUserRequest } from '@/types/admin-user'
import type { UserRole } from '@/types/user'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/datetime'

const users = ref<AdminUser[]>([])
const loading = ref(false)
const userStore = useUserStore()

async function load() {
  loading.value = true
  try {
    users.value = await listUsers()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const enabledAdminCount = computed(
  () => users.value.filter((u) => u.role === 'admin' && u.status === 'enabled').length,
)
function isSelf(row: AdminUser): boolean {
  return row.id === userStore.user?.id
}
function isLastEnabledAdmin(row: AdminUser): boolean {
  return row.role === 'admin' && row.status === 'enabled' && enabledAdminCount.value <= 1
}
/** 危险操作（禁用/降级/删除）是否禁用 + 原因；null 表示放行。 */
function dangerDisabledReason(row: AdminUser): string | null {
  if (isSelf(row)) return '不能对自己做此操作'
  if (isLastEnabledAdmin(row)) return '需至少保留一个启用的管理员'
  return null
}

/** 执行一个动作并在成功后提示 + 重拉。业务/网络错误已由 request 拦截器弹 toast，这里吞掉避免未处理拒绝。 */
async function runAction(action: () => Promise<unknown>, successMsg: string) {
  try {
    await action()
    ElMessage.success(successMsg)
    await load()
  } catch {
    /* 已由 request 拦截器统一处理 */
  }
}

/** 危险操作二次确认；用户取消返回 false。 */
async function confirmDanger(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function onDisable(row: AdminUser) {
  if (!(await confirmDanger(`确定停用用户「${row.username}」？`, '停用确认'))) return
  await runAction(() => disableUser(row.id), '已停用')
}
async function onEnable(row: AdminUser) {
  await runAction(() => enableUser(row.id), '已启用')
}
async function onChangeRole(row: AdminUser) {
  const target: UserRole = row.role === 'admin' ? 'member' : 'admin'
  const label = target === 'admin' ? '管理员' : '成员'
  if (!(await confirmDanger(`确定将「${row.username}」改为${label}？`, '改角色确认'))) return
  await runAction(() => changeRole(row.id, target), '角色已修改')
}
async function onResetPassword(row: AdminUser) {
  try {
    const { value } = await ElMessageBox.prompt(`为用户「${row.username}」设置新密码`, '重置密码', {
      inputType: 'password',
      inputPattern: /^.{8,72}$/,
      inputErrorMessage: '密码长度需为 8~72 个字符',
    })
    await runAction(() => resetPassword(row.id, value), '密码已重置')
  } catch {
    /* 取消 */
  }
}
async function onDelete(row: AdminUser) {
  if (!(await confirmDanger(`确定删除用户「${row.username}」？此操作不可恢复。`, '删除确认'))) return
  await runAction(() => deleteUser(row.id), '已删除')
}

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<CreateUserRequest>({ username: '', password: '', role: 'member' })
const rules: FormRules<CreateUserRequest> = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 50, message: '用户名不超过 50 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 72, message: '密码长度需为 8~72 个字符', trigger: 'blur' },
  ],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
}

function openCreate() {
  form.username = ''
  form.password = ''
  form.role = 'member'
  dialogVisible.value = true
}
async function submitCreate() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 兜底：当前 vitest + happy-dom 下 el-form.validate() 对空必填字段会误判为通过（工具链兼容问题，
  // 见本任务排查报告；真实浏览器无此 bug）。提交前再按后端 CreateUserRequest 约束校验一次，
  // 确保「不合法不提交」既能被测试验证、也是生产代码的可靠护栏。
  if (!form.username || form.username.length > 50) return
  if (form.password.length < 8 || form.password.length > 72) return
  if (!form.role) return
  await createUser({ ...form })
  ElMessage.success('用户已创建')
  dialogVisible.value = false
  await load()
}
</script>

<template>
  <div class="user-list">
    <div class="user-list__header">
      <h2>用户管理</h2>
      <el-button type="primary" data-test="create-open" @click="openCreate">新建用户</el-button>
    </div>
    <el-table v-loading="loading" :data="users" data-test="user-table">
      <el-table-column prop="username" label="用户名" />
      <el-table-column label="角色">
        <template #default="{ row }">
          <el-tag :type="row.role === 'admin' ? 'danger' : 'info'">
            {{ row.role === 'admin' ? '管理员' : '成员' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态">
        <template #default="{ row }">
          <el-tag :type="row.status === 'enabled' ? 'success' : 'info'">
            {{ row.status === 'enabled' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间">
        <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="320">
        <template #default="{ row }">
          <el-tooltip
            v-if="dangerDisabledReason(row) && row.status === 'enabled'"
            :content="dangerDisabledReason(row)!"
          >
            <span>
              <el-button :data-test="`disable-${row.id}`" size="small" disabled>停用</el-button>
            </span>
          </el-tooltip>
          <el-button
            v-else-if="row.status === 'enabled'"
            :data-test="`disable-${row.id}`"
            size="small"
            @click="onDisable(row)"
          >停用</el-button>
          <el-button
            v-else
            :data-test="`enable-${row.id}`"
            size="small"
            type="success"
            @click="onEnable(row)"
          >启用</el-button>

          <el-button
            :data-test="`role-${row.id}`"
            size="small"
            :disabled="!!dangerDisabledReason(row)"
            @click="onChangeRole(row)"
          >{{ row.role === 'admin' ? '降为成员' : '升为管理员' }}</el-button>

          <el-button :data-test="`reset-${row.id}`" size="small" @click="onResetPassword(row)">重置密码</el-button>

          <el-button
            :data-test="`delete-${row.id}`"
            size="small"
            type="danger"
            :disabled="!!dangerDisabledReason(row)"
            @click="onDelete(row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新建用户" width="480">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" data-test="create-username" maxlength="50" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" data-test="create-password" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" data-test="create-role">
            <el-option label="成员" value="member" />
            <el-option label="管理员" value="admin" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" data-test="create-submit" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.user-list__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
</style>
