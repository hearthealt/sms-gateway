package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.DeviceCommandAckRequest;
import com.smsgateway.model.dto.DeviceCommandAckResponse;
import com.smsgateway.service.DeviceCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备侧：远程指令的执行回执。
 *
 * <p>挂在 {@code /api/device/**} 下，因此自动受 {@code DeviceAuthInterceptor} 保护，
 * 设备身份取自拦截器写入的请求属性 —— **不接受请求体里的 deviceId**，
 * 否则任何设备都能把别人设备的指令标成「已执行」。
 *
 * <p><b>回执走独立端点，不搭下一次心跳。</b>三条理由：
 *
 * <ol>
 *   <li>回执必须**立刻**发。搭下一次心跳意味着「已下发 → 已执行」最长要 60 秒。
 *       管理员点了「停止网关」，60 秒内控制台一直显示「已下发」，他会再点一次 ——
 *       第二次会被服务端的同类型去重挡下并返回同一条，但体验上仍然是「这按钮没反应」。</li>
 *   <li>回执要带**失败原因**，而心跳请求是个「里面的一切都不作数」的只读上行
 *       （见 HeartbeatRequest 的类注释）。往它里面加 ackedCommandIds，
 *       等于让心跳请求变成「既上报状态又回报指令结果」的混合体。</li>
 *   <li>独立端点天然可测、可限流、可单独演进。</li>
 * </ol>
 *
 * <p>代价是设备端多了一个接口，可以接受。
 *
 * <p>被禁用的设备**可以**回执：拦截器只在 {@code /api/sms/receive} 上拒绝 DISABLED
 * 设备。这是对的 —— 「停止网关」「改号码」这类指令与「上不上报」无关，
 * 而禁止被禁用设备回执，只会让那些指令永远停在「已下发」。
 */
@Slf4j
@RestController
@RequestMapping("/api/device/command")
@RequiredArgsConstructor
public class DeviceCommandController {

    private final DeviceCommandService deviceCommandService;

    @PostMapping("/ack")
    public ResponseEntity<ApiResult<DeviceCommandAckResponse>> ack(
            @Valid @RequestBody DeviceCommandAckRequest request,
            HttpServletRequest httpRequest) {

        String deviceId = (String) httpRequest.getAttribute("deviceId");
        int accepted = deviceCommandService.ack(deviceId, request.getResults());
        log.debug("Device {} acked {} of {} command(s)", deviceId, accepted, request.getResults().size());

        return ResponseEntity.ok(ApiResult.success(new DeviceCommandAckResponse(accepted)));
    }
}
