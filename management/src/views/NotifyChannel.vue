<template>
  <div class="notify-channel">
    <el-card shadow="never">
      <div class="action-bar">
        <div class="action-left">
          <span class="action-title">转发渠道</span>
          <el-tag type="info" effect="plain" size="small">{{ channels.length }} 个渠道</el-tag>
        </div>
        <!-- 后端没启用时直接禁用：反正存不进去，让人填一遍再报错是白费功夫 -->
        <el-tooltip
          :disabled="ready"
          content="后端尚未启用消息转发，见上方说明"
          placement="bottom"
        >
          <span>
            <el-button type="primary" :disabled="!ready" @click="showAddDialog">
              <el-icon><Plus /></el-icon> 新建渠道
            </el-button>
          </span>
        </el-tooltip>
      </div>

      <!--
        后端没配加密密钥时**先拦在这里**。不拦的话，使用者会填完整个渠道表单、
        点保存，才拿到一个「没配密钥」的错误 —— 那一刻填的东西全白填了。
      -->
      <el-alert
        v-if="!ready"
        type="warning"
        :closable="false"
        show-icon
        class="not-ready"
        title="后端还没有配置渠道加密密钥"
        description="下面配的渠道现在存不进去 —— 渠道凭据是加密存储的，没有密钥就存不了。"
      >
        <template #default>
          <div class="not-ready-detail">
            在后端设置环境变量后重启：<br />
            <code>NOTIFY_ENCRYPT_KEY=$(openssl rand -base64 32)</code>
            <p class="not-ready-note">
              docker 部署改 <code>docker/.env</code>，然后
              <code>docker compose up -d backend</code>。<br />
              密钥不能有默认值（跟着仓库走的默认密钥等于没有密钥），且**丢了之后
              已配的渠道凭据都要重新填** —— 密文在库里，没有密钥读不出来。<br />
              配好密钥之后，转发总开关在「系统设置」页，改完立即生效、不用重启。
            </p>
          </div>
        </template>
      </el-alert>

      <el-alert
        v-else-if="!channels.length && !loading"
        type="info"
        :closable="false"
        show-icon
        title="还没有转发渠道"
        description="渠道决定短信转发到哪里。配好之后还要到「转发规则」页把渠道和短信条件关联起来，否则不会有任何短信被转发。"
      />

      <el-table v-else :data="channels" stripe style="width: 100%" v-loading="loading">
        <el-table-column label="渠道名" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-primary">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="180">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>

        <el-table-column label="健康" width="150">
          <template #default="{ row }">
            <el-tooltip v-if="row.lastError" :content="row.lastError" placement="top">
              <span :class="healthClass(row)">
                <el-icon v-if="row.consecutiveFailures > 0"><WarningFilled /></el-icon>
                {{ healthText(row) }}
              </span>
            </el-tooltip>
            <span v-else :class="healthClass(row)">{{ healthText(row) }}</span>
          </template>
        </el-table-column>

        <!-- 积压比连续失败次数更早暴露「这个渠道卡住了」：限流推迟的不算失败，但积压会一直涨 -->
        <el-table-column label="积压" width="80" align="center">
          <template #default="{ row }">
            <span :class="row.backlog > 0 ? 'backlog-warn' : ''">{{ row.backlog }}</span>
          </template>
        </el-table-column>

        <el-table-column label="启用" width="76" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="actingId === row.id"
              @click.stop
              @change="(val: boolean) => handleToggle(row, val)"
            />
          </template>
        </el-table-column>

        <!--
          三个动作：测试 → 编辑 → 删除，按「用到频率 × 后果」排（删除不可逆，放最后）。

          宽度 150 是算出来的，不是拍的：`link` 型按钮 `padding: 2px`，
          每个「两字」按钮 2+2+24 = 28px，三个 84px，按钮间距 12×2 = 24px，
          单元格 padding 24px，合计 132px —— 150 留了余量。

          **别改成 `text` 型**：那个 `padding: 5px 11px`，三个要 186px，
          150 装不下就会折成两行 —— 这正是之前的表现。

          **不要加 fixed="right"**：整表放得下、不会横向滚动，固定列没有意义；
          而它一旦固定，前面的列就不再自动填满剩余宽度，宽屏下会在渠道名与操作之间空出一大块。
          （与设备管理页同理，见那里的注释。）
        -->
        <el-table-column label="操作" width="150" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="!!actingId" @click="handleTest(row)">
              测试
            </el-button>
            <el-button link type="primary" size="small" :disabled="!!actingId" @click="showEditDialog(row)">
              编辑
            </el-button>
            <el-button
              link
              type="danger"
              size="small"
              :disabled="!!actingId"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑渠道' : '新建渠道'"
      width="600px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px" label-position="right">
        <!--
          **这是本功能唯一一处安全提示，且按类型显隐。**

          只在「这条消息天然是一对多」的渠道上出现。对 WxPusher / Server酱 / PushPlus
          这类默认只推给你自己的渠道，常驻一条「群里所有人都能看到」的警告是噪音 ——
          而噪音会让真正需要看它的那几次被忽略掉。那三个渠道的「一对多」是可选字段
          （topic / topicIds），提示挂在字段自己身上，见字段描述。

          放在这里而不是做成运行时拦截：这是配置时该知道的事，不是运行时会报错的事。
        -->
        <el-alert v-if="showSafetyNote" type="warning" :closable="false" show-icon class="safety-note">
          <template #title>转发的是短信原文，验证码不做处理</template>
          <template #description>
            收件范围内所有成员（以及以后被拉进去的人）都能看到并能使用每一个验证码 ——
            对群而言，等于把对应账号的登录权限给了他们。请确认收件范围里只有你自己。
          </template>
        </el-alert>

        <el-form-item label="渠道名" required>
          <el-input v-model="form.name" placeholder="如：运维群" maxlength="100" />
        </el-form-item>

        <!-- 类型选了就不能改：不同渠道的配置结构完全不同，改类型等于换一个渠道 -->
        <el-form-item label="类型" required>
          <el-select v-model="form.type" :disabled="editing" style="width: 100%">
            <el-option v-for="t in channelTypes" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>

        <!--
          配置字段由 schema 表驱动，不写十段 v-if。
          加一种渠道时后端加枚举 + 实现，前端只在这里补一行字段描述。
        -->
        <el-form-item
          v-for="field in currentFields"
          :key="field.key"
          :label="field.label"
          :required="field.required"
        >
          <el-switch
            v-if="field.type === 'switch'"
            :model-value="form.config[field.key] === true"
            @change="(v: boolean) => setConfig(field.key, v)"
          />
          <el-input
            v-else
            :model-value="asText(form.config[field.key])"
            :type="field.type === 'secret' ? 'password' : 'text'"
            :show-password="field.type === 'secret'"
            :rows="field.type === 'textarea' ? 3 : undefined"
            :autosize="field.type === 'textarea'"
            :placeholder="editing ? '留空表示不修改' : field.placeholder"
            @update:model-value="(v: string) => setConfig(field.key, v)"
          />
          <div v-if="field.hint" class="form-hint block">{{ field.hint }}</div>
        </el-form-item>

        <el-form-item label="每分钟上限">
          <el-input-number v-model="form.rateLimitPerMin" :min="0" :max="600" />
          <span class="form-hint inline">
            0 表示不限。默认值已按各渠道官方的硬限取了保守档。
          </span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useAdminEvents } from '../composables/useAdminEvents'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createChannel,
  deleteChannel,
  getChannelList,
  getChannelTypes,
  getNotifyStatus,
  testChannel,
  toggleChannel,
  updateChannel,
} from '../api/notify'
import type { NotifyChannel, NotifyChannelType, NotifyChannelTypeOption } from '../types'

/**
 * 一种渠道类型需要填哪些配置项。
 *
 * 放在前端而不是后端：字段名与渠道实现的约定（`webhookUrl`、`corpSecret`…）
 * 是前后端之间的一部分契约，而它极短。让后端再下发一份 schema 会给「加一种渠道」
 * 多加一处要同步的地方，而那里一旦不同步，表现是表单项静默消失。
 */
type FieldType = 'text' | 'secret' | 'switch' | 'textarea'

interface FieldSpec {
  key: string
  label: string
  type: FieldType
  required?: boolean
  placeholder?: string
  hint?: string
}

const TYPE_FIELDS: Record<NotifyChannelType, FieldSpec[]> = {
  WECOM_BOT: [
    {
      key: 'webhookUrl',
      label: 'Webhook 地址',
      type: 'secret',
      required: true,
      placeholder: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...',
      hint: '这个地址就是全部凭证，注意保密。只接受 qyapi.weixin.qq.com 的地址。',
    },
  ],
  FEISHU_BOT: [
    {
      key: 'webhookUrl',
      label: 'Webhook 地址',
      type: 'secret',
      required: true,
      placeholder: 'https://open.feishu.cn/open-apis/bot/v2/hook/...',
    },
    {
      key: 'secret',
      label: '加签密钥',
      type: 'secret',
      placeholder: '机器人开启「签名校验」时填',
      hint: '飞书的签名与钉钉完全不同（key 与待签数据是反的），别照抄另一家。',
    },
  ],
  DINGTALK_BOT: [
    {
      key: 'webhookUrl',
      label: 'Webhook 地址',
      type: 'secret',
      required: true,
      placeholder: 'https://oapi.dingtalk.com/robot/send?access_token=...',
      hint: '这里的 access_token 是 webhook 的 token，不是应用的 access_token。',
    },
    {
      key: 'secret',
      label: '加签密钥',
      type: 'secret',
      placeholder: '机器人安全设置选「加签」时填',
      hint: '用「自定义关键词」方式时不用填密钥，但关键词必须设成「短信转发」（消息以它开头）。',
    },
  ],
  TELEGRAM_BOT: [
    {
      key: 'botToken',
      label: 'Bot Token',
      type: 'secret',
      required: true,
      placeholder: '123456:ABC-DEF...',
    },
    {
      key: 'chatId',
      label: 'Chat ID',
      type: 'text',
      required: true,
      placeholder: '如 -1001234567890',
      hint: 'bot 不能主动私聊没跟它说过话的用户 —— 请先给这个 bot 发一条 /start。',
    },
  ],
  SLACK_WEBHOOK: [
    {
      key: 'webhookUrl',
      label: 'Webhook 地址',
      type: 'secret',
      required: true,
      placeholder: 'https://hooks.slack.com/services/T.../B.../...',
      hint: '限流最严（1 条/秒），官方警告超限可能导致应用被永久禁用，所以默认只放 15 条/分钟。',
    },
  ],
  WXPUSHER: [
    {
      key: 'spt',
      label: 'SPT',
      type: 'secret',
      placeholder: '填了就优先用它（最简单，无需注册应用）',
      hint: 'SPT 模式把内容放在 URL 里，长短信会被截断；内容长的话用下面的标准模式。',
    },
    { key: 'appToken', label: 'AppToken', type: 'secret', placeholder: 'AT_xxx（标准模式）' },
    {
      key: 'uids',
      label: 'UID 列表',
      type: 'text',
      placeholder: 'UID_xxx,UID_yyy',
      hint: '只填自己的 UID 就是推给你自己，这是推荐用法。',
    },
    {
      key: 'topicIds',
      label: '主题 ID',
      type: 'text',
      placeholder: '如 123,456（最多 5 个）',
      hint: '填了主题就是一对多推送 —— 关注该主题的所有人都能看到验证码。',
    },
  ],
  SERVERCHAN: [
    {
      key: 'sendKey',
      label: 'SendKey',
      type: 'secret',
      required: true,
      placeholder: 'SCT...',
      hint: '免费额度只有 5 条/天，够测试不够日常用。',
    },
  ],
  PUSHPLUS: [
    { key: 'token', label: 'Token', type: 'secret', required: true },
    {
      key: 'topic',
      label: '群组编码',
      type: 'text',
      placeholder: '留空则只推给你自己',
      hint: '填了群组编码就是一对多推送 —— 群组里所有人都能看到验证码。',
    },
  ],
  GENERIC_WEBHOOK: [
    {
      key: 'url',
      label: '目标地址',
      type: 'text',
      required: true,
      placeholder: 'https://internal.example.com/hook',
    },
    { key: 'hmacSecret', label: '签名密钥', type: 'secret', placeholder: '可选，留空则不签名' },
    {
      key: 'allowPrivateNetwork',
      label: '允许内网',
      type: 'switch',
      hint: '对接公司内部系统时才打开。默认拦截内网地址，防止盗号后拿它探测内网。',
    },
  ],
  WECOM_APP: [
    { key: 'corpId', label: '企业 ID', type: 'text', required: true, placeholder: 'ww...' },
    {
      key: 'corpSecret',
      label: '应用密钥',
      type: 'secret',
      required: true,
      hint: '注意用「自建应用」的 Secret，不是通讯录同步的 Secret。',
    },
    { key: 'agentId', label: 'AgentId', type: 'text', required: true, placeholder: '如 1000002' },
    {
      key: 'touser',
      label: '接收人',
      type: 'text',
      placeholder: '留空则发给应用可见范围内的所有人',
    },
  ],
}

const channels = ref<NotifyChannel[]>([])
const channelTypes = ref<NotifyChannelTypeOption[]>([])

/**
 * 后端是否已启用转发。
 *
 * **初值取 true**：状态还没查回来时先按「已就绪」渲染，否则首帧会闪一下
 * 那条警告 —— 而绝大多数情况下它是不该出现的。
 */
const ready = ref(true)

const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref<NotifyChannel | null>(null)
const actingId = ref<number | null>(null)

const form = reactive({
  name: '',
  type: 'WECOM_BOT' as NotifyChannelType,
  /**
   * 配置项。**存的是「用户实际填了什么」，不是全量字段。**
   *
   * 这是必须的：编辑时配置是打码值、不能预填，所以开关类字段不能有一个默认的
   * `false` —— 否则管理员只改了个渠道名就保存，会把原本打开的「允许内网」覆盖成关，
   * 而界面上看不出发生过什么。没碰过的键就不在对象里，提交时自然被过滤掉。
   */
  config: {} as Record<string, unknown>,
  rateLimitPerMin: 20,
})

const currentFields = computed<FieldSpec[]>(() => TYPE_FIELDS[form.type] ?? [])

/**
 * 哪些渠道天然是「一对多」—— 只有这些才显示顶部那条安全提示。
 *
 * 判据是「这条消息默认会被几个人看到」，不是渠道属于哪一类：
 * - 群机器人 / Slack 频道：天然是群
 * - Telegram / 企业微信应用：chat_id 与 touser 都可能是多个人，**按可能的最坏情况算**
 * - WxPusher / Server酱 / PushPlus：默认只推给账号本人，不进这个表
 *   （它们的一对多是可选字段，提示挂在字段上）
 * - 通用 Webhook：目标是管理员自建的系统，收件范围由那个系统决定，
 *   我们既不知道也不该替他下判断
 */
const GROUPISH_TYPES = new Set<NotifyChannelType>([
  'WECOM_BOT',
  'FEISHU_BOT',
  'DINGTALK_BOT',
  'SLACK_WEBHOOK',
  'TELEGRAM_BOT',
  'WECOM_APP',
])

const showSafetyNote = computed(() => GROUPISH_TYPES.has(form.type))

function typeLabel(type: string): string {
  return channelTypes.value.find((t) => t.value === type)?.label ?? type
}

function asText(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function setConfig(key: string, value: unknown) {
  form.config[key] = value
}

function healthText(row: NotifyChannel): string {
  if (row.consecutiveFailures > 0) return `连续失败 ${row.consecutiveFailures} 次`
  if (row.lastSuccessAt) return '正常'
  return '尚未发送'
}

function healthClass(row: NotifyChannel): string {
  if (row.consecutiveFailures > 0) return 'health-bad'
  if (row.lastSuccessAt) return 'health-ok'
  return 'health-idle'
}

async function loadData() {
  loading.value = true
  try {
    channels.value = await getChannelList()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 状态与类型并行取：两者互不依赖，而且都要在首帧之后尽快就位
  const [status, types] = await Promise.all([getNotifyStatus(), getChannelTypes()])
  ready.value = status.ready
  channelTypes.value = types
  await loadData()
})

/*
 * 这一页**真的有服务端自变源**：渠道连续失败到阈值会被自动停用，而那不是任何人的操作。
 * 不推的话管理员看不到 —— 而这恰恰是最该立刻知道的一件事（渠道挂了，验证码就转发不出去了）。
 *
 * **弹窗开着时跳过**：编辑渠道的表单里有加密配置，被静默覆盖等于把刚填的东西丢了。
 */
useAdminEvents((event) => {
  if (event !== 'channels' && event !== 'hello') return
  if (dialogVisible.value) return
  loadData()
})

function showAddDialog() {
  editing.value = null
  form.name = ''
  form.type = 'WECOM_BOT'
  form.config = {}
  form.rateLimitPerMin = 20
  dialogVisible.value = true
}

function showEditDialog(row: NotifyChannel) {
  editing.value = row
  form.name = row.name
  form.type = row.type
  form.config = {}
  form.rateLimitPerMin = row.rateLimitPerMin
  // 配置**不预填**：回显的是打码值，填进去再提交会把 **** 当成新地址存起来。
  // 留空即「不修改」，这是后端约定的。
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写渠道名')
    return
  }

  // 必填校验。后端也会挡，但那要等一次往返、而且错误信息不如这里具体。
  const missing = currentFields.value
    .filter((f) => f.required && f.type !== 'switch')
    .filter((f) => !asText(form.config[f.key]).trim())
  if (missing.length) {
    ElMessage.warning(`请填写：${missing.map((f) => f.label).join('、')}`)
    return
  }

  // 只把**没留空**的配置项提交上去：留空是「不修改」的约定，
  // 提交空串会让后端把这项当成明确的清空。
  const config: Record<string, unknown> = {}
  Object.entries(form.config).forEach(([key, value]) => {
    if (value === '' || value === undefined || value === null) return
    config[key] = value
  })

  const payload = {
    name: form.name.trim(),
    type: form.type,
    config,
    rateLimitPerMin: form.rateLimitPerMin,
  }

  saving.value = true
  try {
    if (editing.value) {
      await updateChannel(editing.value.id, payload)
      ElMessage.success('渠道已更新')
    } else {
      await createChannel(payload)
      ElMessage.success('渠道已创建')
    }
    dialogVisible.value = false
    await loadData()
  } catch {
    // 拦截器已统一提示
  } finally {
    saving.value = false
  }
}

async function handleToggle(row: NotifyChannel, enabled: boolean) {
  actingId.value = row.id
  try {
    await toggleChannel(row.id, enabled)
    row.enabled = enabled
    ElMessage.success(enabled ? '渠道已启用' : '渠道已停用')
  } catch {
    // 拦截器已提示
  } finally {
    actingId.value = null
  }
}

async function handleTest(row: NotifyChannel) {
  actingId.value = row.id
  try {
    const result = await testChannel(row.id)
    if (result.success) {
      ElMessage.success('测试消息已发出，去目标处确认一下收到了没有')
    } else {
      // 失败原因留在弹窗里而不是一闪而过：多半要照着它去改配置
      ElMessageBox.alert(result.detail || '发送失败，对端没有返回更多信息', '测试发送失败', {
        type: 'error',
        confirmButtonText: '知道了',
      })
    }
    await loadData()
  } catch {
    // 拦截器已提示
  } finally {
    actingId.value = null
  }
}

async function handleDelete(row: NotifyChannel) {
  // 删渠道会连带影响两处，得说清楚 —— 用 popconfirm 的一行标题塞不下
  try {
    await ElMessageBox.confirm(
      '引用它的转发规则会失去这个目标，需要另行调整（规则不会自动停用，但会变成「无目标」）。\n' +
        '它还没投递的记录会被置为「已取消」——那些验证码早就过期了，补发只是噪声。\n' +
        '已投递成功的记录保留下来。',
      `确认删除渠道「${row.name}」？`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }

  actingId.value = row.id
  try {
    await deleteChannel(row.id)
    ElMessage.success('渠道已删除')
    await loadData()
  } catch {
    // 拦截器已提示
  } finally {
    actingId.value = null
  }
}
</script>

<style scoped>
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.action-left {
  display: flex;
  align-items: center;
  gap: 10px;
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
.health-ok { color: var(--color-success); }
.health-bad { color: var(--color-danger); display: inline-flex; align-items: center; gap: 4px; }
.health-idle { color: var(--color-text-secondary); }
.backlog-warn { color: var(--color-warning); font-weight: 600; }
/* 安全提示与第一个表单项之间留出间距，否则会贴着「渠道名」 */
.safety-note {
  margin-bottom: 18px;
}
.not-ready {
  margin-bottom: 16px;
}
.not-ready-detail {
  font-size: 12px;
  line-height: 1.9;
  color: var(--color-text-secondary);
}
.not-ready-detail code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: rgba(0, 0, 0, 0.05);
  padding: 1px 5px;
  border-radius: 3px;
}
.not-ready-note {
  margin: 8px 0 0;
}
.form-hint {
  font-size: 12px;
  color: var(--color-text-secondary);
  line-height: 1.6;
}
.form-hint.block { display: block; margin-top: 4px; }
.form-hint.inline { margin-left: 10px; }
</style>
