<template>
  <el-card shadow="never" class="detail-card">
    <template #header>
      <div class="card-header">
        <span class="card-section-title">远程指令</span>
        <el-button
          text
          type="primary"
          :disabled="loading"
          @click="reload"
        >
          刷新
        </el-button>
      </div>
    </template>

    <!--
      送达延迟是**常驻说明**（每一刻都成立），不是每一次都要读的警告 ——
      所以是一行灰字而不是一条 el-alert。同理，启停按钮的判据也写在这里：
      控制台并不知道那台手机的网关到底在不在跑，只能从「它在不在线」推。
      不说清这一点，管理员会在按钮写着「停止」而设备其实早已停下时以为界面坏了。
    -->
    <p class="command-hint">
      指令搭心跳下发：设备在线时约 30 秒生效，网关已停止时最长 15 分钟，1 小时后过期。<br />
      启停按钮按<b>设备当前是否在线</b>判断 —— 在线说明网关在跑；离线则按「启动」显示
      （也可能是它连不上服务器，那台设备本来就没在上报）。
    </p>

    <div class="command-actions">
      <!--
        一个主按钮而不是并列两个：启停是同一件事的两个方向，各摆一个按钮会让
        「哪个才是现在该点的」变成每次都要想一下的问题。
      -->
      <el-button
        :type="gatewayAction === 'STOP_GATEWAY' ? 'warning' : 'primary'"
        :loading="submitting === gatewayAction"
        :disabled="submitting !== null"
        @click="issue(gatewayAction)"
      >
        {{ gatewayAction === 'STOP_GATEWAY' ? '停止网关' : '启动网关' }}
      </el-button>

      <el-button
        :loading="submitting === 'REUPLOAD'"
        :disabled="submitting !== null"
        @click="issue('REUPLOAD')"
      >
        触发重传
      </el-button>

      <!--
        低频与破坏性的三项收进「更多」：它们各自有后果要读（清理会删记录、重新注册
        会改写设备信息），排在主按钮旁边只会让每一次操作都要重新辨认一遍。
      -->
      <el-dropdown :disabled="submitting !== null" @command="onMoreCommand">
        <el-button :disabled="submitting !== null">
          更多<el-icon class="dropdown-arrow"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="SET_PHONE">修改本机号码</el-dropdown-item>
            <el-dropdown-item command="CLEAR_UPLOADED">清理已上传记录</el-dropdown-item>
            <el-dropdown-item command="RE_REGISTER" divided>重新注册</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <el-table
      :data="commands"
      stripe
      style="width: 100%"
      v-loading="loading"
      empty-text="还没有下发过指令"
    >
      <el-table-column label="类型" width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.typeLabel }}</span>
          <span v-if="row.argument" class="command-argument">{{ row.argument }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small" effect="plain">
            {{ row.statusLabel }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="issuedBy" label="签发人" width="110" />
      <el-table-column label="下发时间" width="160">
        <template #default="{ row }">
          <span class="time-text">{{ formatTime(row.sentAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="回执" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <!--
            回执时间与说明合成一列：它们回答的是同一个问题（设备那边怎么了），
            各占一列会让表格多出一格空白，而这一页本来就不宽。
            一直没回执时把次数显出来 —— > 1 是「设备一直没回」的直接证据。
          -->
          <span v-if="row.ackedAt" class="time-text">{{ formatTime(row.ackedAt) }}</span>
          <span v-if="row.resultDetail"> {{ row.resultDetail }}</span>
          <span v-else-if="!row.ackedAt && row.attempts > 1" class="time-text">
            已下发 {{ row.attempts }} 次，尚未回执
          </span>
          <span v-else-if="!row.ackedAt" class="no-code">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" align="center">
        <template #default="{ row }">
          <!-- 终态（已执行/失败/已过期/已撤销）服务端会拒绝撤销，给个点了报错的按钮不如不给 -->
          <el-button
            v-if="row.status === 'PENDING' || row.status === 'SENT'"
            link
            type="danger"
            size="small"
            @click="cancel(row)"
          >
            撤销
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { cancelDeviceCommand, getDeviceCommands, issueDeviceCommand } from '../api/device'
import type { DeviceCommand, DeviceCommandStatus, DeviceCommandType } from '../types'

const props = defineProps<{
  deviceId: string
  /** 设备是否在线（来自设备详情）。启停按钮按它决定显示哪一个方向。 */
  online: boolean
}>()

const commands = ref<DeviceCommand[]>([])
const loading = ref(false)
/** 正在签发的类型；用于给对应的那个按钮单独显示 loading。 */
const submitting = ref<DeviceCommandType | null>(null)

const PAGE_SIZE = 10

/**
 * 主按钮指向哪个方向。
 *
 * 控制台并不知道那台手机的网关到底在不在跑 —— 设备状态只有「在线/离线/已禁用」，
 * 而「离线」既可能是停了网关，也可能是它连不上服务器。在线则一定说明它在跑
 * （心跳是服务发的）。所以只能这样推，并把判据写给用户看（见模板里那行提示）。
 */
const gatewayAction = computed<DeviceCommandType>(() =>
  props.online ? 'STOP_GATEWAY' : 'START_GATEWAY'
)

function formatTime(t: string | null) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-'
}

/**
 * 状态 → 标签颜色。
 *
 * SENT 用 warning 而不是 success：它**不是**「已送达」的终态，而是一个会被长期停留的
 * 状态（回执丢了、设备离线都停在这里，服务端会重复下发直到回执或过期）。显示成绿色
 * 会让人以为设备照做了。
 */
function statusTagType(status: DeviceCommandStatus) {
  switch (status) {
    case 'ACKED':
      return 'success'
    case 'SENT':
      return 'warning'
    case 'FAILED':
      return 'danger'
    case 'EXPIRED':
      return 'danger'
    case 'CANCELLED':
      return 'info'
    default:
      return 'info'
  }
}

async function reload() {
  loading.value = true
  try {
    const res = await getDeviceCommands(props.deviceId, { page: 1, pageSize: PAGE_SIZE })
    commands.value = res.records
  } catch (e) {
    console.error('Failed to load device commands', e)
  } finally {
    loading.value = false
  }
}

/**
 * 二次确认框里的后果说明。
 *
 * 每一条都写「点下去之后那台手机会发生什么、以及在哪儿能看出来」—— 这些动作都在
 * 别人手上的设备上生效，不写清楚的话，现场只能靠猜。与「删设备」那个确认框
 * 会写明「会一起删掉多少条短信」是同一种做法。
 *
 * 文案一律纯文本，**不用 markdown 的星号加粗**：ElMessageBox 用纯字符串时不会解析它，
 * 页面上会原样显示两个星号。
 */
function confirmText(type: DeviceCommandType): { title: string; body: string } {
  switch (type) {
    case 'START_GATEWAY':
      return {
        title: '启动网关',
        body:
          '设备将重新开始上报，积压的短信会被上传。\n\n' +
          '若网关本来就在运行，这条指令不会产生任何变化。',
      }
    case 'STOP_GATEWAY':
      return {
        title: '停止网关',
        body:
          '设备将停止上报，短信会留在手机上不再上传，直到有人再把它启动。\n\n' +
          '若设备已离线或网关已停止，它最长 15 分钟后才送达；一小时后过期。',
      }
    case 'CLEAR_UPLOADED':
      return {
        title: '清理本地已上传记录',
        body: '只会删除手机上已成功上传的记录，未上传的短信不受影响。不可撤销。',
      }
    case 'RE_REGISTER':
      return {
        title: '重新注册',
        body:
          '设备会用本机已有的身份重跑一次注册，不会下发新的密钥。\n\n' +
          '它解决的是「服务端那一行被人改乱了」或「App 升级后想同步版本号」这类问题。' +
          '如果设备已经丢失重注册密钥（例如重装过），请改用「生成恢复码」并让现场的人扫码 —— ' +
          '那件事没法远程修复。',
      }
    default:
      return { title: '确认', body: '' }
  }
}

/** 不需要二次确认的类型：它们没有副作用（不发短信、不删数据），可以随便点。 */
const NO_CONFIRM: DeviceCommandType[] = ['REUPLOAD']

async function issue(type: DeviceCommandType, argument: string | null = null) {
  if (submitting.value) return

  if (!NO_CONFIRM.includes(type)) {
    const { title, body } = type === 'SET_PHONE'
      ? {
          title: '修改本机号码',
          body: `把设备上记录的本机号码改成 ${argument}。\n\n这个号码用于按号码等验证码的调用方，改错了他们就会一直超时。`,
        }
      : confirmText(type)

    try {
      await ElMessageBox.confirm(body, title, {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
        // 消息里的换行要显式开启，否则 \n 会被折叠成空格，


      })
    } catch {
      return
    }
  }

  submitting.value = type
  try {
    await issueDeviceCommand(props.deviceId, type, argument)
    ElMessage.success('指令已下发，设备下次联系服务器时执行')
    await reload()
  } catch {
    // 具体原因拦截器已经弹过（例如「同类型已有一条在等待下发」）
  } finally {
    submitting.value = null
  }
}

/**
 * 号码用弹框输入，而不是常驻一行输入框 + 按钮。
 *
 * 这一页只有一个动作需要参数，为它常驻一整行会把工具条撑成两行 —— 而那个输入框
 * 99% 的时间是空的。项目的其他地方（建渠道、写规则）也都是「要填参数的动作用弹窗」。
 */
async function askPhoneAndIssue() {
  if (submitting.value) return

  let value: string
  try {
    const result = await ElMessageBox.prompt('把设备上记录的本机号码改成：', '修改本机号码', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: '例如 13800138000',
      inputValidator: (raw: string) => {
        const digits = (raw ?? '').replace(/\D/g, '')
        if (digits.length < 5) return '至少需要 5 位数字'
        if (raw.length > 32) return '太长（上限 32 个字符）'
        return true
      },
    })
    value = result.value
  } catch {
    return
  }

  await issue('SET_PHONE', value)
}

function onMoreCommand(command: string) {
  if (command === 'SET_PHONE') {
    askPhoneAndIssue()
    return
  }
  issue(command as DeviceCommandType)
}

async function cancel(row: DeviceCommand) {
  try {
    await ElMessageBox.confirm(
      '撤销后这条指令不会再下发到设备。已经执行过的无法撤回。',
      '撤销指令',
      { confirmButtonText: '撤销', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await cancelDeviceCommand(row.id)
    ElMessage.success('已撤销')
    await reload()
  } catch {
    // 400（已结束）等由拦截器提示
  }
}

onMounted(reload)

// 页面上的 SSE 订阅只有 DeviceDetail 一处（一个页面只该有一条事件流），
// 它收到信号后调这里刷新。
defineExpose({ reload })
</script>

<style scoped>
.command-hint {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text-secondary);
}

.command-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  /* 与表格之间留一条细分隔线：这里是「会改变设备状态的动作」，下面是只读的历史 */
  padding-bottom: 16px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--color-border-light);
}

.dropdown-arrow {
  margin-left: 4px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.card-section-title {
  font-weight: 600;
  font-size: 16px;
}
.command-argument {
  margin-left: 6px;
  color: var(--color-text-secondary);
  font-size: 13px;
}
.time-text { font-size: 13px; color: var(--color-text-secondary); }
.no-code { color: var(--color-text-placeholder); }
</style>
