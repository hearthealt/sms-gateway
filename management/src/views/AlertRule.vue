<template>
  <div class="alert-rule">
    <el-card shadow="never">
      <div class="action-bar">
        <div class="action-left">
          <span class="action-title">告警规则</span>
          <el-tag type="info" effect="plain" size="small">{{ rules.length }} 条规则</el-tag>
        </div>
        <el-button type="primary" @click="showAddDialog">
          <el-icon><Plus /></el-icon> 新增规则
        </el-button>
      </div>

      <!--
        没有规则时**必须显式说出来**：设备离线、渠道挂掉、远程指令没执行成功，
        这三件事在没有规则的情况下一条通知都不会发 —— 而「没配规则」和「配了但没触发」
        在界面上长得一模一样，现场会以为功能坏了。
      -->
      <div v-if="!rules.length" class="preset-block">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="还没有告警规则 —— 现在出故障不会有任何通知"
          description="设备离线、转发渠道被自动停用、远程指令执行失败，这三件事都要先有一条规则指向渠道，才会发出通知。"
        />

        <!--
          一键建三条，而不是靠建库时种子化：种子数据的 channelIds 必然为空，
          而后端会直接拒绝一条没有渠道的规则 —— 也就是说种子只能种出三条「无目标」的
          红标签。转发规则当初没有种子化，正是同一个理由。
        -->
        <div class="preset-actions">
          <el-button
            type="primary"
            plain
            :disabled="!enabledChannels.length"
            @click="openPresetDialog"
          >
            <el-icon><Plus /></el-icon> 一键创建三条默认规则
          </el-button>
          <span v-if="!enabledChannels.length" class="preset-hint">
            先到「转发渠道」建一个并启用它 —— 一条规则至少要指向一个渠道。
          </span>
        </div>
      </div>

      <!--
        **规则配好了、总开关却没开** —— 这是最容易踩的一个坑：告警走的是与转发同一条
        投递链路，而那条链路的总开关在「系统设置 → 消息转发」里，默认是关的。
        不说出来的话，现场表现就是「设备真离线了、运行日志里也有设备离线，但什么通知都没收到」。
      -->
      <el-alert
        v-if="rules.length && !notifyEnabled"
        type="error"
        :closable="false"
        show-icon
        class="switch-off-warning"
        title="「消息转发」总开关没有打开 —— 下面这些规则不会发出任何通知"
        description="告警与短信转发共用同一条投递链路。到「系统设置 → 消息转发」里打开「消息转发」开关，这些规则才会生效。"
      />

      <el-alert
        v-else-if="!channels.length"
        type="warning"
        :closable="false"
        show-icon
        title="还没有转发渠道"
        description="规则必须指向至少一个渠道，先去「转发渠道」页建一个。"
      />

      <el-table :data="rules" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="ruleName" label="规则名称" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-primary">{{ row.ruleName }}</span>
          </template>
        </el-table-column>

        <!-- 类型为 null 的规则对所有告警生效，用 info 标签与具体类型区分开 -->
        <el-table-column label="告警类型" width="180">
          <template #default="{ row }">
            <el-tag :type="row.alertType ? 'warning' : 'info'" size="small" effect="plain">
              {{ row.alertTypeLabel }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="限定设备" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.deviceId || '不限' }}</template>
        </el-table-column>

        <!--
          通知到哪些渠道。与转发规则页同一条取舍：显示名字而不是 id 列表，
          停用的渠道带「（已停用）」后缀（规则配着它，但它实际不会投递），
          目标全没了时给一个红标签 —— 空的单元格比一条假装的规则危险。
        -->
        <el-table-column label="通知到" min-width="180">
          <template #default="{ row }">
            <template v-if="row.targetChannelNames.length">
              <el-tag
                v-for="(name, i) in row.targetChannelNames"
                :key="i"
                size="small"
                effect="plain"
                class="target-tag"
              >
                {{ name }}
              </el-tag>
            </template>
            <el-tag v-else type="danger" size="small" effect="plain">无目标</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="启用" width="76" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="togglingId === row.id"
              @click.stop
              @change="(val: boolean) => handleToggle(row, val)"
            />
          </template>
        </el-table-column>

        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="showEditDialog(row)">编辑</el-button>
            <el-popconfirm
              title="确定要删除此规则吗？"
              confirm-button-text="确定删除"
              @confirm="handleDelete(row)"
            >
              <template #reference>
                <el-button link type="danger" size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <!--
        去重与静默时段在「系统设置」页，不在这个页面。提示一次就够 ——
        不提示的话，配完规则的人会以为「怎么还在半夜被叫醒」是规则没生效。
      -->
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="footnote"
        title="同一台设备反复上下线不会重复刷屏"
        description="同类告警有冷却窗口，且设备恢复在线后才会重新计一次。静默时段、冷却时长都在「系统设置 → 故障告警」里改。"
      />
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑告警规则' : '新增告警规则'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px">
        <el-form-item label="规则名称" required>
          <el-input v-model="form.ruleName" placeholder="如：设备离线通知运维群" maxlength="100" />
        </el-form-item>

        <el-form-item label="告警类型">
          <!-- 留空 = 不限类型。下拉里显式放一个「全部类型」选项，别让人猜能不能不选 -->
          <el-select v-model="form.alertType" style="width: 100%">
            <el-option label="全部类型" :value="null" />
            <el-option v-for="t in types" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <span class="form-hint block">
            设备离线＝心跳断了；转发渠道被自动停用＝连续失败达阈值；
            远程指令失败＝设备执行不了（含一直没回执而过期）。
          </span>
        </el-form-item>

        <el-form-item label="通知到" required>
          <el-select v-model="form.channelIds" multiple style="width: 100%" placeholder="至少选一个渠道">
            <el-option
              v-for="c in channels"
              :key="c.id"
              :label="`${c.name}${c.enabled ? '' : '（已停用）'}`"
              :value="c.id"
            />
          </el-select>
          <span class="form-hint block">
            一条告警会<strong>同时</strong>发给所有命中的渠道（并集，不像采集规则那样首条命中即定论）。
          </span>
        </el-form-item>

        <el-divider />

        <el-form-item label="限定设备">
          <el-input v-model="form.deviceId" placeholder="留空 = 不限设备。要填就填完整设备号" />
          <span class="form-hint block">
            设备号是<strong>精确相等</strong>的，不是模糊匹配 —— 它是标识符，允许模糊只会让人误以为可以这么用。
          </span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 一键创建三条：只有「通知到」需要选，其余字段都是写死的默认值 -->
    <el-dialog
      v-model="presetDialogVisible"
      title="创建三条默认规则"
      width="520px"
      :close-on-click-modal="false"
    >
      <p class="preset-dialog-hint">
        会创建「设备离线」「转发渠道被自动停用」「远程指令失败」三条规则，都指向下面选中的渠道。
        已经存在同名的规则会跳过，不会重复创建。
      </p>

      <el-form label-width="80px">
        <el-form-item label="通知到" required>
          <el-select v-model="presetChannelIds" multiple style="width: 100%" placeholder="至少选一个渠道">
            <el-option
              v-for="c in channels"
              :key="c.id"
              :label="`${c.name}${c.enabled ? '' : '（已停用）'}`"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="presetDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingPreset" @click="createPresets">
          创建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useAdminEvents } from '../composables/useAdminEvents'
import { ElMessage } from 'element-plus'
import {
  createAlertRule,
  deleteAlertRule,
  getAlertRuleList,
  getAlertTypes,
  toggleAlertRule,
  updateAlertRule,
} from '../api/alert'
import { getChannelList } from '../api/notify'
import { getSysConfigList } from '../api/sysconfig'
import type { AlertRule, AlertTypeOption, NotifyChannel } from '../types'

const rules = ref<AlertRule[]>([])
const channels = ref<NotifyChannel[]>([])
const types = ref<AlertTypeOption[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref<AlertRule | null>(null)
const togglingId = ref<number | null>(null)

/** 「一键创建三条」的弹窗状态与它选中的渠道。 */
const presetDialogVisible = ref(false)
const presetChannelIds = ref<number[]>([])
const creatingPreset = ref(false)

/** 停用的渠道配上去也不会投递（列表上会显示成「已停用」），所以只默认勾启用的。 */
const enabledChannels = computed(() => channels.value.filter((c) => c.enabled))

/**
 * 转发总开关。**告警走的是与转发同一条投递链路**，所以这个开关关着时，
 * 下面那些规则一条都不会发出通知 —— 而那个开关在另一个页面（系统设置）上，
 * 不提示的话没人会想到这两件事是连着的。
 *
 * 从后端取而不是读死一个常量：默认值是 `false`（`SysConfigKey.NOTIFY_ENABLED`），
 * 而管理员改过之后这里要跟着变。
 */
const notifyEnabled = ref(true)

/**
 * 三条默认规则。类型枚举值与后端一致，规则名就是它们的用途 ——
 * 名字由前端给（后端的 AlertType 是**类型**，不是规则名，一个类型可以配多条规则）。
 */
const PRESETS: { ruleName: string; alertType: string }[] = [
  { ruleName: '设备离线', alertType: 'DEVICE_OFFLINE' },
  { ruleName: '转发渠道被自动停用', alertType: 'CHANNEL_AUTO_DISABLED' },
  { ruleName: '远程指令失败', alertType: 'DEVICE_COMMAND_FAILED' },
]

const form = reactive({
  ruleName: '',
  alertType: null as string | null,
  deviceId: '',
  channelIds: [] as number[],
})

async function loadData() {
  loading.value = true
  try {
    const [ruleList, channelList, typeList, configs] = await Promise.all([
      getAlertRuleList(),
      getChannelList(),
      getAlertTypes(),
      getSysConfigList(),
    ])
    rules.value = ruleList
    channels.value = channelList
    types.value = typeList

    // 总开关从配置里读。取不到时**按「开着」处理**：那只会少一条提示，
    // 而默认成「关着」会让每个打开这一页的人都看到一条可能是假的警告。
    const notify = configs.find((c) => c.key === 'notify.enabled')
    notifyEnabled.value = notify ? notify.value === 'true' : true
  } finally {
    loading.value = false
  }
}

onMounted(loadData)

/*
 * 这一页**没有服务端自变源**（变化只来自管理员自己，而本地操作已是即时更新的），
 * 所以订阅只覆盖「另一个管理员改了」。与转发规则页同一个口径，别把它当成实时性承诺。
 *
 * **弹窗开着时跳过**：编辑中的表单不能被静默覆盖。
 *
 * 事件名照旧不窄分支 —— 本页关心的东西只有一个列表，整体重拉就够。
 */
useAdminEvents(() => {
  if (dialogVisible.value) return
  loadData()
})

function openPresetDialog() {
  presetChannelIds.value = enabledChannels.value.map((c) => c.id)
  presetDialogVisible.value = true
}

/**
 * 建三条（已存在的同名规则跳过）。
 *
 * 逐条顺序发请求而不是并发：三条都指向同一批渠道，没必要抢；而且顺序发的话，
 * 失败时能说清「建到第几条断的」，用户刷新一下就知道还差哪条。
 */
async function createPresets() {
  if (!presetChannelIds.value.length) {
    ElMessage.warning('至少要选一个渠道')
    return
  }

  creatingPreset.value = true
  try {
    const existing = new Set(rules.value.map((r) => r.ruleName))
    const todo = PRESETS.filter((p) => !existing.has(p.ruleName))
    if (!todo.length) {
      ElMessage.info('这三条规则都已经存在了')
      presetDialogVisible.value = false
      return
    }

    for (const preset of todo) {
      await createAlertRule({
        ruleName: preset.ruleName,
        alertType: preset.alertType,
        deviceId: '',
        enabled: true,
        channelIds: presetChannelIds.value,
      })
    }
    ElMessage.success(`已创建 ${todo.length} 条规则`)
    presetDialogVisible.value = false
    await loadData()
  } catch {
    // 拦截器已提示；已经建好的那几条不会回滚（列表刷新后看得见），
    // 所以这里不改本地状态、只让用户自己看一眼
    await loadData()
  } finally {
    creatingPreset.value = false
  }
}

function showAddDialog() {
  editing.value = null
  form.ruleName = ''
  form.alertType = null
  form.deviceId = ''
  form.channelIds = []
  dialogVisible.value = true
}

function showEditDialog(row: AlertRule) {
  editing.value = row
  form.ruleName = row.ruleName
  form.alertType = row.alertType
  form.deviceId = row.deviceId ?? ''
  form.channelIds = [...row.channelIds]
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!form.ruleName.trim()) {
    ElMessage.warning('请填写规则名称')
    return
  }
  if (!form.channelIds.length) {
    ElMessage.warning('至少要选一个渠道')
    return
  }

  const payload = {
    ruleName: form.ruleName.trim(),
    alertType: form.alertType,
    deviceId: form.deviceId,
    enabled: editing.value?.enabled ?? true,
    channelIds: form.channelIds,
  }

  saving.value = true
  try {
    if (editing.value) {
      await updateAlertRule(editing.value.id, payload)
      ElMessage.success('规则已更新')
    } else {
      await createAlertRule(payload)
      ElMessage.success('规则已创建')
    }
    dialogVisible.value = false
    await loadData()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function handleToggle(row: AlertRule, enabled: boolean) {
  togglingId.value = row.id
  try {
    await toggleAlertRule(row.id, enabled)
    row.enabled = enabled
    ElMessage.success(enabled ? '规则已启用' : '规则已停用')
  } catch {
    // 后端会拒绝「没有任何渠道的规则启用」，提示由拦截器弹出，这里不改本地状态
  } finally {
    togglingId.value = null
  }
}

async function handleDelete(row: AlertRule) {
  try {
    await deleteAlertRule(row.id)
    ElMessage.success('规则已删除')
    await loadData()
  } catch {
    // 拦截器已提示
  }
}
</script>

<style scoped>
/*
 * 标题行与表格之间用一条细分隔线分区 —— 与其余页面（采集规则 / 投递记录 /
 * 设备列表 / 短信记录 / API 密钥 / 运行日志 / 接口文档 / 系统设置）完全一致。
 * 圆角与边框由 App.vue 的 .el-card 全局规则给，这里不重复声明。
 */
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 20px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--color-border-light);
}
.action-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.action-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-primary);
}
.cell-primary {
  font-weight: 500;
  color: var(--color-text-primary);
}
.target-tag {
  margin: 2px 4px 2px 0;
}
.form-hint {
  font-size: 12px;
  color: var(--color-text-secondary);
  line-height: 1.6;
}
.form-hint.block {
  display: block;
  margin-top: 4px;
}
.footnote {
  margin-top: 16px;
}

/* 总开关没开那条警告：与空态那块用同一个间距，免得两个提示叠在一起时贴住表格 */
.switch-off-warning {
  margin-bottom: 16px;
}

/* 空态里那块「还没有规则 + 一键创建」：按钮与提示跟在警告条下方，与它成一组 */
.preset-block {
  margin-bottom: 16px;
}
.preset-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 12px;
}
.preset-hint {
  margin: 0;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.preset-dialog-hint {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text-secondary);
}
</style>
