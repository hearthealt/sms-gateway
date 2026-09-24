package com.smsgateway.service.notify;

import com.smsgateway.model.enums.AlertType;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 告警的防刷屏闸门。
 *
 * <p><b>两个键，各管一件事。</b>这是整个告警特性里最容易被「简化」掉的一处，
 * 所以把分工写在这里：
 *
 * <ul>
 *   <li>{@code sms:alert:episode:{type}:{subject}} —— 「这次离线只告警一次」。
 *       设备恢复在线时**主动删除**，于是下一次离线能重新告警。TTL 长（7 天），
 *       因为它表达的是「这一段故障还没结束」。</li>
 *   <li>{@code sms:alert:cooldown:{type}:{subject}} —— 「反复抖动时每冷却窗口最多一条」。
 *       **恢复时不清**，自然过期。这正是防抖的全部：清掉它等于「离线 → 恢复 → 离线」
 *       每轮发一条。</li>
 * </ul>
 *
 * <p>两个键都要拿到才发。效果：网络抖两分钟 → 一次都不发（没到阈值）；
 * 离线 20 分钟恢复 → 一条；接着又离线 20 分钟 → **不发**（episode 空着，但冷却没过期）；
 * 断断续续离线一整天 → 最多 24 条，而不是几百条。
 *
 * <p><b>Redis 挂掉时放行。</b>与 {@code NotifyRateLimiter} 同一个取向：代价是可能重复
 * 告警，比「Redis 一挂、故障告警全部消失」轻得多 —— 而那恰恰是最需要告警的时刻。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertGate {

    private static final String EPISODE_PREFIX = "sms:alert:episode:";
    private static final String COOLDOWN_PREFIX = "sms:alert:cooldown:";

    /**
     * 设备离线那一段「故障期」的保质期。
     *
     * <p>7 天：比任何一次「设备关机忘了开」都长，而比「同一个键永久留着」短 ——
     * 万一恢复事件丢了（进程被杀、Redis 被清），7 天之后还能重新告警，
     * 不会永久哑掉。
     */
    private static final Duration OFFLINE_EPISODE_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final SysConfigService sysConfigService;

    /**
     * 这条告警该不该发。
     *
     * @return true 表示可以发。已经在这个故障期里、或还在冷却窗口内时返回 false。
     */
    public boolean shouldFire(AlertType type, String subjectKey) {
        String episodeKey = episodeKey(type, subjectKey);
        String cooldownKey = cooldownKey(type, subjectKey);
        Duration cooldown = Duration.ofMinutes(cooldownMinutes());

        try {
            Boolean episodeFresh = redisTemplate.opsForValue()
                    .setIfAbsent(episodeKey, "1", episodeTtl(type, cooldown));
            if (!Boolean.TRUE.equals(episodeFresh)) {
                log.debug("告警在故障期内，跳过：type={}, subject={}", type, subjectKey);
                return false;
            }

            Boolean cooldownFree = redisTemplate.opsForValue()
                    .setIfAbsent(cooldownKey, "1", cooldown);
            if (!Boolean.TRUE.equals(cooldownFree)) {
                // 冷却没过。**必须把刚占上的 episode 退回去** —— 不退的话，这次被冷却挡下的
                // 告警会白白消费掉这一次故障期：冷却过去之后设备再离线，episode 已经被占着，
                // 于是再也发不出来，而现场只会觉得「告警有时候不灵」。
                redisTemplate.delete(episodeKey);
                log.debug("告警在冷却窗口内，跳过：type={}, subject={}", type, subjectKey);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.warn("告警去重检查失败，本次放行：type={}, subject={}", type, subjectKey, e);
            return true;
        }
    }

    /** 设备恢复在线时调用：结束这一段故障期，让下一次离线能重新告警。 */
    public void clearEpisode(AlertType type, String subjectKey) {
        try {
            redisTemplate.delete(episodeKey(type, subjectKey));
        } catch (Exception e) {
            // 清不掉只是「下一次离线可能不告警」，不该影响设备恢复在线这件事本身。
            log.warn("清除告警故障期失败：type={}, subject={}", type, subjectKey, e);
        }
    }

    /**
     * 故障期键的存活时长。
     *
     * <p>「故障期」只对**设备离线**有明确含义：它有可观测的结束事件（恢复在线），
     * 那个事件负责清键。其余两类没有对应的结束事件 —— 渠道可能被重新启用、
     * 指令可能重发成功，但都没有一个「结束」信号会到达这里。给它们一个 7 天的
     * episode 等于「同一个渠道一周内只会告警一次」，那会漏掉第二次故障。
     * 所以它们退回冷却窗口的长度，也就是只有冷却在起作用。
     */
    private Duration episodeTtl(AlertType type, Duration cooldown) {
        return type == AlertType.DEVICE_OFFLINE ? OFFLINE_EPISODE_TTL : cooldown;
    }

    /**
     * 冷却窗口（分钟）。
     *
     * <p>下限 1 分钟、上限 7 天：填 0 等于没有防抖（一次抖动刷满屏），
     * 填一个超大值等于告警静默失效，而界面上都看不出不对。
     */
    private int cooldownMinutes() {
        int configured = sysConfigService.getInt(SysConfigKey.ALERT_COOLDOWN_MINUTES);
        return Math.max(1, Math.min(7 * 24 * 60, configured));
    }

    private String episodeKey(AlertType type, String subjectKey) {
        return EPISODE_PREFIX + type + ":" + subjectKey;
    }

    private String cooldownKey(AlertType type, String subjectKey) {
        return COOLDOWN_PREFIX + type + ":" + subjectKey;
    }
}
