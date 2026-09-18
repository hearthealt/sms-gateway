<template>
  <div class="apikey-management">
    <!-- Header Actions -->
    <el-card shadow="never" class="action-card">
      <div class="action-bar">
        <div class="action-left">
          <span class="action-title">API 密钥</span>
          <el-tag type="info" effect="plain" size="small">{{ keys.length }} 个密钥</el-tag>
        </div>
        <el-button type="primary" @click="showIssueDialog">
          <el-icon><Plus /></el-icon> 签发密钥
        </el-button>
      </div>
    </el-card>

    <!-- Key Table -->
    <el-card shadow="never" class="table-card">
      <el-table :data="keys" stripe style="width: 100%" v-loading="loading" empty-text="暂无密钥，点击上方按钮签发">
        <el-table-column prop="name" label="用途备注" min-width="150" show-overflow-tooltip />
        <el-table-column label="密钥" min-width="365">
          <template #default="{ row }">
            <div class="key-cell">
              <span class="key-text">{{ revealed[row.id] ? row.apiKey : maskKey(row.apiKey) }}</span>
              <el-button text size="small" :title="revealed[row.id] ? '隐藏' : '显示'" @click="toggleReveal(row.id)">
                <el-icon><Hide v-if="revealed[row.id]" /><View v-else /></el-icon>
              </el-button>
              <el-button text size="small" title="复制完整密钥" @click="copyKey(row.apiKey)">
                <el-icon><CopyDocument /></el-icon>
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="togglingId === row.id"
              @click.stop
              @change="(val: boolean) => handleToggle(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="过期时间" width="205">
          <template #default="{ row }">
            <span v-if="!row.expiresAt" class="no-code">永久</span>
            <template v-else-if="isExpired(row.expiresAt)">
              <span class="expired-text">{{ formatTime(row.expiresAt) }}</span>
              <el-tag type="danger" size="small" effect="plain" class="expired-tag">已过期</el-tag>
            </template>
            <span v-else class="time-text">{{ formatTime(row.expiresAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最后使用" width="175">
          <template #default="{ row }">
            <span v-if="row.lastUsedAt" class="time-text">{{ formatTime(row.lastUsedAt) }}</span>
            <span v-else class="no-code">从未使用</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-popconfirm
              title="删除后使用该密钥的调用方会立即 401，确定删除吗？"
              confirm-button-text="确定删除"
              width="260"
              @confirm="handleDelete(row)"
            >
              <template #reference>
                <el-button text type="danger" size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Issue Dialog -->
    <el-dialog
      v-model="dialogVisible"
      title="签发密钥"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px" label-position="left">
        <el-form-item label="用途备注" prop="name">
          <el-input v-model="form.name" placeholder="如：XX 系统登录取码" maxlength="100" show-word-limit />
          <div class="form-tip">用于识别是哪个调用方，方便日后停用或轮换</div>
        </el-form-item>
        <el-form-item label="过期时间" prop="expiresAt">
          <el-date-picker
            v-model="form.expiresAt"
            type="datetime"
            placeholder="不填表示永久有效"
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DD[T]HH:mm:ss"
            style="width: 100%"
            clearable
          />
          <div class="form-tip">留空则永久有效，直到手动禁用或删除</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleIssue">确定签发</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { Plus, View, Hide, CopyDocument } from '@element-plus/icons-vue'
import { getApiKeyList, createApiKey, deleteApiKey, toggleApiKey } from '../api/apikey'
import { copyText } from '../utils/clipboard'
import type { ApiKey } from '../types'
import type { FormInstance, FormRules } from 'element-plus'

const keys = ref<ApiKey[]>([])
const loading = ref(false)
const saving = ref(false)
const togglingId = ref<number | null>(null)
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()

/**
 * 哪几行的密钥已展开。用普通对象按下标存，不用 Set ——
 * 对象是 ref 深度响应式的，Set 的增删要依赖集合类型拦截，这里没必要冒险。
 */
const revealed = ref<Record<number, boolean>>({})

const defaultForm = {
  name: '',
  expiresAt: null as string | null,
}

const form = reactive({ ...defaultForm })

const formRules: FormRules = {
  name: [{ required: true, message: '请输入用途备注', trigger: 'blur' }],
}

/** sk-3f2a1b4c5d6e7f809a1b2c3d4e5f6071 → sk-****6071 */
function maskKey(key: string): string {
  if (key.length <= 8) return '********'
  return `${key.slice(0, 3)}****${key.slice(-4)}`
}

function toggleReveal(id: number) {
  revealed.value[id] = !revealed.value[id]
}

function formatTime(t: string) {
  return dayjs(t).format('YYYY-MM-DD HH:mm:ss')
}

function isExpired(t: string): boolean {
  return dayjs(t).isBefore(dayjs())
}

async function copyKey(key: string) {
  if (await copyText(key)) {
    ElMessage.success('密钥已复制')
  } else {
    ElMessage.error('复制失败')
  }
}

async function loadKeys() {
  loading.value = true
  try {
    keys.value = await getApiKeyList()
  } catch (e) {
    console.error('Failed to load API keys', e)
  } finally {
    loading.value = false
  }
}

function showIssueDialog() {
  Object.assign(form, defaultForm)
  dialogVisible.value = true
}

async function handleIssue() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const created = await createApiKey({
      name: form.name,
      // 选择器清空后是空串，后端要的是 null（表示不过期）
      expiresAt: form.expiresAt || null,
    })
    ElMessage.success('密钥已签发')
    dialogVisible.value = false
    await loadKeys()
    // 新签发的这条直接展开，省得管理员再去点一次「显示」才能复制
    revealed.value[created.id] = true
  } catch (e) {
    // 失败提示由 http 响应拦截器统一处理
    console.error(e)
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: ApiKey) {
  try {
    await deleteApiKey(row.id)
    ElMessage.success('密钥已删除')
    await loadKeys()
  } catch (e) {
    console.error(e)
  }
}

async function handleToggle(row: ApiKey, val: boolean) {
  togglingId.value = row.id
  try {
    await toggleApiKey(row.id, val)
    row.enabled = val
    ElMessage.success(val ? '密钥已启用' : '密钥已禁用')
  } catch (e) {
    // 失败提示由 http 响应拦截器统一给出，这里不再重复弹一条
    console.error(e)
  } finally {
    togglingId.value = null
  }
}

onMounted(loadKeys)
</script>

<style scoped>
.action-card {
  margin-bottom: 16px;
  border-radius: var(--border-radius-base);
  border: 1px solid var(--color-border-light);
}
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.action-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.action-title {
  font-weight: 600;
  font-size: 16px;
  color: var(--color-text-primary);
}

.table-card {
  border-radius: var(--border-radius-base);
  border: 1px solid var(--color-border-light);
}

:deep(.el-table th.el-table__cell) {
  background: var(--color-bg) !important;
  color: var(--color-text-regular);
  font-weight: 600;
}

/*
 * 行高统一：数据单元格一律不换行，超出部分截断。
 * 与 SmsList.vue / DeviceList.vue 同一策略 —— 不这么做的话，
 * 密钥展开后这行会被撑成两行，表格参差。
 */
:deep(.el-table__body td.el-table__cell > .cell) {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 密钥用等宽字体，展开时才好逐位核对 */
.key-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}
.key-text {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  color: var(--color-text-primary);
}
.key-cell .el-button + .el-button {
  margin-left: 0;
}

.time-text { font-size: 13px; color: var(--color-text-secondary); }
.expired-text { font-size: 13px; color: var(--color-danger); }
.expired-tag { margin-left: 6px; }
.no-code { color: var(--color-text-placeholder); }

.form-tip {
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-top: 4px;
  line-height: 1.4;
}

:deep(.el-dialog__body) {
  padding-top: 20px;
}
</style>
