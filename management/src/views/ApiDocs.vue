<template>
  <div class="api-docs-layout">
    <!-- Left Navigation -->
    <aside class="docs-nav">
      <div class="nav-header">
        <div class="nav-logo">
          <svg viewBox="0 0 32 32" width="22" height="22" fill="none">
            <rect width="32" height="32" rx="8" fill="url(#lg)" />
            <path d="M8 16L14 22L24 10" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
            <defs><linearGradient id="lg" x1="0" y1="0" x2="32" y2="32"><stop stop-color="#409eff"/><stop offset="1" stop-color="#36d399"/></linearGradient></defs>
          </svg>
        </div>
        <div class="nav-title">
          <strong>对外接口文档</strong>
          <span>v1.0</span>
        </div>
      </div>
      <nav class="nav-list">
        <template v-for="group in navGroups" :key="group.label">
          <div class="nav-group-label">{{ group.label }}</div>
          <a
            v-for="item in group.children"
            :key="item.id"
            :href="'#' + item.id"
            :class="['nav-item', { active: activeSection === item.id }]"
            @click.prevent="scrollTo(item.id)"
          >
            <span v-if="item.method" class="nav-method" :class="item.method.toLowerCase()">{{ item.method }}</span>
            <span>{{ item.label }}</span>
          </a>
        </template>
      </nav>
    </aside>

    <!-- Right Content -->
    <main class="docs-content" ref="contentRef" @scroll="onScroll">
      <!-- Page Header -->
      <div class="page-header">
        <h1>SMS Gateway <span class="fw-light">短信 API</span></h1>
        <p class="page-desc">外部调用方接口文档 · Base URL: <code>http://&lt;host&gt;:8080/api</code></p>
        <p class="page-scope">
          本文档面向<strong>外部调用方</strong>，提供两个接口：按号码和时间<strong>查询短信</strong>、
          <strong>阻塞等待验证码</strong>。设备端接口（<code>/api/device/**</code>）与管理后台接口
          （<code>/api/admin/**</code>）不在此范围内。
        </p>
        <div class="page-badges">
          <span class="badge badge-blue">JSON</span>
          <span class="badge badge-green">RESTful</span>
        </div>
      </div>

      <!-- ======== 1. 鉴权 ======== -->
      <section id="auth" class="api-section">
        <h2 class="section-title">鉴权</h2>

        <div class="endpoint-card">
          <p class="ep-desc">
            所有请求都必须携带调用密钥，放在 <code>Authorization</code> 请求头里，格式为
            <code>Bearer &lt;apiKey&gt;</code>（<code>Bearer</code> 与密钥之间有一个空格）。
            密钥在管理后台的「API 密钥」页签发，由服务端管理员分发。
          </p>
          <div class="ep-body">
            <div class="ep-left">
              <div class="ep-subtitle">📋 请求头</div>
              <el-table :data="authHeaders" stripe size="small" class="ep-table">
                <el-table-column label="名称" prop="name" width="130" />
                <el-table-column label="类型" prop="type" width="80" />
                <el-table-column label="必填" width="60">
                  <template #default="{ row }">
                    <el-tag v-if="row.required" type="danger" size="small" effect="dark">是</el-tag>
                    <span v-else class="opt-tag">否</span>
                  </template>
                </el-table-column>
                <el-table-column label="说明" prop="desc" min-width="200" />
              </el-table>
            </div>
            <div class="ep-right">
              <div class="ep-subtitle">📦 示例</div>
              <pre class="code-block"><code>{{ authHeaderExample }}</code></pre>
              <div class="ep-subtitle" style="color: #f56c6c; margin-top: 16px;">❌ 401 Unauthorized</div>
              <pre class="code-block"><code>{{ unauthorizedExample }}</code></pre>
            </div>
          </div>
          <div class="ep-note">
            <el-icon><WarningFilled /></el-icon>
            <span>
              密钥被<strong>禁用</strong>、<strong>删除</strong>或<strong>超过有效期</strong>后立即失效，
              调用方会收到 401。失效后请向管理员申请重新签发，不要复用同一个密钥。
            </span>
          </div>
        </div>
      </section>

      <!-- ======== 2. 查询短信 ======== -->
      <section id="sms-list" class="api-section">
        <h2 class="section-title">查询短信</h2>

        <div class="endpoint-card">
          <div class="ep-head">
            <span class="ep-method get">GET</span>
            <code class="ep-path">/api/sms/list</code>
            <span class="ep-tag">查询</span>
          </div>
          <p class="ep-desc">
            查询网关已采集到的短信。<strong>所有条件都是可选的，一个都不传就是「取全部」</strong>。
            按接收时间倒序返回，分页。
          </p>

          <div class="ep-body">
            <div class="ep-left">
              <div class="ep-subtitle">📋 查询参数</div>
              <el-table :data="listParams" stripe size="small" class="ep-table">
                <el-table-column label="参数" prop="name" width="100" />
                <el-table-column label="类型" prop="type" width="70" />
                <el-table-column label="必填" width="60">
                  <template #default="{ row }">
                    <el-tag v-if="row.required" type="danger" size="small" effect="dark">是</el-tag>
                    <span v-else class="opt-tag">否</span>
                  </template>
                </el-table-column>
                <el-table-column label="说明" prop="desc" min-width="240" />
              </el-table>
            </div>
            <div class="ep-right">
              <div class="ep-subtitle">📦 请求示例</div>
              <pre class="code-block no-bg"><code>{{ listReqExample }}</code></pre>
            </div>
          </div>

          <div class="ep-body">
            <div class="ep-left">
              <div class="ep-subtitle">📋 响应字段</div>
              <el-table :data="listResFields" stripe size="small" class="ep-table">
                <el-table-column label="字段" prop="field" width="180" />
                <el-table-column label="类型" prop="type" width="70" />
                <el-table-column label="说明" prop="desc" min-width="240" />
              </el-table>
            </div>
            <div class="ep-right">
              <div class="ep-subtitle" style="color: #67c23a">✅ 200 OK</div>
              <pre class="code-block"><code>{{ listResExample }}</code></pre>
            </div>
          </div>

          <div class="ep-note">
            <el-icon><WarningFilled /></el-icon>
            <span>
              响应里的 <code>records[].phone</code> 是<strong>设备上报的原始值</strong>，
              可能带 <code>+86</code>；而查询是按「包含」匹配的，所以你传
              <code>13800138000</code> 能同时命中库里存的 <code>13800138000</code> 和
              <code>+8613800138000</code>。号码里的 <code>+86</code>、空格、横线都会先被归一化。
            </span>
          </div>
          <div class="ep-note" style="margin-top: 10px">
            <el-icon><WarningFilled /></el-icon>
            <span>
              默认<strong>不含</strong>被采集规则判定为「忽略」的营销类短信，与管理后台列表口径一致。
            </span>
          </div>
          <div class="ep-note" style="margin-top: 10px">
            <el-icon><WarningFilled /></el-icon>
            <span>
              时间统一用 ISO-8601（<code>2026-09-17T15:29:21</code>）：入参
              <code>startTime</code> / <code>endTime</code> 与返回的 <code>receiveTime</code>
              同格式，拿到的值可以<strong>直接回传</strong>。格式里没有空格，拼进 URL 也不用编码。
            </span>
          </div>
        </div>
      </section>

      <!-- ======== 3. 等待验证码 ======== -->
      <section id="sms-wait" class="api-section">
        <h2 class="section-title">等待验证码</h2>

        <div class="endpoint-card">
          <div class="ep-head">
            <span class="ep-method get">GET</span>
            <code class="ep-path">/api/sms/wait</code>
            <span class="ep-tag">阻塞等待</span>
          </div>
          <p class="ep-desc">
            等待指定号码收到一条带验证码的短信。<strong>接口会阻塞</strong>到收到或超时为止：
            若验证码已经在服务器上（短信先到、后调接口），立即返回；否则一直等到 <code>timeout</code> 秒。
            调用方不需要自己写轮询循环。
          </p>

          <div class="ep-body">
            <div class="ep-left">
              <div class="ep-subtitle">📋 查询参数</div>
              <el-table :data="waitParams" stripe size="small" class="ep-table">
                <el-table-column label="参数" prop="name" width="100" />
                <el-table-column label="类型" prop="type" width="70" />
                <el-table-column label="必填" width="60">
                  <template #default="{ row }">
                    <el-tag v-if="row.required" type="danger" size="small" effect="dark">是</el-tag>
                    <span v-else class="opt-tag">否</span>
                  </template>
                </el-table-column>
                <el-table-column label="说明" prop="desc" min-width="240" />
              </el-table>
            </div>
            <div class="ep-right">
              <div class="ep-subtitle">📦 请求示例</div>
              <pre class="code-block no-bg"><code>{{ waitReqExample }}</code></pre>
            </div>
          </div>

          <div class="ep-body">
            <div class="ep-left">
              <div class="ep-subtitle" style="color: #67c23a">✅ 200 OK（等到了短信）</div>
              <pre class="code-block"><code>{{ waitResExample }}</code></pre>
            </div>
            <div class="ep-right">
              <div class="ep-subtitle" style="color: #67c23a">✅ 200 OK（验证码已在服务器上）</div>
              <pre class="code-block"><code>{{ waitResCachedExample }}</code></pre>
            </div>
          </div>

          <div class="ep-body">
            <div class="ep-left">
              <div class="ep-subtitle" style="color: #e6a23c">⚠️ 408 Request Timeout</div>
              <pre class="code-block"><code>{{ waitTimeoutExample }}</code></pre>
            </div>
            <div class="ep-right">
              <div class="ep-subtitle">📋 响应字段</div>
              <el-table :data="waitResFields" stripe size="small" class="ep-table">
                <el-table-column label="字段" prop="field" width="150" />
                <el-table-column label="类型" prop="type" width="70" />
                <el-table-column label="说明" prop="desc" min-width="200" />
              </el-table>
            </div>
          </div>

          <div class="ep-note warn">
            <el-icon><WarningFilled /></el-icon>
            <span>
              <strong>命中的是「已在服务器上」那条路径时，只有 <code>smsCode</code> 和
              <code>phone</code> 有值</strong>，<code>sender</code> / <code>content</code> /
              <code>receiveTime</code> 都是 <code>null</code> ——
              缓存里只存了验证码本身。调用方不要把这些字段当非空值用。
            </span>
          </div>
          <div class="ep-note warn" style="margin-top: 10px">
            <el-icon><WarningFilled /></el-icon>
            <span>
              同一号码<strong>只应有一个等待方</strong>。并发发起多个等待会互相干扰
              （后发起的会顶掉先前的等待，任一超时都会让其余等待提前失败）。
              若业务上确实可能并发，请在你的系统里对同一号码加锁串行化。
            </span>
          </div>
        </div>
      </section>

      <!-- ======== 4. 调用示例 ======== -->
      <section id="call-examples" class="api-section">
        <h2 class="section-title">调用示例</h2>

        <div class="endpoint-card">
          <el-tabs v-model="exampleTab">
            <el-tab-pane label="curl" name="curl">
              <pre class="code-block"><code>{{ curlExample }}</code></pre>
            </el-tab-pane>
            <el-tab-pane label="Java" name="java">
              <pre class="code-block"><code>{{ javaExample }}</code></pre>
            </el-tab-pane>
            <el-tab-pane label="Python" name="python">
              <pre class="code-block"><code>{{ pythonExample }}</code></pre>
            </el-tab-pane>
          </el-tabs>
          <div class="ep-note">
            <el-icon><WarningFilled /></el-icon>
            <span>
              示例里的 <code>sk-3f2a...6071</code> 是占位符，请换成实际签发的密钥。
              调用 <code>/api/sms/wait</code> 时注意：HTTP 客户端的超时时间要<strong>大于</strong>
              请求参数 <code>timeout</code>，否则本地会先断开，拿不到 408。
            </span>
          </div>
        </div>
      </section>

      <!-- ======== 5. 错误码 ======== -->
      <section id="error-codes" class="api-section">
        <h2 class="section-title">错误码</h2>
        <div class="endpoint-card">
          <el-table :data="errorCodes" stripe size="small" class="ep-table">
            <el-table-column label="HTTP" prop="http" width="90" />
            <el-table-column label="code" prop="code" width="90" />
            <el-table-column label="说明" prop="desc" min-width="400" />
          </el-table>
          <div class="ep-body" style="margin-top: 20px">
            <div class="ep-left">
              <div class="ep-subtitle">通用响应格式</div>
              <pre class="code-block"><code>{{ commonResExample }}</code></pre>
            </div>
            <div class="ep-right">
              <div class="ep-subtitle">响应字段</div>
              <el-table :data="resultFields" stripe size="small" class="ep-table">
                <el-table-column label="字段" prop="field" width="110" />
                <el-table-column label="类型" prop="type" width="80" />
                <el-table-column label="说明" prop="desc" min-width="200" />
              </el-table>
            </div>
          </div>
          <div class="ep-note">
            <el-icon><WarningFilled /></el-icon>
            <span>
              HTTP 状态码与响应体里的 <code>code</code> <strong>保持一致</strong>：
              408 时两者都是 408。判断成败请以 HTTP 状态码为准，同时也可以读 <code>code</code>。
            </span>
          </div>
        </div>
      </section>

      <!-- ======== 6. 对接流程 ======== -->
      <section id="integration-guide" class="api-section">
        <h2 class="section-title">对接流程</h2>
        <div class="endpoint-card">
          <div class="guide-flow">
            <div class="guide-step">
              <div class="guide-num">1</div>
              <div class="guide-body">
                <div class="guide-title">申请密钥</div>
                <div class="guide-text">管理员在「API 密钥」页签发</div>
              </div>
              <el-icon class="guide-arrow"><ArrowRight /></el-icon>
            </div>
            <div class="guide-step">
              <div class="guide-num">2</div>
              <div class="guide-body">
                <div class="guide-title">选一种取法</div>
                <div class="guide-text">拉历史用 list，等新的用 wait</div>
              </div>
              <el-icon class="guide-arrow"><ArrowRight /></el-icon>
            </div>
            <div class="guide-step">
              <div class="guide-num">3</div>
              <div class="guide-body">
                <div class="guide-title">拿短信 / 验证码</div>
                <div class="guide-text">records[].code 或 smsCode</div>
              </div>
              <el-icon class="guide-arrow"><ArrowRight /></el-icon>
            </div>
            <div class="guide-step">
              <div class="guide-num">4</div>
              <div class="guide-body">
                <div class="guide-title">完成业务</div>
                <div class="guide-text">校验后登录</div>
              </div>
            </div>
          </div>
          <div class="ep-note" style="margin-top: 20px">
            <el-icon><WarningFilled /></el-icon>
            <span>
              两种取法各有适用场景：<strong>自己控制节奏、要历史数据</strong>用
              <code>/api/sms/list</code>（不阻塞，可翻页、可按时间段）；
              <strong>用户点了「获取验证码」要立刻拿到</strong>用 <code>/api/sms/wait</code>
              （阻塞等，省掉自己写轮询）。
            </span>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ArrowRight, WarningFilled } from '@element-plus/icons-vue'

// ── Navigation ──
interface NavItem {
  id: string
  label: string
  method?: string
}

interface NavGroup {
  label: string
  children: NavItem[]
}

const navGroups: NavGroup[] = [
  { label: '接入准备', children: [
    { id: 'auth', label: '鉴权' },
  ]},
  { label: '接口', children: [
    { id: 'sms-list', label: '查询短信', method: 'GET' },
    { id: 'sms-wait', label: '等待验证码', method: 'GET' },
  ]},
  { label: '其他', children: [
    { id: 'call-examples', label: '调用示例' },
    { id: 'error-codes', label: '错误码' },
    { id: 'integration-guide', label: '对接流程' },
  ]},
]

const contentRef = ref<HTMLElement | null>(null)
const activeSection = ref('auth')
const exampleTab = ref('curl')

function scrollTo(id: string) {
  activeSection.value = id
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onScroll() {
  const c = contentRef.value
  if (!c) return
  const viewTop = 72
  const items: { id: string; el: HTMLElement }[] = []
  for (const g of navGroups) {
    for (const item of g.children) {
      const el = document.getElementById(item.id)
      if (el) items.push({ id: item.id, el })
    }
  }
  // Find the first item whose top is below viewTop → highlight its predecessor
  let found = items[0]?.id ?? activeSection.value
  for (let i = 0; i < items.length; i++) {
    if (items[i].el.getBoundingClientRect().top >= viewTop) {
      found = i > 0 ? items[i - 1].id : items[i].id
      break
    }
  }
  // If even the last item is still above viewTop → highlight the last
  const last = items[items.length - 1]
  if (last && last.el.getBoundingClientRect().top < viewTop) {
    found = last.id
  }
  activeSection.value = found
}

// ── Response sample strings ──
const authHeaderExample = `Authorization: Bearer sk-3f2a1b4c5d6e7f809a1b2c3d4e5f6071`
const unauthorizedExample = `{\n  "code": 401,\n  "message": "Invalid API key",\n  "data": null\n}`

const listReqExample = `GET /api/sms/list\n  ?phone=13800138000\n  &startTime=2026-09-17T00:00:00\n  &endTime=2026-09-17T23:59:59\n  &page=1\n  &pageSize=20\n\nAuthorization: Bearer sk-3f2a1b4c5d6e7f809a1b2c3d4e5f6071`

const listResExample = `{\n  "code": 200,\n  "message": "success",\n  "data": {\n    "records": [\n      {\n        "id": 1024,\n        "phone": "13800138000",\n        "sender": "10086",\n        "content": "【中国移动】您的验证码是 123456，5 分钟内有效。",\n        "code": "123456",\n        "receiveTime": "2026-09-17T15:40:23"\n      }\n    ],\n    "total": 128,\n    "page": 1,\n    "pageSize": 20\n  }\n}`

const waitReqExample = `GET /api/sms/wait\n  ?phone=13800138000\n  &timeout=60\n\nAuthorization: Bearer sk-3f2a1b4c5d6e7f809a1b2c3d4e5f6071`
const waitResExample = `{\n  "code": 200,\n  "message": "success",\n  "data": {\n    "smsCode": "123456",\n    "sender": "10086",\n    "content": "【中国移动】您的验证码是 123456，5 分钟内有效。",\n    "phone": "13800138000",\n    "receiveTime": 1742198423000\n  }\n}`
const waitResCachedExample = `{\n  "code": 200,\n  "message": "success",\n  "data": {\n    "smsCode": "123456",\n    "sender": null,\n    "content": null,\n    "phone": "13800138000",\n    "receiveTime": null\n  }\n}`
const waitTimeoutExample = `{\n  "code": 408,\n  "message": "wait timeout",\n  "data": null\n}`
const commonResExample = `{\n  "code": 200,\n  "message": "success",\n  "data": { ... }\n}`

const curlExample = `# 时间参数是 ISO 格式、不含空格，直接拼进 URL 即可
curl -s \\
  -H "Authorization: Bearer sk-3f2a1b4c5d6e7f809a1b2c3d4e5f6071" \\
  "http://localhost:8080/api/sms/list?phone=13800138000&startTime=2026-09-17T00:00:00&endTime=2026-09-17T23:59:59&pageSize=20"`

const javaExample = `import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

HttpClient client = HttpClient.newHttpClient();

// 时间参数是 ISO 格式，不含空格，无需自己做 URL 编码
String url = "http://localhost:8080/api/sms/list?phone=13800138000"
        + "&startTime=2026-09-17T00:00:00"
        + "&endTime=2026-09-17T23:59:59"
        + "&pageSize=20";

HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", "Bearer sk-3f2a1b4c5d6e7f809a1b2c3d4e5f6071")
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

if (response.statusCode() == 200) {
    // 用 path() 而不是 get()：code 字段可能为 null，path() 返回 MissingNode 不会 NPE
    JsonNode data = new ObjectMapper().readTree(response.body()).path("data");
    System.out.println("命中 " + data.path("total").asLong() + " 条");
    for (JsonNode sms : data.path("records")) {
        System.out.println(sms.path("phone").asText() + " | " + sms.path("code").asText());
    }
} else {
    // 401 密钥无效；400 参数有误
    System.out.println(response.statusCode() + " " + response.body());
}`

const pythonExample = `import requests

resp = requests.get(
    "http://localhost:8080/api/sms/list",
    params={
        "phone": "13800138000",
        "startTime": "2026-09-17T00:00:00",   # ISO 格式，无需自己编码
        "endTime": "2026-09-17T23:59:59",
        "pageSize": 20,
    },
    headers={"Authorization": "Bearer sk-3f2a1b4c5d6e7f809a1b2c3d4e5f6071"},
    timeout=10,
)

data = resp.json()["data"]
print(f"命中 {data['total']} 条")
for sms in data["records"]:
    print(sms["phone"], sms["code"])`

// ── Table data ──
const authHeaders = [
  { name: 'Authorization', type: 'String', required: true, desc: '固定格式 Bearer <apiKey>，注意 Bearer 后有一个空格' },
]
const listParams = [
  { name: 'phone', type: 'String', required: false, desc: '接收号码。不传 = 全部。带 +86、空格、横线都行，服务端会归一化' },
  { name: 'startTime', type: 'String', required: false, desc: '起始时间，ISO-8601：2026-09-17T00:00:00，按接收时间过滤' },
  { name: 'endTime', type: 'String', required: false, desc: '结束时间，格式同上' },
  { name: 'page', type: 'Int', required: false, desc: '页码，从 1 开始，默认 1' },
  { name: 'pageSize', type: 'Int', required: false, desc: '每页条数，默认 20，最大 100' },
]
const listResFields = [
  { field: 'data.records', type: 'Array', desc: '短信列表，按接收时间倒序' },
  { field: 'data.records[].id', type: 'Long', desc: '短信 ID' },
  { field: 'data.records[].phone', type: 'String', desc: '接收号码，设备上报的原始值（可能带 +86）' },
  { field: 'data.records[].sender', type: 'String', desc: '发送方号码' },
  { field: 'data.records[].content', type: 'String', desc: '短信正文' },
  { field: 'data.records[].code', type: 'String', desc: '提取出的验证码，没有则为 null' },
  { field: 'data.records[].receiveTime', type: 'String', desc: '接收时间，ISO-8601：yyyy-MM-ddTHH:mm:ss' },
  { field: 'data.total', type: 'Long', desc: '命中总条数（不是本页条数）' },
]
const waitParams = [
  { name: 'phone', type: 'String', required: true, desc: '接收号码，同样会归一化' },
  { name: 'timeout', type: 'Long', required: false, desc: '等待秒数，默认 60。无上限校验，建议不超过 60~120' },
]
const waitResFields = [
  { field: 'data.smsCode', type: 'String', desc: '提取到的验证码' },
  { field: 'data.sender', type: 'String', desc: '发送方号码。缓存命中时为 null' },
  { field: 'data.content', type: 'String', desc: '短信正文。缓存命中时为 null' },
  { field: 'data.phone', type: 'String', desc: '接收号码（归一化后）' },
  { field: 'data.receiveTime', type: 'Long', desc: '接收时间戳（毫秒）。缓存命中时为 null' },
]
const resultFields = [
  { field: 'code', type: 'Int', desc: '业务状态码，与 HTTP 状态码一致' },
  { field: 'message', type: 'String', desc: '提示信息，成功固定为 success' },
  { field: 'data', type: 'Object', desc: '业务数据，出错时为 null' },
]
const errorCodes = [
  { http: 200, code: 200, desc: '成功' },
  { http: 400, code: 400, desc: '参数有误：缺少必填参数、号码格式不对，或 startTime / endTime 格式不对' },
  { http: 401, code: 401, desc: '密钥问题：没带 Authorization 头、密钥不存在、已被禁用或已过期' },
  { http: 408, code: 408, desc: '超时：timeout 秒内没等到验证码（仅 /api/sms/wait）' },
  { http: 500, code: 500, desc: '服务端内部错误' },
]
</script>

<style scoped>
/* ═══════════ Layout ═══════════ */
.api-docs-layout {
  display: flex;
  gap: 0;
  min-height: calc(100vh - 100px);
}

/* ═══════════ Navigation ═══════════ */
.docs-nav {
  width: 220px;
  flex-shrink: 0;
  position: sticky;
  top: 0;
  height: calc(100vh - 100px);
  overflow-y: auto;
  border-right: 1px solid var(--color-border-light);
  padding: 0 0 24px;
}
.nav-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 16px 16px;
  border-bottom: 1px solid var(--color-border-light);
  margin-bottom: 8px;
}
.nav-logo { flex-shrink: 0; display: flex; }
.nav-title { display: flex; flex-direction: column; line-height: 1.3; }
.nav-title strong { font-size: 14px; color: var(--color-text-primary); }
.nav-title span { font-size: 11px; color: var(--color-text-placeholder); }

.nav-group-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--color-text-placeholder);
  padding: 16px 16px 6px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 16px;
  font-size: 13px;
  color: var(--color-text-regular);
  text-decoration: none;
  cursor: pointer;
  border-left: 3px solid transparent;
  transition: all 0.15s ease;
}
.nav-item:hover {
  color: var(--color-primary);
  background: rgba(64,158,255,.05);
}
.nav-item.active {
  color: var(--color-primary);
  background: rgba(64,158,255,.08);
  border-left-color: var(--color-primary);
  font-weight: 600;
}
.nav-method {
  display: inline-block;
  padding: 0 5px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 700;
  font-family: monospace;
  color: #fff;
  line-height: 1.6;
  flex-shrink: 0;
}
.nav-method.post { background: #409eff; }
.nav-method.get { background: #67c23a; }

/* ═══════════ Content ═══════════ */
.docs-content {
  flex: 1;
  overflow-y: auto;
  padding: 0 0 48px 28px;
  scroll-behavior: smooth;
}

.page-header {
  padding: 28px 0 24px;
  border-bottom: 1px solid var(--color-border-light);
  margin-bottom: 32px;
}
.page-header h1 {
  font-size: 26px;
  font-weight: 800;
  margin: 0 0 4px;
  color: var(--color-text-primary);
}
.fw-light { font-weight: 300; color: var(--color-text-secondary); }
.page-desc { font-size: 13px; color: var(--color-text-secondary); margin: 0 0 10px; }
/* 范围说明：比副标题再弱一档，读起来是备注而不是正文标题 */
.page-scope {
  font-size: 12px;
  color: var(--color-text-placeholder);
  line-height: 1.7;
  margin: 0 0 12px;
  max-width: 820px;
}
.page-desc code,
.page-scope code { background: var(--color-bg); padding: 1px 6px; border-radius: 3px; font-size: 12px; color: var(--color-primary); }
.page-badges { display: flex; gap: 8px; }
.badge { display: inline-block; padding: 2px 10px; border-radius: 12px; font-size: 11px; font-weight: 600; }
.badge-blue { background: #ecf5ff; color: #409eff; }
.badge-green { background: #f0f9eb; color: #67c23a; }

/* ═══════════ API Section ═══════════ */
/* id 挂在本元素上，滚动定位的留白得补在这里 */
.api-section { margin-bottom: 40px; scroll-margin-top: 24px; }
.section-title {
  font-size: 20px;
  font-weight: 700;
  margin: 0 0 20px;
  padding-bottom: 10px;
  border-bottom: 2px solid var(--color-primary);
  color: var(--color-text-primary);
}

/* ═══════════ Endpoint Card ═══════════ */
.endpoint-card {
  background: var(--color-white);
  border: 1px solid var(--color-border-light);
  border-radius: 12px;
  padding: 24px;
  margin-bottom: 20px;
  scroll-margin-top: 24px;
}
.endpoint-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,.04); }

.ep-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.ep-method {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 5px;
  font-size: 12px;
  font-weight: 700;
  font-family: monospace;
  color: #fff;
  letter-spacing: .3px;
  flex-shrink: 0;
}
.ep-method.post { background: linear-gradient(135deg,#409eff,#337ecc); }
.ep-method.get { background: linear-gradient(135deg,#67c23a,#529b2e); }
.ep-path {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary);
  font-family: 'JetBrains Mono', monospace;
}
.ep-tag {
  font-size: 11px;
  color: var(--color-text-placeholder);
  background: var(--color-bg);
  padding: 1px 8px;
  border-radius: 4px;
}
.ep-desc {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin: 0 0 20px;
  line-height: 1.6;
}
.ep-desc code {
  background: var(--color-bg);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 12px;
}

/* ═══════════ Left-Right ═══════════ */
.ep-body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  margin-bottom: 16px;
}
.ep-left, .ep-right { min-width: 0; }
.ep-subtitle {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-regular);
  margin-bottom: 8px;
}

/* ═══════════ Table ═══════════ */
.ep-table { width: 100%; }
:deep(.ep-table th.el-table__cell) {
  background: var(--color-bg) !important;
  font-weight: 600;
  color: var(--color-text-regular);
  font-size: 12px;
}
:deep(.ep-table .el-table__cell) {
  font-size: 12px;
  padding: 6px 0;
}
.opt-tag { color: var(--color-text-placeholder); font-size: 11px; }

/* ═══════════ Code Block ═══════════ */
.code-block {
  background: #1e2a3a;
  border-radius: 8px;
  padding: 14px 16px;
  margin: 0;
  overflow-x: auto;
  font-family: 'JetBrains Mono','Fira Code',monospace;
  font-size: 12px;
  line-height: 1.7;
  color: #e6e9ef;
}
.code-block code { background: none; padding: 0; color: inherit; }
.code-block.no-bg {
  background: var(--color-bg);
  color: var(--color-text-primary);
  border: 1px solid var(--color-border-light);
}

/* 代码示例切换 tab 与深色代码块贴合 */
:deep(.el-tabs__header) { margin-bottom: 14px; }
:deep(.el-tabs__item) { font-size: 13px; }

/* ═══════════ Notes ═══════════ */
.ep-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 14px;
  background: #ecf5ff;
  border-radius: 8px;
  font-size: 12px;
  color: #2c3e50;
  line-height: 1.5;
}
.ep-note .el-icon { color: #409eff; font-size: 14px; margin-top: 1px; flex-shrink: 0; }
.ep-note code {
  background: rgba(64,158,255,.1);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 11px;
  color: #409eff;
}
/* 提醒类：字段可能为 null、并发等待等容易踩的坑 */
.ep-note.warn { background: #fdf6ec; }
.ep-note.warn .el-icon { color: #e6a23c; }
.ep-note.warn code {
  background: rgba(230,162,60,.12);
  color: #b88230;
}

/* ═══════════ Guide Flow ═══════════ */
.guide-flow { display: flex; align-items: flex-start; gap: 0; flex-wrap: nowrap; overflow-x: auto; padding: 8px 0; }
.guide-step {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--color-bg);
  border: 1px solid var(--color-border-light);
  border-radius: 10px;
  padding: 14px 18px;
  flex: 1;
  min-width: 140px;
}
.guide-num {
  width: 26px; height: 26px;
  border-radius: 50%;
  background: var(--color-primary);
  color: #fff;
  font-weight: 700;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.guide-body { display: flex; flex-direction: column; gap: 2px; }
.guide-title { font-size: 12px; font-weight: 600; color: var(--color-text-primary); }
.guide-text { font-size: 11px; color: var(--color-text-secondary); }
.guide-arrow {
  font-size: 18px;
  color: var(--color-text-placeholder);
  flex-shrink: 0;
}

/* ═══════════ Scrollbar ═══════════ */
.docs-nav::-webkit-scrollbar, .docs-content::-webkit-scrollbar { width: 4px; }
.docs-nav::-webkit-scrollbar-thumb, .docs-content::-webkit-scrollbar-thumb { background: var(--color-text-placeholder); border-radius: 2px; }

/* ═══════════ Responsive ═══════════ */
@media (max-width: 900px) {
  .api-docs-layout { flex-direction: column; }
  .docs-nav { width: 100%; position: static; height: auto; border-right: none; border-bottom: 1px solid var(--color-border-light); margin-bottom: 20px; }
  .docs-content { padding-left: 0; }
  .ep-body { grid-template-columns: 1fr; }
  .guide-flow { flex-direction: column; align-items: stretch; }
  .guide-arrow { transform: rotate(90deg); margin: 0 auto; }
}
</style>
