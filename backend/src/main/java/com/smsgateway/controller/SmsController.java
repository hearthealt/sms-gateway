package com.smsgateway.controller;

import com.smsgateway.model.dto.*;
import com.smsgateway.service.ClientSmsService;
import com.smsgateway.service.SmsService;
import com.smsgateway.service.WaitingService;
import com.smsgateway.util.PageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 短信接口。
 *
 * <p>这里混了两类调用方，鉴权走不同的拦截器（见 {@code SecurityConfig}）：
 * <ul>
 *   <li>{@code POST /receive} —— 设备上报，DeviceAuthInterceptor（deviceToken）</li>
 *   <li>{@code GET /list}、{@code GET /wait} —— 外部调用方，ClientAuthInterceptor（API Key）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;
    private final WaitingService waitingService;
    private final ClientSmsService clientSmsService;

    /**
     * 设备上报短信。
     *
     * <p>幂等键是 <b>(deviceId, localMessageId)</b>，由 uk_device_message 唯一索引保证，
     * 与请求体里的字段同源。
     *
     * <p>客户端还会带一个 {@code Idempotency-Key} 头（内容是 {@code deviceId:localMessageId}），
     * 但它与上面那个键完全等价，服务端不读它。头保留在文档里只是为了让老客户端照常发送 ——
     * 原实现声明了这个参数却从不使用，这里去掉声明，免得再给人「有额外幂等语义」的错觉。
     */
    @PostMapping("/receive")
    public ResponseEntity<ApiResult<SmsReceiveResponse>> receive(
            @Valid @RequestBody SmsReceiveRequest request,
            HttpServletRequest httpRequest) {

        // 身份一律取拦截器认证出来的那个，**不用请求体里的 deviceId**。
        //
        // 否则任何持有合法设备令牌的人都能把 body 里的 deviceId 填成别人的设备：
        // 短信被记到他人名下，而且 phone 也是可控的 —— 拿它去覆盖他人号码的
        // 验证码缓存并广播，正在 wait 的调用方就会收到攻击者指定的验证码。
        String deviceId = (String) httpRequest.getAttribute("deviceId");

        log.info("SMS receive request: deviceId={}, localMessageId={}, sender={}",
                deviceId, request.getLocalMessageId(), request.getSender());

        SmsReceiveResponse response = smsService.receiveSms(deviceId, request);

        if (response.isDuplicate()) {
            return ResponseEntity.ok(ApiResult.success("duplicate", response));
        }
        return ResponseEntity.ok(ApiResult.success(response));
    }

    /**
     * 查询已采集的短信。所有条件可选，都不传就是「取全部」。
     *
     * <p>只有号码和时间两个维度 —— 外部调用方不关心发送方是谁。
     * phone 会先归一化，再靠 {@code like %phone%} 容忍库里存的 +86 前缀，历史数据不用迁移。
     *
     * <p>时间参数用 ISO-8601（{@code 2026-09-17T15:29:21}），与响应里的 receiveTime 同格式，
     * 调用方拿到的值可以直接回传。刻意不加 {@code @DateTimeFormat} 指定 pattern ——
     * Spring 默认的 ISO_LOCAL_DATE_TIME 能同时接受带不带毫秒的写法，比写死 pattern 宽容。
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResult<PageResult<ClientSmsView>>> list(
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "startTime", required = false) LocalDateTime startTime,
            @RequestParam(value = "endTime", required = false) LocalDateTime endTime,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {

        int safePage = PageUtil.safePage(page);
        int safePageSize = PageUtil.safePageSize(pageSize);

        log.info("SMS list request: phone={}, startTime={}, endTime={}, page={}, pageSize={}",
                phone, startTime, endTime, safePage, safePageSize);

        return ResponseEntity.ok(ApiResult.success(
                clientSmsService.list(phone, startTime, endTime, safePage, safePageSize)));
    }

    /**
     * 阻塞等待指定号码收到的验证码。
     *
     * <p>不带 sender —— 外部调用方只知道接收号码，不该需要知道是谁发的。
     * 已存在的验证码立即返回；否则挂起到超时，调用方不用自己写轮询。
     */
    @GetMapping("/wait")
    public ResponseEntity<ApiResult<SmsWaitResponse>> wait(
            @RequestParam("phone") String phone,
            @RequestParam(value = "timeout", defaultValue = "60") long timeout) {

        log.info("SMS wait request: phone={}, timeout={}s", phone, timeout);

        CompletableFuture<SmsWaitResponse> future = waitingService.waitForSms(phone, timeout);

        try {
            // 显式定界。服务层已经把 timeout 夹进 MAX_WAIT_SECONDS，正常路径这里总会先由
            // future 自己完成；定界是防异常路径 —— 比如调度器已关停、超时任务根本不会跑，
            // 那样无参 get() 会永远挂着，把 Tomcat 工作线程一直占住不放。
            SmsWaitResponse response = future.get(WaitingService.MAX_WAIT_SECONDS + 5, TimeUnit.SECONDS);
            return ResponseEntity.ok(ApiResult.success(response));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                        .body(ApiResult.error(408, "wait timeout"));
            }
            log.error("Error waiting for SMS", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResult.error(500, "internal server error"));
        } catch (TimeoutException e) {
            // get() 自身的兜底超时：future 始终没完成，说明调度侧的定时任务没跑起来
            log.error("Wait future never completed for phone={}", phone, e);
            return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                    .body(ApiResult.error(408, "wait timeout"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResult.error(500, "interrupted"));
        }
    }
}
