package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 探活端点。设备设置页的「测试连接」打这里。
 *
 * 它回答的是一个此前答不了的问题：**这个地址后面到底是不是本服务。**
 *
 * 原先设备侧探的是根路径 /，任何 HTTP 响应都算「已连通」—— 404 也算。于是把地址填成
 * 路由器管理页、或别的占着 8080 的服务，界面照样报成功。这里返回一个带服务标识的
 * 固定信封，设备侧校验该标识，填错地址就会明确报「不是本服务」，而不是假装连通。
 *
 * 刻意放在所有鉴权路径之外（不在 /api/device/**、/api/admin/**、/api/sms/wait|list 之下）：
 * 设备得先能自检地址填得对不对，才谈得上注册，所以这个端点不能要求令牌。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    /**
     * 设备侧靠这个值确认「对面是本服务」。
     * 改动会让所有已发布的客户端把本服务判成「不是本服务」，不要动。
     */
    private static final String SERVICE_NAME = "sms-gateway";

    /** 探库超时（秒）。不设的话库挂掉时会一直挂在连接池的 connectionTimeout 上。 */
    private static final int DB_PROBE_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    @GetMapping("/health")
    public ResponseEntity<ApiResult<Map<String, Object>>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", SERVICE_NAME);
        data.put("database", databaseUp() ? "UP" : "DOWN");
        return ResponseEntity.ok(ApiResult.success(data));
    }

    /**
     * 顺带探一次数据库，结果放进响应体，而不是用状态码表达。
     *
     * 两个理由：
     *
     * 一是「半死」状态最需要被这个端点暴露出来 —— 服务器活着但数据库挂了时，端口照样
     * 在监听、HTTP 照样 200，设备侧只看 HTTP 会以为一切正常，实际注册和上报全会失败。
     *
     * 二是降级不能用状态码表达：设备侧正是靠状态码区分「地址填错了」和「地址对但库挂了」，
     * 若降级也返回非 200，这两种故障就糊成一团了。
     */
    private boolean databaseUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_PROBE_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("Health check: database unreachable", e);
            return false;
        }
    }
}
