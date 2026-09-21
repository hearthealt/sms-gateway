package com.smsgateway.controller;

import com.smsgateway.model.dto.*;
import com.smsgateway.service.AdminSmsService;
import com.smsgateway.service.DeviceService;
import com.smsgateway.service.notify.NotifyChannelService;
import com.smsgateway.util.PageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final AdminSmsService adminSmsService;
    private final NotifyChannelService notifyChannelService;

    @PostMapping("/register")
    public ResponseEntity<ApiResult<DeviceRegisterResponse>> register(@Valid @RequestBody DeviceRegisterRequest request) {
        log.info("Device registration request: deviceId={}", request.getDeviceId());
        DeviceRegisterResponse response = deviceService.register(request);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    /**
     * 心跳。响应体里带回设备状态，设备据此得知自己是否被管理员禁用。
     *
     * 心跳对被禁用的设备**放行**（见 DeviceAuthInterceptor），这是刻意的：
     * 它是设备唯一能发现自己被恢复的通道，也让管理端能持续看到设备是否还活着。
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<ApiResult<HeartbeatResponse>> heartbeat(
            @Valid @RequestBody HeartbeatRequest request,
            HttpServletRequest httpRequest) {

        // 同 /api/sms/receive：身份取认证结果，不用请求体里的 deviceId。
        // 否则可拿自己的令牌覆写他人设备的 deviceName / phoneNumber / battery。
        String deviceId = (String) httpRequest.getAttribute("deviceId");

        log.debug("Heartbeat request: deviceId={}", deviceId);
        String status = deviceService.heartbeat(deviceId, request);
        return ResponseEntity.ok(ApiResult.success(new HeartbeatResponse(status)));
    }

    /**
     * 设备主动报告「网关已停止」。
     *
     * <p>心跳是「我还活着」的单向信号 —— 用户把网关停了之后心跳就断了，服务端要等 90 秒
     * 超时才判离线，那 90 秒管理后台一直显示在线。设备在停止时补这一条，后台立刻变灰。
     *
     * <p>尽力而为：进程被系统杀掉时发不出这个请求，那种情况仍由心跳超时兜底。
     * 身份同样取自拦截器的认证结果，不接受请求体里自报的设备号。
     */
    @PostMapping("/offline")
    public ResponseEntity<ApiResult<Void>> offline(HttpServletRequest httpRequest) {
        String deviceId = (String) httpRequest.getAttribute("deviceId");
        deviceService.markOffline(deviceId);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    /**
     * 本设备在服务端的短信记录。
     *
     * 挂在 /api/device/** 下，因此自动受 DeviceAuthInterceptor 保护。设备身份取自
     * 拦截器写入的请求属性，而**不是**请求参数 —— 否则任何设备都能查看到别人的记录。
     */
    @GetMapping("/sms")
    public ResponseEntity<ApiResult<PageResult<SmsView>>> mySms(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "true") boolean includeIgnored,
            HttpServletRequest httpRequest) {

        String deviceId = (String) httpRequest.getAttribute("deviceId");
        return ResponseEntity.ok(ApiResult.success(
                adminSmsService.byDevice(deviceId, PageUtil.safePage(page), PageUtil.safePageSize(pageSize), includeIgnored)));
    }

    /**
     * 本设备今日的短信统计。
     *
     * 设备端的「今日短信 / 今日验证码」读这里而不是本地库：本地库会因为清理历史、
     * 清除应用数据而与服务端不一致，出现过「数字是 0、点进去有内容」。
     */
    @GetMapping("/sms/stats")
    public ResponseEntity<ApiResult<DeviceSmsStats>> smsStats(HttpServletRequest httpRequest) {
        String deviceId = (String) httpRequest.getAttribute("deviceId");
        return ResponseEntity.ok(ApiResult.success(deviceService.todayStats(deviceId)));
    }

    /**
     * 主页那两张小图的数据：近 N 天 + 今日逐小时。
     *
     * 缺数据的天/小时由服务端补 0（见 DeviceService.trend）—— 柱状图里的空柱子
     * 本身就是信息（那天一条都没收到），跳过去会把 7 天画成 3 天而读者不会发现。
     */
    /**
     * 「一键测转发链路」：把所有启用的转发渠道各发一条测试消息。
     *
     * <p><b>限流是必需的，不是为了省资源</b>：这个接口会让服务端往微信/钉钉这类外部渠道
     * 真发消息，而设备令牌是能被窃取的。同一台设备 {@value #NOTIFY_TEST_INTERVAL_SECONDS}
     * 秒只放一次。
     *
     * <p>限流只在进程内存里，**重启会清空** —— 这是刻意接受的：重启不是攻击者能触发的动作，
     * 而为一个「按钮不能连点」的需求在设备表上加一列、多一次写库，代价更大。
     *
     * <p>顺带说明这个口子并不是新开的：拿着设备令牌同样能调 {@code /api/sms/receive}
     * 上报一条假短信，而假短信本来就会走转发规则发出去。限流挡住的是「连点刷屏」，
     * 不是「令牌被偷」—— 后者要换令牌，那是另一件事。
     */
    /**
     * 当前启用的转发渠道名。
     *
     * <p>给自检页在按钮旁边先摆出「会发给谁」用 —— 一个渠道都没启用时，
     * 点「测转发」只会返回空列表，而人看到的是「测过了，什么都没发生」。
     * 只回名字，不回配置（那里面有 webhook 地址与 token）。
     */
    @GetMapping("/notify/channels")
    public ResponseEntity<ApiResult<List<String>>> notifyChannels() {
        return ResponseEntity.ok(ApiResult.success(notifyChannelService.enabledChannelNames()));
    }

    @PostMapping("/notify/test")
    public ResponseEntity<ApiResult<List<NotifyTestResult>>> testNotify(HttpServletRequest httpRequest) {
        String deviceId = (String) httpRequest.getAttribute("deviceId");

        long now = System.currentTimeMillis();
        Long last = lastNotifyTestAt.get(deviceId);
        if (last != null && now - last < NOTIFY_TEST_INTERVAL_SECONDS * 1000L) {
            long wait = (NOTIFY_TEST_INTERVAL_SECONDS * 1000L - (now - last) + 999) / 1000;
            throw new IllegalArgumentException("测试过于频繁，请 " + wait + " 秒后再试。");
        }
        lastNotifyTestAt.put(deviceId, now);

        return ResponseEntity.ok(ApiResult.success(notifyChannelService.testAllEnabled()));
    }

    /** 每台设备上次测转发的时间。进程内，重启清空 —— 理由见 testNotify。 */
    private final Map<String, Long> lastNotifyTestAt = new ConcurrentHashMap<>();

    private static final int NOTIFY_TEST_INTERVAL_SECONDS = 300;

    @GetMapping("/sms/trend")
    public ResponseEntity<ApiResult<DeviceTrend>> smsTrend(
            @RequestParam(defaultValue = "7") int days,
            HttpServletRequest httpRequest) {

        String deviceId = (String) httpRequest.getAttribute("deviceId");
        return ResponseEntity.ok(ApiResult.success(deviceService.trend(deviceId, days)));
    }
}
