/** 知识库视图（对齐后端 DatasetResponse）。id/ownerId 为 string（Long 序列化防精度丢失）。 */
export interface Dataset {
  id: string
  name: string
  description: string | null
  ownerId: string
  createTime: string
  updateTime: string
}

/** 创建/编辑共用表单。 */
export interface DatasetForm {
  name: string
  description: string
}

/** 文档处理状态（对齐后端 kb_document.status 四态）。K3 上传后异步流转 pending→processing→ready/failed。 */
export type DocumentStatus = 'pending' | 'processing' | 'ready' | 'failed'

/** 文档视图（对齐后端 DocumentResponse）。命名 KbDocument 避免与 DOM 内置 Document 撞名。
 *  id/datasetId/fileSize 为 string（Long 序列化）；chunkCount 是 Integer → number。 */
export interface KbDocument {
  id: string
  datasetId: string
  name: string
  fileType: 'txt' | 'md'
  fileSize: string
  status: DocumentStatus
  chunkCount: number
  /** status=failed 时的用户可读原因；其余状态为 null */
  errorMessage: string | null
  createTime: string
  updateTime: string
}

/** 分段预览（对齐后端 ChunkResponse）。position 从 1 起。 */
export interface Chunk {
  id: string
  position: number
  content: string
}
