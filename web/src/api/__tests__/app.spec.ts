import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import {
  listApps, getApp, createApp, updateApp, deleteApp, enableApp, disableApp,
} from '@/api/app'
import type { AppForm } from '@/types/app'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const FORM: AppForm = { name: '客服助手', description: '答疑', config: { systemPrompt: '你是客服' } }

describe('app api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listApps → GET /app/apps + 分页/筛选 params', () => {
    listApps({ keyword: '客服', page: 2, size: 20 })
    expect(request.get).toHaveBeenCalledWith('/app/apps', {
      params: { keyword: '客服', page: 2, size: 20 },
    })
  })
  it('getApp → GET /app/apps/{id}', () => {
    getApp('10')
    expect(request.get).toHaveBeenCalledWith('/app/apps/10')
  })
  it('createApp → POST /app/apps + body(type=chat)', () => {
    createApp(FORM)
    expect(request.post).toHaveBeenCalledWith('/app/apps', { ...FORM, type: 'chat' })
  })
  it('updateApp → PUT /app/apps/{id} + body', () => {
    updateApp('10', FORM)
    expect(request.put).toHaveBeenCalledWith('/app/apps/10', FORM)
  })
  it('deleteApp → DELETE /app/apps/{id}', () => {
    deleteApp('10')
    expect(request.delete).toHaveBeenCalledWith('/app/apps/10')
  })
  it('enableApp / disableApp → POST .../{id}/enable|disable', () => {
    enableApp('10'); disableApp('10')
    expect(request.post).toHaveBeenCalledWith('/app/apps/10/enable')
    expect(request.post).toHaveBeenCalledWith('/app/apps/10/disable')
  })
})
