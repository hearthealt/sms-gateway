package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.EventLogView;
import com.smsgateway.model.dto.EventTypeOption;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.enums.EventLevel;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.service.EventLogService;
import com.smsgateway.util.PageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运行事件的查询接口。只读 —— 事件的产生全在各业务路径上，没有从外部写入的入口。
 *
 * <p>路径放在 {@code /api/admin/eventlog} 而不是 {@code /api/admin/events}：
 * 后者已经被 SSE 事件流占用了（见 {@code AdminEventController}），
 * 一个是长连接、一个是普通查询，混在同一个前缀下容易看错。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/eventlog")
@RequiredArgsConstructor
public class AdminEventLogController {

    private final EventLogService eventLogService;

    /**
     * 事件列表。
     *
     * <p>类型与级别用**字符串**接参而不是直接绑枚举：前端传一个不认识的类型名时，
     * 直接绑枚举会在 Spring 的参数解析阶段抛异常（400 且原因晦涩），
     * 而这里解析失败时退化成「不过滤」—— 管理端筛错了顶多多显示几行，不该报错。
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResult<PageResult<EventLogView>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "15") int pageSize,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startDate,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endDate) {

        return ResponseEntity.ok(ApiResult.success(eventLogService.list(
                parseType(type),
                parseLevel(level),
                deviceId,
                startDate,
                endDate,
                PageUtil.safePage(page),
                PageUtil.safePageSize(pageSize))));
    }

    /** 下拉框选项：事件类型与其中文标签。前端不硬编码，加类型只需改后端枚举。 */
    @GetMapping("/types")
    public ResponseEntity<ApiResult<List<EventTypeOption>>> types() {
        return ResponseEntity.ok(ApiResult.success(eventLogService.types()));
    }

    private static EventType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EventType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            log.debug("Unknown event type filter '{}', ignoring it", raw);
            return null;
        }
    }

    private static EventLevel parseLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EventLevel.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            log.debug("Unknown event level filter '{}', ignoring it", raw);
            return null;
        }
    }
}
