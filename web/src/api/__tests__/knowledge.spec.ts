import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import {
  listDatasets, getDataset, createDataset, updateDataset, deleteDataset,
} from '@/api/knowledge'
import type { DatasetForm } from '@/types/knowledge'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const FORM: DatasetForm = { name: '客服知识库', description: '售后答疑' }

describe('knowledge api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listDatasets → GET /knowledge/datasets + 分页/搜索 params', () => {
    listDatasets({ keyword: '客服', page: 2, size: 20 })
    expect(request.get).toHaveBeenCalledWith('/knowledge/datasets', {
      params: { keyword: '客服', page: 2, size: 20 },
    })
  })
  it('getDataset → GET /knowledge/datasets/{id}', () => {
    getDataset('10')
    expect(request.get).toHaveBeenCalledWith('/knowledge/datasets/10')
  })
  it('createDataset → POST /knowledge/datasets + body', () => {
    createDataset(FORM)
    expect(request.post).toHaveBeenCalledWith('/knowledge/datasets', FORM)
  })
  it('updateDataset → PUT /knowledge/datasets/{id} + body', () => {
    updateDataset('10', FORM)
    expect(request.put).toHaveBeenCalledWith('/knowledge/datasets/10', FORM)
  })
  it('deleteDataset → DELETE /knowledge/datasets/{id}', () => {
    deleteDataset('10')
    expect(request.delete).toHaveBeenCalledWith('/knowledge/datasets/10')
  })
})
