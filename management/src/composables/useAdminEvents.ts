import { onBeforeUnmount, onMounted } from 'vue'
import { getValidToken } from '../utils/auth'

/**
 * 订阅管理后台的事件流（SSE），有变化时回调一次。
 *
 * <p>为什么不是定时轮询：设备上报、重复计数、设备上下线只有服务端知道，
 * 浏览器每隔几秒问一次既费请求又永远慢半拍 —— 现场看到的是「明明传上来了，
 * 列表还是旧的，得点刷新」。这里改成服务端有事发生时推一条，前端收到再拉一次数据。
 *
 * <p><b>为什么不用 EventSource</b>：管理端鉴权走 `Authorization: Bearer`，
 * 而 EventSource 不支持自定义请求头；只能把令牌塞进查询串，那会进 Nginx 访问日志、
 * 浏览器历史和 Referer。所以用 fetch 读流、自己解析 SSE 帧。
 *
 * <p>返回的 `connected` 只用来展示连接状态（例如顶栏那个小圆点）。
 */
export function useAdminEvents(onEvent: (event: string) => void) {
  let controller: AbortController | null = null
  let stopped = false
  let retry = 0
  let pending: ReturnType<typeof setTimeout> | null = null

  /**
   * 合并短时间内的多次事件：**一个窗口只回调一次**。
   *
   * 一批短信（例如双卡同时收到）会在几百毫秒内推来好几条，
   * 不做合并就是连着拉好几次列表 —— 前端这边抖，后端那边白跑。
   *
   * <b>由此产生一条必须遵守的契约：`onEvent` 拿到的那个名字，不代表这个窗口里
   * 只发生了这一件事。</b>比如 300ms 内先来 `sms` 再来 `deliveries`，回调只会看到
   * `deliveries`。所以**handler 不能按事件名做窄分支**（「是 A 才刷 A、是 B 才刷 B」），
   * 那样被盖掉的那个名字对应的数据就永远不刷新了；必须「收到任何事件都把本页关心的
   * 东西整体重拉一遍」。现有各页都是这个写法，加新页时照做。
   *
   * 之所以不改成「把窗口里所有名字逐个回调」：那会让同时订阅多个事件的页面
   * （投递记录 `deliveries`+`sms`、设备详情 `devices`+`sms`）在一次抖动里连发两三次
   * 同样的请求，而收益只是让一个本来就不该存在的窄分支能工作。
   */
  function schedule(event: string) {
    if (pending) clearTimeout(pending)
    pending = setTimeout(() => {
      pending = null
      onEvent(event)
    }, 300)
  }

  async function connect() {
    if (stopped) return

    const token = getValidToken()
    if (!token) {
      // 没登录就别连了，登录后整页会重建，那时再连
      return
    }

    controller = new AbortController()

    try {
      const res = await fetch('/api/admin/events', {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: controller.signal,
      })

      if (res.status === 401) {
        // 会话过期由 axios 那边统一处理（跳登录），这里安静退出即可，
        // 否则会在后台一直重连一个必然 401 的接口。
        stopped = true
        return
      }
      if (!res.ok || !res.body) {
        throw new Error(`events stream failed: ${res.status}`)
      }

      // 连上了：退避计数归零，并立刻对齐一次数据 —— 断线期间漏掉的事件靠这一下补上
      // （服务端订阅成功时会先发一条 hello）。
      retry = 0
      await readStream(res.body)
      // 流正常结束（服务端重启/网关断开）也走重连
    } catch (e) {
      if (stopped) return
      // AbortError 是主动关闭，不算异常
      if ((e as Error)?.name !== 'AbortError') {
        console.warn('Admin events stream error, will retry', e)
      }
    }

    if (stopped) return

    // 退避重连：1s → 2s → 5s → 10s 封顶。
    // 服务端挂掉时不能拿固定 1 秒去锤它，但也不能退得太远 —— 现场就等着它刷新。
    const delay = [1000, 2000, 5000, 10000][Math.min(retry, 3)]
    retry += 1
    setTimeout(connect, delay)
  }

  async function readStream(body: ReadableStream<Uint8Array>) {
    const reader = body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    for (;;) {
      const { done, value } = await reader.read()
      if (done) return

      buffer += decoder.decode(value, { stream: true })

      // SSE 以空行分帧。必须按 \n\n 切、保留不完整的尾帧，
      // 否则一个事件被拆在两个网络包里就会解析失败。
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)

        let eventName = 'message'
        for (const line of frame.split('\n')) {
          if (line.startsWith('event:')) {
            eventName = line.slice(6).trim()
          }
          // data: 里的内容前端用不到（只当「该刷新了」的信号），这里不解析
        }
        schedule(eventName)
      }
    }
  }

  onMounted(connect)

  onBeforeUnmount(() => {
    stopped = true
    if (pending) clearTimeout(pending)
    controller?.abort()
  })
}
