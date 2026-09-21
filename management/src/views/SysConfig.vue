<template>
  <div class="sys-config">
    <el-card shadow="never">
      <div class="page-head">
        <div class="head-left">
          <span class="page-title">系统设置</span>
          <!-- 有未保存的改动时才出现：没改动就没有「保存」这回事 -->
          <el-tag v-if="dirtyCount" type="warning" effect="plain" size="small">
            {{ dirtyCount }} 项未保存
          </el-tag>
          <span v-else class="head-hint">改完立即生效，不用重启后端</span>
        </div>
        <div class="head-actions">
          <!-- 用 disabled 而不是 :loading：转圈图标会让按钮变宽、加载完再缩回去，
               整页刷新时看着像样式坏了。进度改由下面内容区的 v-loading 表达
               （与设备列表、投递记录那几页一致）。 -->
          <el-button @click="loadData" :disabled="loading || saving">刷新</el-button>
          <el-button v-if="dirtyCount" :disabled="saving" @click="discard">放弃修改</el-button>
          <el-button type="primary" :disabled="!dirtyCount" :loading="saving" @click="saveAll">
            保存
          </el-button>
        </div>
      </div>

      <!-- 读配置时的进度放在这一层：按钮上不再转圈（那会让按钮变宽），
           而这一页的反馈不能缺 —— 刷新是一次真实的往返 -->
      <div v-loading="loading">
        <div v-for="group in groups" :key="group.name" class="group">
          <div class="group-title">{{ group.name }}</div>

        <div v-for="item in group.items" :key="item.key" class="config-row">
          <div class="config-info">
            <div class="config-label">
              {{ item.label }}
              <el-tag v-if="isDirty(item)" size="small" type="warning" effect="plain">未保存</el-tag>
            </div>
            <p class="config-desc">{{ item.description }}</p>
            <!-- 键名平时不可见，hover 才出现 —— 排查时对着日志找它，平时是噪音 -->
            <div class="config-key">{{ item.key }}</div>
          </div>

          <div class="config-control">
            <el-switch
              v-if="item.type === 'BOOLEAN'"
              :model-value="drafts[item.key] === 'true'"
              @change="(v: boolean) => (drafts[item.key] = String(v))"
            />

            <el-time-select
              v-else-if="item.type === 'TIME'"
              :model-value="drafts[item.key]"
              start="00:00"
              step="00:15"
              end="23:45"
              placeholder="选择时间"
              class="input-time"
              @update:model-value="(v: string) => (drafts[item.key] = v)"
            />

            <!--
              不能用 v-model="drafts[item.key]"：草稿一律是字符串（见 drafts 的说明，
              后端也按字符串收），而 el-input-number 只接受 Number | Null ——
              直接绑会报 "Expected Number | Null, got String"，值也照旧传不进去。
              这里在边界上换一次类型：读出来转 Number，写回去转 String。
            -->
            <el-input-number
              v-else-if="item.type === 'INT'"
              :model-value="numberDraft(item.key)"
              :min="0"
              :controls="false"
              class="input-number"
              @update:model-value="(v: number | null) => (drafts[item.key] = v == null ? '' : String(v))"
            />

            <el-input v-else v-model="drafts[item.key]" class="input-text" />

            <!-- 改过才出现：不用记得原值是多少。它只改草稿，提交仍走顶部那个保存 -->
            <el-button
              v-if="item.value !== item.defaultValue"
              link
              type="info"
              @click="drafts[item.key] = item.defaultValue"
            >
              恢复默认
            </el-button>
          </div>
        </div>
        </div>
      </div>

      <el-empty v-if="!loading && !items.length" description="没有可配置项" :image-size="80" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSysConfigList, updateSysConfig } from '../api/sysconfig'
import type { SysConfigItem } from '../types'

const items = ref<SysConfigItem[]>([])
const loading = ref(false)
const saving = ref(false)

/**
 * 编辑中的值，与 `item.value`（服务端已保存的）分开。
 *
 * **所有控件都只改这里，提交统一走顶部那个「保存」。** 逐项自动保存的写法会让
 * 页面变成一排保存按钮，而哪些改了、哪些没改反而看不出来。
 */
const drafts = reactive<Record<string, string>>({})

const dirtyCount = computed(() => items.value.filter(isDirty).length)

/**
 * 草稿是字符串，el-input-number 要 Number —— 在这里换算，边界只此一处。
 *
 * 空串（用户清空了输入框）回 null 而不是 0：0 是个合法值（保留天数 0 = 不清理），
 * 拿它顶替「没填」会让保存下去的值悄悄变成 0。
 */
function numberDraft(key: string): number | null {
  const raw = drafts[key]
  if (raw === undefined || raw === '') return null
  const n = Number(raw)
  return Number.isFinite(n) ? n : null
}

/** 按后端给的分组切片，顺序取后端返回的顺序（也就是枚举里的声明顺序）。 */
const groups = computed(() => {
  const byName = new Map<string, SysConfigItem[]>()
  items.value.forEach((item) => {
    const name = item.group || '其他'
    if (!byName.has(name)) byName.set(name, [])
    byName.get(name)!.push(item)
  })
  return Array.from(byName.entries()).map(([name, groupItems]) => ({ name, items: groupItems }))
})

function isDirty(item: SysConfigItem): boolean {
  return drafts[item.key] !== undefined && String(drafts[item.key]) !== item.value
}

async function loadData() {
  loading.value = true
  try {
    items.value = await getSysConfigList()
    items.value.forEach((item) => {
      drafts[item.key] = item.value
    })
  } finally {
    loading.value = false
  }
}

onMounted(loadData)

function discard() {
  items.value.forEach((item) => {
    drafts[item.key] = item.value
  })
}

/**
 * 保存全部改动。
 *
 * 逐项提交而不是打包一个接口：后端是「一项一个 PUT」，而这样失败的粒度也正好 ——
 * 某一项值不合法时，其余的照样存进去，不用全部重来。
 */
async function saveAll() {
  const dirty = items.value.filter(isDirty)
  if (!dirty.length) {
    return
  }

  saving.value = true
  const failed: string[] = []

  try {
    for (const item of dirty) {
      const value = String(drafts[item.key] ?? '')
      try {
        await updateSysConfig(item.key, value)
        // 只有真存进去了才更新「已保存的值」—— 失败时草稿留着，用户能看着自己填的去改。
        // 这里刻意不整体 reload：那会把没保存成功的那项也冲回原值，用户得重新填一遍。
        item.value = value
      } catch {
        // 拦截器已经弹出后端给的具体原因（如「不能是负数」「没配加密密钥…」）
        failed.push(item.label)
      }
    }

    if (failed.length) {
      ElMessage.warning(`未保存成功：${failed.join('、')}。原因见上方提示。`)
    } else {
      ElMessage.success('已保存，立即生效')
    }
  } finally {
    saving.value = false
  }
}

/**
 * 带着未保存的改动离开时拦一下。
 *
 * 单靠顶部一个保存按钮，最容易出的岔子就是「改完直接点了左边菜单」——
 * 改动没了，而且没有任何提示。这是「一个保存按钮」这个选择的必要补偿。
 */
onBeforeRouteLeave(async () => {
  if (!dirtyCount.value) {
    return true
  }
  try {
    await ElMessageBox.confirm(
      `还有 ${dirtyCount.value} 项改动没有保存，离开就没了。`,
      '确认离开？',
      { type: 'warning', confirmButtonText: '放弃改动', cancelButtonText: '留下' }
    )
    return true
  } catch {
    return false
  }
})
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 16px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--color-border-light);
}
.head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-primary);
}
.head-hint {
  font-size: 12px;
  color: var(--color-text-secondary);
}
.head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.group {
  padding-top: 20px;
}
.group-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
  /* 与下面的行拉开层次，又不做成一张独立的卡 */
  letter-spacing: 0.5px;
}

.config-row {
  display: flex;
  align-items: flex-start;
  gap: 24px;
  padding: 14px 0;
  border-bottom: 1px solid var(--color-border-light);
}
.config-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.config-info {
  flex: 1;
  min-width: 0;
}
.config-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary);
}
.config-desc {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--color-text-secondary);
}
/* 键名平时不可见，鼠标移到这一行才淡入 */
.config-key {
  margin-top: 5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  color: var(--color-text-placeholder, #c0c4cc);
  opacity: 0;
  transition: opacity 0.15s;
}
.config-row:hover .config-key {
  opacity: 1;
}

.config-control {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  /* 与标题那一行对齐，而不是顶到说明文字的中间 */
  padding-top: 1px;
}
.input-number,
.input-time {
  width: 130px;
}
.input-text {
  width: 220px;
}
</style>
