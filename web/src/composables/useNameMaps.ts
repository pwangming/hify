import { ref } from 'vue'
import { listUsers } from '@/api/admin/user'
import { listApps } from '@/api/app'
import { listProviders } from '@/api/admin/provider'
import { listModels } from '@/api/admin/model'

/**
 * 看板名称解析：usage 接口只出 id（模块白名单限制，名称在前端拼装，见 spec §0）。
 * 并行拉用户/应用/模型三份列表建映射；解析不到（已删资源）回退「#id（已删除）」。
 */
export function useNameMaps() {
  const users = ref(new Map<string, string>())
  const apps = ref(new Map<string, string>())
  const models = ref(new Map<string, string>())

  async function loadApps() {
    const map = new Map<string, string>()
    let page = 1
    for (; page <= 5; page++) {
      const res = await listApps({ page, size: 100 })
      res.list.forEach((a) => map.set(a.id, a.name))
      if (res.list.length < 100) break
    }
    apps.value = map
  }

  async function loadModels() {
    const providers = await listProviders()
    const lists = await Promise.all(providers.map((p) => listModels(p.id)))
    const map = new Map<string, string>()
    lists.flat().forEach((m) => map.set(m.id, m.name))
    models.value = map
  }

  async function load() {
    await Promise.all([
      listUsers().then((list) => {
        users.value = new Map(list.map((u) => [u.id, u.username]))
      }),
      loadApps(),
      loadModels(),
    ])
  }

  const fallback = (id: string) => `#${id}（已删除）`
  const resolveUser = (id: string) => users.value.get(id) ?? fallback(id)
  const resolveApp = (id: string) => apps.value.get(id) ?? fallback(id)
  const resolveModel = (id: string) => models.value.get(id) ?? fallback(id)

  return { load, resolveUser, resolveApp, resolveModel, users, apps, models }
}
