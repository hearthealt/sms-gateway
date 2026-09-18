<template>
  <div class="rule-management">
    <!-- Header Actions -->
    <el-card shadow="never" class="action-card">
      <div class="action-bar">
        <div class="action-left">
          <span class="action-title">采集规则</span>
          <el-tag type="info" effect="plain" size="small">{{ rules.length }} 条规则</el-tag>
        </div>
        <el-button type="primary" @click="showAddDialog">
          <el-icon><Plus /></el-icon> 新增规则
        </el-button>
      </div>
    </el-card>

    <!-- Rule Table -->
    <el-card shadow="never" class="table-card">
      <el-table :data="rules" stripe style="width: 100%" v-loading="loading" empty-text="暂无规则，点击上方按钮添加">
        <el-table-column prop="ruleName" label="规则名称" min-width="140" />
        <el-table-column prop="senderPattern" label="发送方匹配" min-width="160" />
        <el-table-column prop="keywordPattern" label="关键词匹配" min-width="160" />
        <el-table-column label="动作" width="90">
          <template #default="{ row }">
            <status-badge :status="row.action" />
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="90">
          <template #default="{ row }">
            <el-tag
              :type="priorityType(row.priority)"
              size="small"
              effect="plain"
            >
              {{ row.priority }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="togglingId === row.id"
              @click.stop
              @change="(val: boolean) => handleToggle(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="160" show-overflow-tooltip prop="description" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="showEditDialog(row)">编辑</el-button>
            <el-popconfirm
              title="确定要删除此规则吗？"
              confirm-button-text="确定删除"
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

    <!-- Add / Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEditing ? '编辑规则' : '新增规则'"
      width="580px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px" label-position="left">
        <el-form-item label="规则名称" prop="ruleName">
          <el-input v-model="form.ruleName" placeholder="输入规则名称" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="发送方匹配" prop="senderPattern">
          <el-input v-model="form.senderPattern" placeholder="发送号码模式，如 95555" maxlength="255" show-word-limit />
          <div class="form-tip">支持精确匹配、LIKE模式 (%关键词%) 或正则表达式</div>
        </el-form-item>
        <el-form-item label="关键词匹配" prop="keywordPattern">
          <el-input v-model="form.keywordPattern" placeholder="内容关键词，如 验证码" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item label="匹配方式" prop="matchType">
          <el-select v-model="form.matchType" style="width: 100%">
            <el-option label="精确匹配 (EXACT)" value="EXACT" />
            <el-option label="模糊匹配 (LIKE)" value="LIKE" />
            <el-option label="正则匹配 (REGEX)" value="REGEX" />
          </el-select>
        </el-form-item>
        <el-form-item label="动作" prop="action">
          <el-radio-group v-model="form.action">
            <el-radio value="collect">采集</el-radio>
            <el-radio value="ignore">忽略</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="优先级" prop="priority">
          <el-input-number v-model="form.priority" :min="0" :max="999" style="width: 200px" />
          <div class="form-tip">数值越大优先级越高</div>
        </el-form-item>
        <el-form-item label="启用" prop="enabled">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="规则描述（可选）"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import StatusBadge from '../components/StatusBadge.vue'
import { getRuleList, createRule, updateRule, deleteRule, toggleRule } from '../api/rule'
import type { CollectRule } from '../types'
import type { FormInstance, FormRules } from 'element-plus'

const rules = ref<CollectRule[]>([])
const loading = ref(false)
const saving = ref(false)
const togglingId = ref<number | null>(null)
const dialogVisible = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const defaultForm = {
  ruleName: '',
  senderPattern: '',
  keywordPattern: '',
  matchType: 'REGEX',
  action: 'collect' as 'collect' | 'ignore',
  priority: 50,
  enabled: true,
  description: '',
}

const form = reactive({ ...defaultForm })

/*
 * 长度上限与后端 @Size、数据库列宽对齐：ruleName 100、senderPattern 255、
 * keywordPattern 255、description 500。
 * 光靠 maxlength 不够 —— 它只挡键盘输入，编辑回填或粘贴超长值照样能提交，
 * 由校验兜住才不会等到后端抛异常才发现。
 */
const formRules: FormRules = {
  ruleName: [
    { required: true, message: '请输入规则名称', trigger: 'blur' },
    { max: 100, message: '规则名称不能超过 100 个字符', trigger: 'blur' },
  ],
  senderPattern: [
    { required: true, message: '请输入发送方匹配模式', trigger: 'blur' },
    { max: 255, message: '发送方匹配不能超过 255 个字符', trigger: 'blur' },
  ],
  keywordPattern: [{ max: 255, message: '关键词匹配不能超过 255 个字符', trigger: 'blur' }],
  description: [{ max: 500, message: '描述不能超过 500 个字符', trigger: 'blur' }],
  action: [{ required: true, message: '请选择动作', trigger: 'change' }],
}

function priorityType(p: number): string {
  if (p >= 70) return 'danger'
  if (p >= 40) return 'warning'
  return 'info'
}

async function loadRules() {
  loading.value = true
  try {
    rules.value = await getRuleList()
  } catch (e) {
    console.error('Failed to load rules', e)
  } finally {
    loading.value = false
  }
}

function showAddDialog() {
  isEditing.value = false
  editingId.value = null
  Object.assign(form, defaultForm)
  dialogVisible.value = true
}

function showEditDialog(rule: CollectRule) {
  isEditing.value = true
  editingId.value = rule.id
  form.ruleName = rule.ruleName
  form.senderPattern = rule.senderPattern
  form.keywordPattern = rule.keywordPattern
  form.matchType = rule.matchType
  form.action = rule.action
  form.priority = rule.priority
  form.enabled = rule.enabled
  form.description = rule.description ?? ''
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    if (isEditing.value && editingId.value) {
      await updateRule(editingId.value, { ...form })
      ElMessage.success('规则已更新')
    } else {
      await createRule({ ...form })
      ElMessage.success('规则已创建')
    }
    dialogVisible.value = false
    await loadRules()
  } catch (e) {
    // 失败提示由 http 响应拦截器统一处理
    console.error(e)
  } finally {
    saving.value = false
  }
}

async function handleDelete(rule: CollectRule) {
  try {
    await deleteRule(rule.id)
    ElMessage.success('规则已删除')
    await loadRules()
  } catch (e) {
    console.error(e)
  }
}

async function handleToggle(rule: CollectRule, val: boolean) {
  togglingId.value = rule.id
  try {
    await toggleRule(rule.id, val)
    rule.enabled = val
    ElMessage.success(val ? '规则已启用' : '规则已禁用')
  } catch (e) {
    // 失败提示由 http 响应拦截器统一给出，这里不再重复弹一条
    console.error(e)
  } finally {
    togglingId.value = null
  }
}

onMounted(loadRules)
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
