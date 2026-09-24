package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.DeviceCommandRequest;
import com.smsgateway.model.dto.DeviceCommandView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.enums.DeviceCommandType;
import com.smsgateway.service.DeviceCommandService;
import com.smsgateway.util.PageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

/**
 * 管理端：远程指令的签发、查询与撤销。
 *
 * <p><b>只做单设备，不做批量。</b>理由有四条：
 *
 * <ol>
 *   <li>每条指令在服务端至少写 2 行 event_log（下发 + 回执），一次 200 台的批量停止
 *       会往事件表里灌 400 行，「运行日志」页当天就废了 —— 而那张表的全部价值
 *       是「只留重要的」。</li>
 *   <li>「清理本地已上传记录」「重新注册」是逐台维护动作，批量做它们是**误操作的
 *       放大器**：一次点错清掉全队的本地记录。</li>
 *   <li>批量必然引出「部分失败怎么办」的界面（50 台成功、30 台离线、20 台拒执），
 *       那是一整块新设计，而需求里没有它。</li>
 *   <li>项目里没有批量操作的先例（{@code AdminDeviceService.setEnabled} 也是一台一次）。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/device")
@RequiredArgsConstructor
public class AdminDeviceCommandController {

    private final DeviceCommandService deviceCommandService;

    @PostMapping("/{deviceId}/command")
    public ResponseEntity<ApiResult<DeviceCommandView>> issue(
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceCommandRequest request,
            HttpServletRequest httpRequest) {

        // 「谁把这台设备停了」是现场最常问的一句话，而它原先在服务端一点痕迹都没有。
        // 管理员账号由 AdminAuthInterceptor 写入请求属性。
        String issuedBy = (String) httpRequest.getAttribute("adminUsername");
        DeviceCommandType type = resolveType(request.getType());

        log.info("Admin {} issues {} for device {}", issuedBy, type, deviceId);
        return ResponseEntity.ok(ApiResult.success(
                deviceCommandService.issue(deviceId, type, request.getArgument(), issuedBy)));
    }

    @GetMapping("/{deviceId}/command/list")
    public ResponseEntity<ApiResult<PageResult<DeviceCommandView>>> list(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(ApiResult.success(
                deviceCommandService.list(deviceId, PageUtil.safePage(page), PageUtil.safePageSize(pageSize))));
    }

    /**
     * 撤销一条指令。
     *
     * <p>路径里**不再带 deviceId**：id 是全局唯一的，两个都能到达同一条指令的地址
     * 只会让人以为「这两个是不是不同的东西」，而其中带错 deviceId 的那个还该不该生效
     * 又要多一条规则。
     */
    @PostMapping("/command/{id}/cancel")
    public ResponseEntity<ApiResult<DeviceCommandView>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResult.success(deviceCommandService.cancel(id)));
    }

    /**
     * 字符串 → 枚举，失败时给一条能看懂的消息。
     *
     * <p>不用 Jackson 直接把请求体绑成枚举：那样一个拼错的类型名会在反序列化阶段抛
     * {@code HttpMessageNotReadableException}，而 {@code GlobalExceptionHandler}
     * 没有接它 —— 管理端看到的是 500「服务器内部错误」，而实际只是把类型名打错了。
     */
    private DeviceCommandType resolveType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("指令类型不能为空");
        }
        try {
            return DeviceCommandType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不认识的指令类型: " + raw);
        }
    }
}
