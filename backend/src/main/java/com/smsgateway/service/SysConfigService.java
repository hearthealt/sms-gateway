package com.smsgateway.service;

import com.smsgateway.model.entity.SysConfig;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.repository.SysConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行期配置的读写。值存在 {@code sys_config} 表里，改完**立即生效，不用重启**。
 *
 * <p><b>带进程内缓存。</b>转发调度器每秒都要读一次「有没有启用」，每次都查库
 * 就是每秒一条查询 —— 而且它查的是一张一年也不会变几次的表。缓存在启动时加载一次，
 * 写入时写穿（{@link #set} 同时更新缓存）。
 *
 * <p>代价：多实例部署时，实例 A 改了配置，实例 B 的缓存要等重启才更新。
 * 当前项目是单实例（docker-compose 一套），所以没有引入「定时刷新」或「Redis 广播」
 * 那份复杂度。换部署形态时这里是第一个要看的地方。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigRepository repository;
    private final AdminEventBroadcaster adminEvents;

    /** 进程内缓存。缺某个键时表示「用默认值」，而不是「这个键不存在」。 */
    private final Map<SysConfigKey, String> cache = new ConcurrentHashMap<>();

    /**
     * 启动时加载一次。
     *
     * <p>**失败时不抛异常**：{@code sys_config} 表可能还没建（老库升级时要先灌
     * schema.sql）。那种情况下一切按默认值运行、日志里给出该做什么 —— 而不是让
     * 整个应用起不来。可选功能的配置表不该成为启动的硬依赖。
     */
    @PostConstruct
    void load() {
        try {
            int known = 0;
            for (SysConfig row : repository.findAll()) {
                SysConfigKey key = SysConfigKey.byKey(row.getConfigKey());
                if (key != null) {
                    cache.put(key, row.getConfigValue());
                    known++;
                }
            }
            log.info("已加载 {} 项系统设置", known);
        } catch (Exception e) {
            log.error("""
                    读取系统设置失败（sys_config 表可能还没建）。**一切按默认值运行。**
                    请在数据库上执行 backend/sql/schema.sql，然后重启后端。
                    """, e);
        }
    }

    // ---------------------------------------------------------------- 读

    public String get(SysConfigKey key) {
        String value = cache.get(key);
        return value == null ? key.defaultValue() : value;
    }

    public boolean getBoolean(SysConfigKey key) {
        return Boolean.parseBoolean(get(key));
    }

    /**
     * 取整数。
     *
     * <p>解析失败退回默认值并记日志 —— 库里被手改成一个非法值时，
     * 不该让调度器抛异常停摆，而现场还看不出是配置的问题。
     */
    public int getInt(SysConfigKey key) {
        String value = get(key);
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("系统设置 {} 的值 '{}' 不是整数，按默认值 {} 处理",
                    key.key(), value, key.defaultValue());
            return Integer.parseInt(key.defaultValue());
        }
    }

    /** 全部配置的当前值，供管理端渲染。 */
    public Map<SysConfigKey, String> all() {
        Map<SysConfigKey, String> result = new EnumMap<>(SysConfigKey.class);
        for (SysConfigKey key : SysConfigKey.values()) {
            result.put(key, get(key));
        }
        return result;
    }

    // ---------------------------------------------------------------- 写

    /**
     * 改一项配置。
     *
     * @throws IllegalArgumentException 值不合法（类型不符、负数等）
     */
    @Transactional
    public void set(SysConfigKey key, String rawValue) {
        String value = normalize(key, rawValue);

        SysConfig row = repository.findById(key.key()).orElseGet(SysConfig::new);
        row.setConfigKey(key.key());
        row.setConfigValue(value);
        repository.save(row);

        // 写穿：改完立刻生效，不必等重启 —— 这正是把这些配置从 yml 搬过来的全部目的
        cache.put(key, value);

        // 推一条：另一个管理员的页面上如果还显示着旧值，他据此做的判断就是错的
        // （「短信保留天数」尤其 —— 以为已经关掉了清理，实际没有）。
        // 前端接这条事件时会跳过有未保存改动的页面，见 SysConfig.vue。
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_SYS_CONFIG,
                Collections.singletonMap("key", key.key()));

        log.warn("系统设置已更改：{} = {}（{}）", key.key(), value, key.label());
    }

    /**
     * 校验并归一。
     *
     * <p>在这里挡住非法值，而不是等使用者去某个功能上撞 —— 比如把保留天数填成
     * 负数，删数据的逻辑会算出未来的时间点，行为难以预料。
     */
    private String normalize(SysConfigKey key, String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException(key.label() + "：值不能为空");
        }
        String value = rawValue.trim();

        return switch (key.type()) {
            case BOOLEAN -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException(key.label() + "：只能是 true 或 false");
                }
                yield value.toLowerCase();
            }
            case INT -> {
                int parsed;
                try {
                    parsed = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(key.label() + "：必须是整数");
                }
                if (parsed < 0) {
                    throw new IllegalArgumentException(key.label() + "：不能是负数");
                }
                yield String.valueOf(parsed);
            }
            case TIME -> {
                if (!SysConfigKey.isValidTime(value)) {
                    throw new IllegalArgumentException(key.label() + "：格式必须是 HH:mm（如 03:30）");
                }
                yield value;
            }
            case STRING -> {
                if (value.length() > 500) {
                    throw new IllegalArgumentException(key.label() + "：过长（上限 500 字符）");
                }
                yield value;
            }
        };
    }
}
