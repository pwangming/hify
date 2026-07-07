import { describe, it, expect } from 'vitest'
import type { AxiosError } from 'axios'
import { fallbackErrorMessage } from '@/api/request'

describe('fallbackErrorMessage', () => {
  it('网络层失败（无 response）→ 中文网络提示，不透传 axios 英文 message', () => {
    const err = { message: 'Network Error', response: undefined } as AxiosError
    expect(fallbackErrorMessage(err)).toBe('网络异常，请稍后重试')
  })

  it('请求超时（无 response）→ 中文网络提示', () => {
    const err = { message: 'timeout of 30000ms exceeded', response: undefined } as AxiosError
    expect(fallbackErrorMessage(err)).toBe('网络异常，请稍后重试')
  })

  it('有 response 但非 Result 信封（异常网关/非 JSON）→ 中文服务提示，不透传英文', () => {
    const err = { message: 'Request failed with status code 502', response: { status: 502 } } as AxiosError
    expect(fallbackErrorMessage(err)).toBe('服务器繁忙，请稍后重试')
  })
})
