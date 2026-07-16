import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { config } from '@/config'
import { createTool, updateTool, previewTool, refreshTool, listTools } from '@/api/admin/tool'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const MCP_BODY = {
  name: 'deepwiki',
  description: 'wiki 问答',
  type: 'mcp' as const,
  url: 'https://mcp.example.com/mcp',
  transport: 'streamable_http',
  authHeaders: [],
}

// create/update/preview/refresh 都会在服务端现场连远端 MCP 做发现：
// 后端预算 connect 5s + initialize 10s + listTools 30s ≈ 45s > axios 默认 30s，
// 不带专用超时会客户端先断报「网络异常」（T4b 验收实测；同 llmTestTimeoutMs 教训）。
describe('admin tool api —— MCP 发现类接口带专用超时', () => {
  beforeEach(() => vi.clearAllMocks())

  it('createTool → POST + mcpDiscoverTimeoutMs', () => {
    createTool(MCP_BODY)
    expect(request.post).toHaveBeenCalledWith('/admin/tool/tools', MCP_BODY, {
      timeout: config.mcpDiscoverTimeoutMs,
    })
  })

  it('updateTool → PUT + mcpDiscoverTimeoutMs', () => {
    updateTool('5', MCP_BODY)
    expect(request.put).toHaveBeenCalledWith('/admin/tool/tools/5', MCP_BODY, {
      timeout: config.mcpDiscoverTimeoutMs,
    })
  })

  it('previewTool → POST preview + mcpDiscoverTimeoutMs', () => {
    previewTool({ type: 'mcp', url: 'https://mcp.example.com/mcp' })
    expect(request.post).toHaveBeenCalledWith(
      '/admin/tool/tools/preview',
      { type: 'mcp', url: 'https://mcp.example.com/mcp' },
      { timeout: config.mcpDiscoverTimeoutMs },
    )
  })

  it('refreshTool → POST refresh + mcpDiscoverTimeoutMs', () => {
    refreshTool('5')
    expect(request.post).toHaveBeenCalledWith('/admin/tool/tools/5/refresh', undefined, {
      timeout: config.mcpDiscoverTimeoutMs,
    })
  })

  it('listTools 等快接口不带专用超时', () => {
    listTools()
    expect(request.get).toHaveBeenCalledWith('/admin/tool/tools')
  })
})
