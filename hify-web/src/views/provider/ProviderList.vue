<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <div class="page-header-left">
        <div class="page-title">模型提供商管理</div>
        <div class="page-desc">管理接入的 AI 模型提供商，配置 API Key 和连接信息</div>
      </div>
      <div class="page-header-actions">
        <button class="btn-primary" @click="dialogRef?.open()">
          <el-icon><Plus /></el-icon>
          新增提供商
        </button>
      </div>
    </div>

    <!-- 列表 -->
    <div class="hify-card provider-card">
      <HifyTable
        :columns="columns"
        :api="fetchProviders"
        :row-style="{ height: '52px' }"
        ref="tableRef"
      >
        <!-- 启用状态列 -->
        <template #status="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '禁用' }}
          </el-tag>
        </template>
        <!-- 健康状态列 -->
        <template #health="{ row }">
          <template v-if="row.health">
            <el-tag :type="healthTagType(row.health.status)" size="small">
              {{ healthLabel(row.health.status) }}
            </el-tag>
            <span v-if="row.health.latencyMs != null" class="latency-ms">
              {{ row.health.latencyMs }}ms
            </span>
          </template>
          <el-tag v-else type="info" size="small">未知</el-tag>
        </template>
        <!-- 模型数列 -->
        <template #models="{ row }">
          <el-popover
            v-if="row.models && row.models.length > 0"
            placement="bottom-start"
            :width="300"
            trigger="click"
          >
            <template #reference>
              <span class="model-count-link">{{ enabledModelCount(row) }} 个</span>
            </template>
            <div class="model-list-popup">
              <div class="model-list-title">已配置模型（{{ row.models.length }} 个）</div>
              <div
                v-for="m in row.models"
                :key="m.id"
                class="model-list-item"
              >
                <span>{{ m.displayName || m.modelId }}</span>
                <el-tag :type="m.enabled ? 'success' : 'info'" size="small">
                  {{ m.enabled ? '启用' : '禁用' }}
                </el-tag>
              </div>
            </div>
          </el-popover>
          <span v-else class="text-muted">0 个</span>
        </template>
        <!-- 操作列 -->
        <template #action="{ row }">
          <el-button type="primary" link size="small" @click="dialogRef?.open(row)">编辑</el-button>
          <el-button
            type="success" link size="small"
            style="margin-left: 4px;"
            @click="openModelDialog(row)"
          >模型</el-button>
          <el-button
            type="warning" link size="small"
            style="margin-left: 4px;"
            :loading="testingId === row.id"
            @click="onTestConnection(row)"
          >测试</el-button>
          <el-button type="danger" link size="small" style="margin-left: 4px;" @click="onDelete(row)">删除</el-button>
        </template>
      </HifyTable>
    </div>

    <!-- 新增/编辑弹窗 -->
    <HifyFormDialog
      ref="dialogRef"
      :title="dialogTitle"
      :rules="rules"
      width="520px"
      label-width="100px"
      @submit="onSubmit"
    >
      <template #default="{ form }">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入提供商名称" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" placeholder="请选择类型" style="width: 100%">
            <el-option v-for="t in providerTypes" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" placeholder="留空表示不修改" show-password />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="https://api.openai.com" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" placeholder="可选" />
        </el-form-item>
      </template>
    </HifyFormDialog>

    <!-- 模型管理弹窗 -->
    <el-dialog
      v-model="modelDialogVisible"
      :title="`模型管理 - ${currentProvider?.name}`"
      width="680px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div style="margin-bottom: 12px; text-align: right;">
        <el-button type="primary" size="small" @click="openAddModel">
          <el-icon><Plus /></el-icon>
          添加模型
        </el-button>
      </div>
      <el-table :data="modelList" size="small" border style="width: 100%">
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="modelId" label="模型 ID" min-width="160" />
        <el-table-column prop="contextSize" label="上下文 Token" width="120" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
              {{ row.enabled === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openEditModel(row)">编辑</el-button>
            <el-button type="danger" link size="small" style="margin-left:4px" @click="onDeleteModel(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="modelList.length === 0" style="text-align:center;padding:20px;color:#999">
        暂无模型配置，点击「添加模型」开始
      </div>
    </el-dialog>

    <!-- 新增/编辑模型弹窗 -->
    <el-dialog
      v-model="modelFormVisible"
      :title="modelFormMode === 'add' ? '添加模型' : '编辑模型'"
      width="480px"
      :close-on-click-modal="false"
      destroy-on-close
      @closed="resetModelForm"
    >
      <el-form
        ref="modelFormRef"
        :model="modelForm"
        :rules="modelFormRules"
        label-width="110px"
        style="margin-top: 8px"
        @submit.prevent
      >
        <el-form-item v-if="modelFormMode === 'add' && remoteModelOptions.length > 0" label="从远程选择">
          <el-select
            placeholder="选择已发现的模型（可选）"
            style="width: 100%"
            :loading="fetchingRemote"
            clearable
            filterable
            @change="onRemoteModelSelect"
          >
            <el-option
              v-for="id in remoteModelOptions"
              :key="id"
              :label="id"
              :value="id"
            />
          </el-select>
          <div style="font-size:12px;color:#999;margin-top:4px">选择后自动填入下方字段，仍可手动修改</div>
        </el-form-item>
        <el-form-item v-if="modelFormMode === 'add' && fetchingRemote" label="远程模型">
          <span style="font-size:12px;color:#999">正在拉取远程模型列表...</span>
        </el-form-item>
        <el-form-item label="模型名称" prop="name">
          <el-input v-model="modelForm.name" placeholder="如 GPT-4o" />
        </el-form-item>
        <el-form-item label="模型 ID" prop="modelId">
          <el-input v-model="modelForm.modelId" placeholder="如 gpt-4o" />
          <div style="font-size:12px;color:#999;margin-top:4px">调用 API 时实际传递的标识</div>
        </el-form-item>
        <el-form-item label="上下文大小" prop="contextSize">
          <el-input-number v-model="modelForm.contextSize" :min="1" :max="2000000" :step="1024" style="width:180px" />
          <span style="margin-left:8px;font-size:12px;color:#999">Token</span>
        </el-form-item>
        <el-form-item v-if="modelFormMode === 'edit'" label="状态">
          <el-switch
            v-model="modelForm.enabled"
            :active-value="1"
            :inactive-value="0"
            active-text="启用"
            inactive-text="禁用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="modelSubmitting" @click="onModelSubmit">
          {{ modelFormMode === 'add' ? '确认' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import HifyTable from '@/components/base/HifyTable.vue'
import HifyFormDialog from '@/components/base/HifyFormDialog.vue'
import { useConfirm } from '@/composables/useConfirm'
import { notifySuccess } from '@/utils/notify'
import type { TableColumn } from '@/components/base/HifyTable.vue'
import {
  getProviderList,
  createProvider,
  updateProvider,
  deleteProvider,
  testConnection,
  listModels,
  addModel,
  updateModel,
  deleteModel,
  fetchRemoteModels,
} from '@/api/provider'
import type { ProviderVO, HealthStatus, ModelConfig } from '@/api/provider'

const providerTypes = [
  { label: 'OpenAI',            value: 'OPENAI' },
  { label: 'Anthropic (Claude)', value: 'ANTHROPIC' },
  { label: 'Google Gemini',     value: 'GEMINI' },
  { label: 'Azure OpenAI',      value: 'AZURE_OPENAI' },
  { label: 'Ollama',            value: 'OLLAMA' },
  { label: 'OpenAI Compatible', value: 'OPENAI_COMPATIBLE' },
]

// ── API ────────────────────────────────────────────────────
const fetchProviders = async ({ page, pageSize }: { page: number; pageSize: number }) => {
  const res = await getProviderList({ page, pageSize })
  return { list: res.list as unknown as Record<string, unknown>[], total: res.total }
}

// ── 健康状态工具 ───────────────────────────────────────────
const healthTagType = (status: HealthStatus) => {
  const map: Record<HealthStatus, 'success' | 'danger' | 'warning' | 'info'> = {
    UP: 'success', DOWN: 'danger', DEGRADED: 'warning', UNKNOWN: 'info',
  }
  return map[status] ?? 'info'
}
const healthLabel = (status: HealthStatus) => {
  const map: Record<HealthStatus, string> = {
    UP: '正常', DOWN: '故障', DEGRADED: '降级', UNKNOWN: '未知',
  }
  return map[status] ?? status
}
const enabledModelCount = (row: ProviderVO) =>
  row.models?.filter(m => m.enabled).length ?? 0

// ── 表格列配置 ─────────────────────────────────────────────
const columns = computed<TableColumn[]>(() => [
  { label: '名称',     prop: 'name',     minWidth: 140 },
  { label: '类型',     prop: 'type',     width: 140 },
  { label: 'Base URL', prop: 'baseUrl',  minWidth: 200, hideOnNarrow: true },
  { label: '状态',     slot: 'status',   width: 80 },
  { label: '健康',     slot: 'health',   width: 120 },
  { label: '模型数',   slot: 'models',   width: 80 },
  { label: '操作',     slot: 'action',   width: 160 },
])

// ── 弹窗 ───────────────────────────────────────────────────
const tableRef = ref<InstanceType<typeof HifyTable>>()
const dialogRef = ref<InstanceType<typeof HifyFormDialog>>()

const rules: FormRules = {
  name:    [{ required: true, message: '请输入名称',      trigger: 'blur' }],
  type:    [{ required: true, message: '请选择类型',      trigger: 'change' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }],
}

const dialogTitle = computed(() => '提供商信息')

const onSubmit = async (data: Record<string, unknown>, mode: 'add' | 'edit') => {
  const apiKey = (data.apiKey as string) || ''
  if (mode === 'add') {
    await createProvider({
      name: data.name as string,
      type: data.type as any,
      baseUrl: data.baseUrl as string,
      description: data.description as string | undefined,
      authConfig: apiKey ? { api_key: apiKey } : {},
    })
  } else {
    const updateData: any = {
      name: data.name,
      baseUrl: data.baseUrl,
      description: data.description,
    }
    if (apiKey) updateData.authConfig = { api_key: apiKey }
    await updateProvider(data.id as number, updateData)
  }
  notifySuccess(mode === 'add' ? '新增成功' : '保存成功')
  dialogRef.value?.close()
  tableRef.value?.refresh()
}

// ── 删除 ───────────────────────────────────────────────────
const { confirm } = useConfirm()

const onDelete = async (row: ProviderVO) => {
  await confirm(
    `确定删除提供商「${row.name}」吗？`,
    async () => { await deleteProvider(row.id) },
    '删除成功'
  )
  tableRef.value?.refresh()
}

// ── 连通性测试 ─────────────────────────────────────────────
const testingId = ref<number | null>(null)
// 缓存每个 provider 测试时拉到的远程模型列表  providerId → modelId[]
const remoteModelsCache = ref<Record<number, string[]>>({})

const onTestConnection = async (row: ProviderVO) => {
  testingId.value = row.id
  try {
    const result = await testConnection(row.id)
    if (result.success) {
      ElMessage.success(`连接成功，延迟 ${result.latencyMs}ms，发现 ${result.modelCount} 个模型`)
      // 顺便拉取模型列表缓存，打开添加弹窗时可直接选
      try {
        const models = await fetchRemoteModels(row.id)
        remoteModelsCache.value[row.id] = models
      } catch { /* 拉取失败不影响测试结果 */ }
    } else {
      ElMessage.error(`连接失败：${result.errorMessage}`)
    }
  } finally {
    testingId.value = null
  }
}

// ── 模型管理 ───────────────────────────────────────────────
const modelDialogVisible = ref(false)
const currentProvider = ref<ProviderVO | null>(null)
const modelList = ref<ModelConfig[]>([])
// 当前 provider 的远程可选模型 ID 列表
const remoteModelOptions = ref<string[]>([])
const fetchingRemote = ref(false)

const openModelDialog = async (row: ProviderVO) => {
  currentProvider.value = row
  modelDialogVisible.value = true
  // 同时加载已配置模型 + 远程模型列表
  try {
    modelList.value = await listModels(row.id)
  } catch {
    ElMessage.error('加载模型列表失败')
  }
  // 从缓存或远程拉取可选模型
  if (remoteModelsCache.value[row.id]) {
    remoteModelOptions.value = remoteModelsCache.value[row.id]
  } else {
    fetchingRemote.value = true
    try {
      const models = await fetchRemoteModels(row.id)
      remoteModelsCache.value[row.id] = models
      remoteModelOptions.value = models
    } catch {
      remoteModelOptions.value = []
    } finally {
      fetchingRemote.value = false
    }
  }
}

// 模型表单
const modelFormVisible = ref(false)
const modelFormMode = ref<'add' | 'edit'>('add')
const modelSubmitting = ref(false)
const modelFormRef = ref<FormInstance>()
const editingModelId = ref<number | null>(null)

const defaultModelForm = () => ({ name: '', modelId: '', contextSize: 4096, enabled: 1 })
const modelForm = ref(defaultModelForm())

const modelFormRules: FormRules = {
  name:    [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  modelId: [{ required: true, message: '请输入模型 ID', trigger: 'blur' }],
}

/** 从远程列表选中一个模型 ID，自动填入表单 */
const onRemoteModelSelect = (modelId: string) => {
  modelForm.value.modelId = modelId
  // 如果名称还是空的，顺便填上
  if (!modelForm.value.name) {
    modelForm.value.name = modelId
  }
}

const resetModelForm = () => {
  modelForm.value = defaultModelForm()
  editingModelId.value = null
  modelFormRef.value?.clearValidate()
}

const openAddModel = () => {
  modelFormMode.value = 'add'
  modelFormVisible.value = true
}

const openEditModel = (row: ModelConfig) => {
  modelFormMode.value = 'edit'
  editingModelId.value = row.id
  modelForm.value = {
    name: row.name,
    modelId: row.modelId,
    contextSize: row.contextSize ?? 4096,
    enabled: row.enabled ?? 1,
  }
  modelFormVisible.value = true
}

const onModelSubmit = async () => {
  await modelFormRef.value?.validate()
  if (!currentProvider.value) return
  modelSubmitting.value = true
  try {
    if (modelFormMode.value === 'add') {
      const created = await addModel(currentProvider.value.id, {
        name: modelForm.value.name,
        modelId: modelForm.value.modelId,
        contextSize: modelForm.value.contextSize,
      })
      modelList.value.push(created)
      notifySuccess('添加成功')
    } else {
      const updated = await updateModel(currentProvider.value.id, editingModelId.value!, {
        name: modelForm.value.name,
        modelId: modelForm.value.modelId,
        contextSize: modelForm.value.contextSize,
        enabled: modelForm.value.enabled,
      })
      const idx = modelList.value.findIndex(m => m.id === editingModelId.value)
      if (idx !== -1) modelList.value[idx] = updated
      notifySuccess('保存成功')
    }
    modelFormVisible.value = false
    tableRef.value?.refresh()
  } finally {
    modelSubmitting.value = false
  }
}

const onDeleteModel = async (row: ModelConfig) => {
  if (!currentProvider.value) return
  await confirm(
    `确定删除模型「${row.name}」吗？`,
    async () => {
      await deleteModel(currentProvider.value!.id, row.id)
      modelList.value = modelList.value.filter(m => m.id !== row.id)
    },
    '删除成功'
  )
  tableRef.value?.refresh()
}
</script>

<style scoped>
.page-header { margin-bottom: 16px; }

.provider-card :deep(.el-table th.el-table__cell) {
  background-color: var(--color-bg-page);
}
.provider-card :deep(.el-table__row:hover > td) {
  background-color: var(--color-bg-hover) !important;
}
.provider-card :deep(.hify-table-pagination) {
  padding-top: 12px;
  border-top: 1px solid var(--color-border-default);
  justify-content: flex-end;
}

.latency-ms {
  margin-left: 6px;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.model-count-link {
  color: var(--color-primary);
  cursor: pointer;
  font-size: 13px;
}
.model-count-link:hover { text-decoration: underline; }

.text-muted {
  color: var(--color-text-tertiary);
  font-size: 13px;
}

.model-list-popup { padding: 4px 0; }
.model-list-title {
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-bottom: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--color-border-default);
}
.model-list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 13px;
}
</style>
