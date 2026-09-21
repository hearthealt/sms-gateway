package com.smsgateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 管理后台的「有变化了」推送通道（SSE）。
 *
 * <p><b>为什么不是前端轮询。</b>设备上报、重复计数、设备上下线这些事，只有服务端知道；
 * 让浏览器每隔几秒问一次，既费请求又永远慢半拍（现场看到的是「明明传上来了，
 * 列表还是旧的，得点刷新」）。这里改成服务端有事发生时主动推一条，
 * 前端收到再拉一次数据 —— 平时一个请求都不发。
 *
 * <p>用 SSE 而不是 WebSocket：这是单向的（只有服务端→浏览器），SSE 就是为这件事设计的，
 * 而且断线重连由客户端处理，不需要心跳帧、不需要额外依赖。
 *
 * <p><b>前端必须用 fetch 读这个流，不能用 EventSource</b>：管理端鉴权走
 * {@code Authorization: Bearer} 头，而 EventSource 不支持自定义请求头 ——
 * 用 fetch + ReadableStream 才不会把令牌塞进 URL（那会进访问日志和浏览器历史）。
 * 见 management/src/composables/useAdminEvents.ts。
 */
@Slf4j
@Component
public class AdminEventBroadcaster {

    /** 短信有新增或重复计数变化。处置方：短信列表页。 */
    public static final String EVENT_SMS = "sms";

    /** 设备在线集合变了（有人上线或掉线）。处置方：设备列表页、仪表盘。 */
    public static final String EVENT_DEVICES = "devices";

    /**
     * 当前挂着的事件流。
     *
     * <p>CopyOnWriteArraySet：订阅/取消订阅远比广播频繁，读多写少，
     * 广播时遍历也需要一个不会被并发修改搞崩的集合。
     */
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    /** 订阅。返回的 emitter 由 Spring MVC 接管写入。 */
    public SseEmitter subscribe() {
        // 0 = 不设超时：这是长连接，断了由前端自己重连（带退避）。
        // 服务端设超时反而会在安静的时段把连接掐掉，逼前端做无意义的定时重连。
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // 立刻发一条：让前端知道「连上了」，也让它在这时对齐一次数据
        // （断线期间漏掉的事件就靠这一下补上）。
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of(), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitters.remove(emitter);
        }

        log.debug("Admin event stream subscribed, active={}", emitters.size());
        return emitter;
    }

    /**
     * 停机前把还挂着的事件流收干净。
     *
     * <p>不收会怎样：Tomcat 关连接器时（{@code AbstractProtocol.stop}）会把
     * waitingProcessors 里每个还挂着的异步请求逐个 {@code timeoutAsync(-1)} —— 也就是
     * 强制置为超时。{@code SseEmitter(0L)} 在容器里存的 asyncTimeout 就是 0，而 0 只意味着
     * 「不主动超时」，那发强制超时照样落到每一条流上，Spring 把它变成
     * AsyncRequestTimeoutException（由 GlobalExceptionHandler 接住、debug 记一句）。
     * 前端不觉得疼（流结束会走它本来那条退避重连），但每次重启都白刷一轮异常栈。
     *
     * <p>为什么监听 ContextClosedEvent 而不是 {@code @PreDestroy}：Boot 3.2 的
     * {@code AbstractApplicationContext.doClose()} 顺序是「先发 ContextClosedEvent →
     * 再停 Lifecycle（web server 就在其中）→ 最后销毁单例 bean」。@PreDestroy 落在最后
     * 一步，那时连接器已经停完，来不及。
     *
     * <p>收流走 {@code complete()}：它会 flush，并让 DeferredResult 以 null 结束
     * （见 ResponseBodyEmitterReturnValueHandler），请求随即正常完成 —— processor 不再留在
     * waitingProcessors 里，那发强制的超时因此落空；前端看到的是流干净地结束。
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAllOnShutdown() {
        if (emitters.isEmpty()) {
            return;
        }
        log.info("Closing {} admin event stream(s) before shutdown", emitters.size());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception e) {
                // 对方已经走了（关了页面、断了网）。这里只是尽量让还活着的那几条干净收线，
                // 收不掉就算了 —— 剩下的交给上面那个 AsyncRequestTimeoutException handler。
                log.debug("Failed to complete admin event emitter on shutdown", e);
            }
        }
        emitters.clear();
    }

    /**
     * 广播一条事件。没有订阅者时直接返回 —— 没人看的时候不该有任何开销。
     *
     * @param event   事件名，见 {@link #EVENT_SMS} / {@link #EVENT_DEVICES}
     * @param payload 附带信息。前端不依赖它的内容，只把它当作「该刷新了」的信号。
     */
    public void broadcast(String event, Map<String, Object> payload) {
        if (emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 对方已经走了（关页面、断网、休眠）。必须主动摘掉：留着的话每广播一次
                // 就往一个死连接上写一次，异常日志会被刷屏，连接也一直占着。
                emitters.remove(emitter);
                log.debug("Dropped dead admin event emitter, remaining={}", emitters.size());
            }
        }
    }
}
