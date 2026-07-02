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
