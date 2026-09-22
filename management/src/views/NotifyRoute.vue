<template>
  <div class="notify-route">
    <el-card shadow="never">
      <div class="action-bar">
        <div class="action-left">
          <span class="action-title">转发规则</span>
          <el-tag type="info" effect="plain" size="small">{{ routes.length }} 条规则</el-tag>
        </div>
        <el-button type="primary" @click="showAddDialog">
          <el-icon><Plus /></el-icon> 新增规则
        </el-button>
      </div>

      <el-alert
        v-if="!channels.length"
        type="warning"
        :closable="false"
        show-icon
        title="还没有转发渠道"
        description="规则必须指向至少一个渠道，先去「转发渠道」页建一个。"
      />

      <el-table :data="routes" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="routeName" label="规则名称" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-primary">{{ row.routeName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发送方" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.senderPattern || '不限' }}</template>
        </el-table-column>
        <el-table-column label="关键词" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.keywordPattern || '不限' }}</template>
        </el-table-column>
        <el-table-column prop="matchType" label="匹配" width="80" />

        <!--
          转发到哪些渠道。这是这张表最该一眼看清的列，所以显示名字而不是 id 列表。

          渠道被停用时后端会带上「（已停用）」后缀 —— 不标的话这条规则看起来一切正常，
          而短信就是发不出去，现场要翻到投递记录页才看得出是渠道被停了。

          目标全没了（渠道被删）时给一个红标签。**空的单元格比一条假装的规则危险**：
          它会让人以为这条规则还在工作。
        -->
        <el-table-column label="转发到" min-width="180">
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

        <!--
          按钮与宽度跟「转发渠道」页保持一致：`link` 型（padding 2px）、宽度 110。
          两个「两字」按钮 28×2 = 56 + 间距 12 + 单元格 padding 24 = 92，留了余量。

          **不要加 fixed="right"**：整表放得下，固定列会让前面的列不再自动填满剩余宽度。
        -->
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
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑规则' : '新增规则'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px">
        <el-form-item label="规则名称" required>
          <el-input v-model="form.routeName" placeholder="如：所有验证码进运维群" maxlength="100" />
        </el-form-item>

        <el-form-item label="发送方匹配">
          <el-input v-model="form.senderPattern" placeholder="留空 = 不限发送方" />
        </el-form-item>

        <el-form-item label="关键词匹配">
          <el-input v-model="form.keywordPattern" placeholder="留空 = 不限正文" />
        </el-form-item>

        <el-form-item label="匹配方式">
          <el-radio-group v-model="form.matchType">
            <el-radio value="LIKE">LIKE</el-radio>
            <el-radio value="EXACT">EXACT</el-radio>
            <el-radio value="REGEX">REGEX</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item>
          <span class="form-hint">
            与采集规则同一套写法：LIKE 下 % 匹配任意长度、_ 匹配单个字符；
            REGEX 用包含匹配。<strong>「任意发送方」在 LIKE 下写 %，在 REGEX 下要写 .*</strong>——
            正则里 % 是普通字符，写 '%' 永远匹配不上。
          </span>
        </el-form-item>

        <el-form-item label="转发到" required>
          <el-select v-model="form.channelIds" multiple style="width: 100%" placeholder="至少选一个渠道">
            <el-option
              v-for="c in channels"
              :key="c.id"
              :label="`${c.name}${c.enabled ? '' : '（已停用）'}`"
              :value="c.id"
            />
          </el-select>
          <span class="form-hint block">
            多条规则命中时渠道是<strong>累加</strong>的，不像采集规则那样首条命中即定论。
          </span>
        </el-form-item>

        <el-divider />

        <el-form-item label="限定设备">
          <el-input v-model="form.deviceId" placeholder="留空 = 不限设备。要填就填完整设备号" />
        </el-form-item>

        <el-form-item label="限定号码">
          <el-input v-model="form.phonePattern" placeholder="留空 = 不限。多卡场景用" />
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
import { ref, reactive, onMounted } from 'vue'
import { useAdminEvents } from '../composables/useAdminEvents'
import { ElMessage } from 'element-plus'
import {
  createRoute,
  deleteRoute,
  getChannelList,
  getRouteList,
  toggleRoute,
  updateRoute,
} from '../api/notify'
import type { NotifyChannel, NotifyRoute } from '../types'

const routes = ref<NotifyRoute[]>([])
const channels = ref<NotifyChannel[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref<NotifyRoute | null>(null)
const togglingId = ref<number | null>(null)

const form = reactive({
  routeName: '',
  senderPattern: '',
  keywordPattern: '',
  matchType: 'LIKE',
  deviceId: '',
  phonePattern: '',
  channelIds: [] as number[],
})

async function loadData() {
  loading.value = true
  try {
    ;[routes.value, channels.value] = await Promise.all([getRouteList(), getChannelList()])
  } finally {
    loading.value = false
  }
}

onMounted(loadData)

/*
 * 转发规则有**一个服务端自变源**：渠道被删时，因此变成「无目标」的规则会被自动停用
 * （见 NotifyChannelService.delete）。那不是任何人的操作，不推的话这边看不到开关自己关了。
 * 其余变化只来自管理员自己，而本地操作已经是即时更新的 —— 这一页的订阅主要覆盖前者。
 *
 * **弹窗开着时跳过**：编辑中的表单不能被静默覆盖。
 */
useAdminEvents((event) => {
  if (event !== 'routes' && event !== 'hello') return
  if (dialogVisible.value) return
  loadData()
})

function showAddDialog() {
  editing.value = null
  form.routeName = ''
  form.senderPattern = ''
  form.keywordPattern = ''
  form.matchType = 'LIKE'
  form.deviceId = ''
  form.phonePattern = ''
  form.channelIds = []
  dialogVisible.value = true
}

function showEditDialog(row: NotifyRoute) {
  editing.value = row
  form.routeName = row.routeName
  form.senderPattern = row.senderPattern ?? ''
  form.keywordPattern = row.keywordPattern ?? ''
  form.matchType = row.matchType
  form.deviceId = row.deviceId ?? ''
  form.phonePattern = row.phonePattern ?? ''
  form.channelIds = [...row.channelIds]
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!form.routeName.trim()) {
    ElMessage.warning('请填写规则名称')
    return
  }
  if (!form.channelIds.length) {
    ElMessage.warning('至少要选一个转发渠道')
    return
  }

  const payload = {
    routeName: form.routeName.trim(),
    senderPattern: form.senderPattern,
    keywordPattern: form.keywordPattern,
    matchType: form.matchType,
    deviceId: form.deviceId,
    phonePattern: form.phonePattern,
    channelIds: form.channelIds,
  }

  saving.value = true
  try {
    if (editing.value) {
      await updateRoute(editing.value.id, payload)
      ElMessage.success('规则已更新')
    } else {
      await createRoute(payload)
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

async function handleToggle(row: NotifyRoute, enabled: boolean) {
  togglingId.value = row.id
  try {
    await toggleRoute(row.id, enabled)
    row.enabled = enabled
    ElMessage.success(enabled ? '规则已启用' : '规则已停用')
  } catch {
    // 拦截器已提示
  } finally {
    togglingId.value = null
  }
}

async function handleDelete(row: NotifyRoute) {
  try {
    await deleteRoute(row.id)
    ElMessage.success('规则已删除')
    await loadData()
  } catch {
    // 拦截器已提示
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
</style>
