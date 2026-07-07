import { get, post, put, del } from '@/utils/request'

export type ProviderType = 'OPENAI' | 'ANTHROPIC' | 'GEMINI' | 'AZURE_OPENAI' | 'OLLAMA' | 'OPENAI_COMPATIBLE'
export type HealthStatus = 'UP' | 'DOWN' | 'DEGRADED' | 'UNKNOWN'

export interface ProviderHealth {
  status: HealthStatus
  lastCheckAt: string | null
  latencyMs: number | null
  errorMessage: string | null
}

export interface ModelConfig {
  id: number
  name: string
  modelId: string
  contextSize: number
  enabled: number
}

export interface ProviderVO {
  id: number
  name: string
  type: ProviderType
  baseUrl: string
  description: string
  enabled: boolean
  authConfigured: boolean
  createdAt: string
  updatedAt: string
  models: ModelConfig[]
  health: ProviderHealth | null
}

export interface ProviderPageParams {
  page: number
  pageSize: number
  type?: string
  enabled?: boolean
}

export interface ProviderCreateDTO {
  name: string
  type: ProviderType
  baseUrl: string
  description?: string
  authConfig: Record<string, string>
}

export interface ProviderUpdateDTO {
  name?: string
  baseUrl?: string
  description?: string
  authConfig?: Record<string, string>
  enabled?: boolean
}

export interface ConnectionTestResult {
  success: boolean
  latencyMs: number | null
  modelCount: number | null
  errorMessage: string | null
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

export const getProviderList = (params: ProviderPageParams) =>
  get<PageResult<ProviderVO>>('/v1/providers', params)

export const getProviderDetail = (id: number) =>
  get<ProviderVO>(`/v1/providers/${id}`)

export const createProvider = (data: ProviderCreateDTO) =>
  post<ProviderVO>('/v1/providers', data)

export const updateProvider = (id: number, data: ProviderUpdateDTO) =>
  put<ProviderVO>(`/v1/providers/${id}`, data)

export const deleteProvider = (id: number) =>
  del<void>(`/v1/providers/${id}`)

export const testConnection = (id: number) =>
  post<ConnectionTestResult>(`/v1/providers/${id}/test-connection`, {})

/** 从远程拉取模型 ID 列表 */
export const fetchRemoteModels = (id: number) =>
  get<string[]>(`/v1/providers/${id}/fetch-models`)

// ── 模型配置 CRUD ──────────────────────────────────────────

export interface ModelConfigCreateDTO {
  name: string
  modelId: string
  contextSize?: number
}

export interface ModelConfigUpdateDTO {
  name: string
  modelId: string
  contextSize?: number
  enabled?: number
}

export const listModels = (providerId: number) =>
  get<ModelConfig[]>(`/v1/providers/${providerId}/models`)

export const addModel = (providerId: number, data: ModelConfigCreateDTO) =>
  post<ModelConfig>(`/v1/providers/${providerId}/models`, data)

export const updateModel = (providerId: number, modelId: number, data: ModelConfigUpdateDTO) =>
  put<ModelConfig>(`/v1/providers/${providerId}/models/${modelId}`, data)

export const deleteModel = (providerId: number, modelId: number) =>
  del<void>(`/v1/providers/${providerId}/models/${modelId}`)
