import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import {
  listModels, createModel, updateModel,
  deleteModel, enableModel, disableModel,
} from '@/api/admin/model'
import type { ModelForm } from '@/types/model'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

describe('admin model api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listModels → GET /admin/provider/providers/{providerId}/models', () => {
    listModels('5')
    expect(request.get).toHaveBeenCalledWith('/admin/provider/providers/5/models')
  })
  it('createModel → POST /admin/provider/providers/{providerId}/models + body', () => {
    const body: ModelForm = { type: 'chat', name: 'GPT-4o', modelKey: 'gpt-4o' }
    createModel('5', body)
    expect(request.post).toHaveBeenCalledWith('/admin/provider/providers/5/models', body)
  })
  it('updateModel → PUT /admin/provider/models/{id} + {name,modelKey}', () => {
    updateModel('8', { name: '改名', modelKey: 'gpt-4o-mini' })
    expect(request.put).toHaveBeenCalledWith('/admin/provider/models/8', {
      name: '改名', modelKey: 'gpt-4o-mini',
    })
  })
  it('deleteModel → DELETE /admin/provider/models/{id}', () => {
    deleteModel('8')
    expect(request.delete).toHaveBeenCalledWith('/admin/provider/models/8')
  })
  it('enableModel → POST .../{id}/enable', () => {
    enableModel('8')
    expect(request.post).toHaveBeenCalledWith('/admin/provider/models/8/enable')
  })
  it('disableModel → POST .../{id}/disable', () => {
    disableModel('8')
    expect(request.post).toHaveBeenCalledWith('/admin/provider/models/8/disable')
  })
})
