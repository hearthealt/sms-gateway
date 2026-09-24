package com.smsgateway.service;

import com.smsgateway.exception.EnrollTokenRequiredException;
import com.smsgateway.exception.EnrollmentRequiredException;
import com.smsgateway.model.dto.DailyCount;
import com.smsgateway.model.dto.DeviceRegisterRequest;
import com.smsgateway.model.dto.DeviceRegisterResponse;
import com.smsgateway.model.dto.DeviceSmsStats;
import com.smsgateway.model.dto.DeviceTrend;
import com.smsgateway.model.dto.HeartbeatRequest;
import com.smsgateway.model.dto.HourlyCount;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.model.enums.SmsStatus;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsMessageRepository;
import com.smsgateway.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final SmsMessageRepository smsMessageRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * 用编程式事务而不是 @Transactional：注册需要「撞唯一约束后重查」的兜底，
     * 而 @Transactional 一旦把事务标记为 rollback-only，同一个事务里后续查询会直接失败。
     * 必须让失败的那次写入彻底回滚后，再另起一次查询。
     */
    private final TransactionTemplate transactionTemplate;

    /** 设备上线/掉线时推一条，让管理后台的设备列表自己刷新。 */
    private final AdminEventBroadcaster adminEvents;

    /**
     * 首次注册的服务器准入校验。
     *
     * <p>**只在设备不存在时用到**。已存在设备的重新注册走 {@link #verifyEnrollment}，
     * 认的是设备身份而不是这张口令 —— 若那条路也要求口令，一开启准入，
     * 所有老设备重装后就全部失联了。
     */
    private final DeviceEnrollTokenService enrollTokenService;
    private final EventLogService eventLogService;

    @Value("${app.secret.key}")
    private String secretKey;

    private static final String DEVICE_ONLINE_KEY_PREFIX = "sms:device:";
    private static final String DEVICE_ONLINE_SUFFIX = ":online";
    private static final long HEARTBEAT_TTL_SECONDS = 90;

    public DeviceRegisterResponse register(DeviceRegisterRequest request) {
        // 事务之前先看一眼这台设备在不在：一能区分「首次注册」与「重新注册」
        // （身份被重置过一次是排查「服务端怎么多了一台设备」的关键线索），
        // 二能在被拒时把「是哪台设备被拒」记进事件 —— 那正是最需要知道的信息。
        SmsDevice known = deviceRepository.findByDeviceId(request.getDeviceId()).orElse(null);

        DeviceRegisterResponse response;
        try {
            response = transactionTemplate.execute(status -> doRegister(request));
        } catch (EnrollmentRequiredException | EnrollTokenRequiredException e) {
            // 注册被拒：口令不对、密钥不匹配、或这台设备压根没启用过重注册校验。
            // 这类拒绝在设备端只表现为一句「注册失败」，服务端不记就没人知道是哪一种。
            recordQuietly(EventType.DEVICE_ENROLL_REJECTED, known,
                    e.getClass().getSimpleName() + "：" + e.getMessage());
            throw e;
        } catch (DataIntegrityViolationException e) {
            // 两个相同 deviceId 的注册并发到达时，双方都会在查询时扑空，后落地的那个
            // 撞上 device_id 的唯一约束。注册本身是幂等的（token 由 deviceId 的 HMAC 推导，
            // 可重算），所以这里重查一次返回已有设备即可，不必把一个重复请求变成 500。
            //
            // 但**密钥校验必须在这里重做一遍**：否则「并发发两条注册、其中一条用错的密钥」
            // 就能从这条兜底路径拿到令牌 —— 它绕过了 doRegister 里的检查，而设备行本身
            // 看不出请求当初用的是哪个密钥。
            log.info("Concurrent registration for deviceId={}, returning existing device",
                    request.getDeviceId());
            response = deviceRepository.findByDeviceId(request.getDeviceId())
                    .map(device -> {
                        verifyEnrollment(device, request.getEnrollSecret());
                        return new DeviceRegisterResponse(
                                HashUtil.hmacSha256(device.getDeviceId(), secretKey),
                                device.getDeviceId(),
                                device.getStatus());
                    })
                    .orElseThrow(() -> e);
        }

        if (known != null) {
            recordQuietly(EventType.DEVICE_RE_REGISTERED, known, "重新注册");
        } else {
            // 首次注册时库里还查不到这台设备（known 为 null），但**请求里带着它的 deviceId**。
            // 不把它带上，这条事件就只剩「注册成功 / 首次注册」几个字、认不出是哪台机器 ——
            // 而它存在的全部意义正是回答「服务端怎么多出来一台设备」。
            recordQuietlyByIdentity(EventType.DEVICE_REGISTERED, request.getDeviceId(), "首次注册");
        }
        return response;
    }

    /**
     * 记一条事件，失败不影响这次请求的结论。
     *
     * <p>注册与心跳都是**设备等着回包**的接口：事件写不进去是旁路问题，
     * 让它把一个成功的注册变成 500，设备那边只会看到「注册失败」并反复重试 ——
     * 比少一条日志坏得多。
     */
    private void recordQuietly(EventType type, SmsDevice device, String reason) {
        try {
            eventLogService.record(type, device, reason);
        } catch (Exception e) {
            log.warn("Failed to record device event {}", type, e);
        }
    }

    /**
     * 设备行**还不存在**时用这个（首次注册）。
     *
     * <p>只带业务标识、不带主键：那时还没有主键可带。
     */
    private void recordQuietlyByIdentity(EventType type, String deviceCode, String reason) {
        try {
            eventLogService.recordWithIdentity(type, null, deviceCode, reason);
        } catch (Exception e) {
            log.warn("Failed to record device event {}", type, e);
        }
    }

    /**
     * 校验「这次重注册确实来自这台设备」。
     *
     * <p>挡的是原实现里的这个洞：注册接口必须免鉴权（设备得先能注册才拿得到令牌），
     * 而对**已存在**的 deviceId，它在重注册分支里把令牌原样返还 —— 于是「知道设备号」
     * 就等于「能冒充这台设备」；而设备号在管理后台列表、设备端界面、任何截图里都可见。
     *
     * <p>现在改为：设备首次注册时自带一个随机密钥，服务端只存 SHA-256，
     * 之后凡是该 deviceId 已存在的注册都必须带上它。重装丢失密钥的设备由管理员
     * 在控制台签发恢复码取回。
     */
    private void verifyEnrollment(SmsDevice device, String enrollSecret) {
        if (device.getEnrollSecretHash() == null) {
            throw new EnrollmentRequiredException(
                    "该设备尚未启用重注册校验（本次变更之前注册的），"
                            + "请由管理员在控制台签发一张恢复码后重新注册。");
        }
        // 分两种说法：没带密钥多半是 App 没升级；带了但不对才是密钥问题。
        // 合成一句"认证失败"会让现场不知道该升级还是该去签恢复码。
        if (enrollSecret == null || enrollSecret.isBlank()) {
            throw new EnrollmentRequiredException(
                    "注册请求没有携带重注册密钥。请先把 App 升级到最新版本；"
                            + "若这台设备刚重装过（本地密钥已随应用数据丢失），"
                            + "请由管理员在控制台签发一张恢复码。");
        }
        if (!device.getEnrollSecretHash().equals(HashUtil.sha256(enrollSecret))) {
            throw new EnrollmentRequiredException(
                    "重注册密钥不匹配。设备若重装过，请由管理员在控制台签发一张恢复码。");
        }
    }

    private DeviceRegisterResponse doRegister(DeviceRegisterRequest request) {
        String deviceId = request.getDeviceId();

        String rawSecret = request.getEnrollSecret();
        boolean hasSecret = rawSecret != null && !rawSecret.isBlank();
        String secretHash = hasSecret ? HashUtil.sha256(rawSecret) : null;

        Optional<SmsDevice> existingDevice = deviceRepository.findByDeviceId(deviceId);
        if (existingDevice.isPresent()) {
            SmsDevice device = existingDevice.get();

            // 先证明身份，再动任何字段 —— 校验失败要整体拒绝，不能留下半截修改。
            verifyEnrollment(device, rawSecret);

            String deviceToken = HashUtil.hmacSha256(deviceId, secretKey);
            device.setDeviceToken(deviceToken);
            if (request.getDeviceName() != null) {
                device.setDeviceName(request.getDeviceName());
            }
            if (request.getPhone() != null) {
                device.setPhoneNumber(request.getPhone());
            }
            if (request.getPlatform() != null) {
                device.setPlatform(request.getPlatform());
            }
            if (request.getAppVersion() != null) {
                device.setAppVersion(request.getAppVersion());
            }
            deviceRepository.save(device);
            log.info("Device re-registered: {}", deviceId);
            // 重复注册不重置 status：被管理员禁用的设备重新注册后仍是禁用，
            // 客户端据此显示横幅，而不是因为「注册成功」就以为可以用了。
            return new DeviceRegisterResponse(deviceToken, deviceId, device.getStatus());
        }

        // 走到这里说明是台新设备。
        //
        // 先过服务器准入，再谈设备身份 —— 顺序是有意的：注册接口必须免鉴权，
        // 在这之前没有任何东西能区分「自己人」和「碰巧知道地址的人」，所以准入这道
        // 必须最先判。没被允许接入，就没必要往下走。
        //
        // 未生成口令 / 口令被停用时 verify 直接放行，于是老部署升级后行为不变。
        enrollTokenService.verify(request.getEnrollToken());

        // 没有密钥就没法建立「这台设备是谁」的凭据，
        // 建出来的账号以后也永远无法重新注册 —— 与其留个隐患，不如现在拒绝。
        // 用 400 而不是 403：这是请求本身不完整，不是身份不通过。
        if (!hasSecret) {
            throw new IllegalArgumentException(
                    "首次注册必须提供 enrollSecret（App 会自行生成）。旧版本 App 请先升级后再注册。");
        }

        String deviceToken = HashUtil.hmacSha256(deviceId, secretKey);

        SmsDevice device = new SmsDevice();
        device.setDeviceId(deviceId);
        device.setDeviceToken(deviceToken);
        // 建档时就记下密钥哈希，之后这台设备的任何重注册都要靠它自证身份。
        device.setEnrollSecretHash(secretHash);
        device.setDeviceName(request.getDeviceName());
        device.setPhoneNumber(request.getPhone());
        device.setPlatform(request.getPlatform());
        device.setAppVersion(request.getAppVersion());
        device.setStatus("ACTIVE");
        deviceRepository.save(device);

        log.info("Device registered: {}", deviceId);
        return new DeviceRegisterResponse(deviceToken, deviceId, device.getStatus());
    }

    /**
     * @return 设备当前状态（ACTIVE / DISABLED），随心跳响应回传给设备。
     *         这是设备得知自己「已被禁用 / 已被恢复」的唯一通道。
     */
    public String heartbeat(String authenticatedDeviceId, HeartbeatRequest request) {
        // 身份由调用方从拦截器的认证结果传入，刻意不从请求体读（同 receiveSms）。
        String deviceId = authenticatedDeviceId;

        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found: " + deviceId));

        // 探测心跳只读，**一个字段都不写**。
        //
        // 它是「网关已停止」时那个 15 分钟的 WorkManager 周期任务发出来的，唯一目的是
        // 取回「启动网关」这条指令。写 last_heartbeat_at 会让后台在停止之后仍显示在线，
        // 而那是用户明明按了停止、界面却绿着 —— 正是 reported_offline_at 那套机制要消灭的现象。
        // 写遥测字段同样有害：一台已经不进不出、什么事都不做的设备，电量与待上传数不该再更新。
        //
        // 判断放在这里而不是控制器：在线状态的写入本来就归 service，
        // 放到控制器会让「谁负责不写心跳时间」变得没有答案。
        if (Boolean.TRUE.equals(request.getCommandProbe())) {
            log.debug("Probe heartbeat from device {} (gateway stopped): presence untouched", deviceId);
            return device.getStatus();
        }

        // 更新之前先算一次上次的在线状态：只在这一下「掉线又回来」时推事件。
        // 每 30 秒的心跳都推，管理后台就变成每 30 秒刷一次，与轮询没有区别。
        boolean wasOnline = isOnline(device);

        device.setLastHeartbeatAt(LocalDateTime.now());
        if (request.getDeviceName() != null) {
            device.setDeviceName(request.getDeviceName());
        }
        if (request.getPhone() != null) {
            device.setPhoneNumber(request.getPhone());
        }
        if (request.getBattery() != null) {
            device.setBattery(request.getBattery());
        }
        if (request.getNetwork() != null) {
            device.setNetwork(request.getNetwork());
        }
        if (request.getCharging() != null) {
            device.setCharging(request.getCharging());
        }
        if (request.getPendingCount() != null) {
            device.setPendingCount(request.getPendingCount());
        }
        deviceRepository.save(device);

        // Update Redis online status
        String redisKey = DEVICE_ONLINE_KEY_PREFIX + deviceId + DEVICE_ONLINE_SUFFIX;
        redisTemplate.opsForValue().set(redisKey, LocalDateTime.now().toString(), HEARTBEAT_TTL_SECONDS, TimeUnit.SECONDS);

        if (!wasOnline) {
            adminEvents.broadcast(AdminEventBroadcaster.EVENT_DEVICES, Map.of("deviceId", deviceId));
            // 只在这一下「掉线又回来」时记。每 30 秒的心跳都记，7 天就是两万行，
            // 真正要看的那些事件会被自己刷没 —— 与上面那个 broadcast 的取舍完全一致。
            recordQuietly(EventType.DEVICE_ONLINE, device, "恢复在线（此前不在线）");
        }

        log.debug("Heartbeat received from device: {}", deviceId);
        return device.getStatus();
    }

    /**
     * 设备今日的短信统计。
     *
     * 由服务端算而不是让设备统计本地库：设备端的记录页展示的就是服务端数据，
     * 同源才不会出现「显示 0、点进去却有内容」。设备本地库会因为清理历史、
     * 清除应用数据等原因与服务端不一致。
     */
    public DeviceSmsStats todayStats(String deviceId) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found: " + deviceId));

        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        return new DeviceSmsStats(
                smsMessageRepository.countByDeviceAndReceiveTimeBetween(device.getId(), start, end),
                smsMessageRepository.countCodesByDeviceAndReceiveTimeBetween(device.getId(), start, end));
    }

    /**
     * 设备端主页那两张小图：近 N 天 + 今日逐小时。
     *
     * **缺数据的点要补 0**，不能只把有数据的天/小时发给前端：
     * 柱状图的「空柱子」本身就是信息（那天一条都没收到），跳过去会把 7 天画成 3 天，
     * 而读者不会发现少了几天。
     *
     * 排除 IGNORED，与「今日短信」那两个数字同源 —— 主页上同一屏的数字和图形
     * 对不上是最容易被现场抓住的那种不一致。
     */
    public DeviceTrend trend(String deviceId, int days) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found: " + deviceId));

        LocalDate today = LocalDate.now();
        int span = Math.max(1, Math.min(days, MAX_TREND_DAYS));
        LocalDateTime from = today.minusDays(span - 1L).atStartOfDay();

        Map<LocalDate, long[]> byDay = new HashMap<>();
        for (Object[] row : smsMessageRepository.countDailySinceByDevice(
                device.getId(), from, SmsStatus.IGNORED)) {
            byDay.put(toLocalDate(row[0]), new long[]{asLong(row[1]), asLong(row[2])});
        }
        List<DailyCount> daily = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalDate day = today.minusDays(span - 1L - i);
            long[] v = byDay.getOrDefault(day, new long[]{0, 0});
            daily.add(new DailyCount(day.format(DAY_FORMAT), v[0], v[1]));
        }

        long[] hours = new long[24];
        long[] hourCodes = new long[24];
        for (Object[] row : smsMessageRepository.countHourlySinceByDevice(
                device.getId(), today.atStartOfDay(), SmsStatus.IGNORED)) {
            int hour = (int) asLong(row[0]);
            if (hour < 0 || hour > 23) continue;
            hours[hour] = asLong(row[1]);
            hourCodes[hour] = asLong(row[2]);
        }
        List<HourlyCount> hourly = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            hourly.add(new HourlyCount(h, hours[h], hourCodes[h]));
        }

        return new DeviceTrend(daily, hourly);
    }

    /** 趋势图最长看多少天。别让一个查询参数把全表扫了。 */
    private static final int MAX_TREND_DAYS = 30;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 分组键是日期。JDBC 驱动给回来的可能是 java.sql.Date 也可能是 LocalDate，两种都认。 */
    private static LocalDate toLocalDate(Object raw) {
        if (raw instanceof java.sql.Date d) return d.toLocalDate();
        if (raw instanceof LocalDate d) return d;
        return LocalDate.parse(String.valueOf(raw));
    }

    private static long asLong(Object raw) {
        return raw == null ? 0L : ((Number) raw).longValue();
    }

    public SmsDevice getDeviceByToken(String token) {
        return deviceRepository.findByDeviceToken(token)
                .orElse(null);
    }

    public SmsDevice getDeviceByDeviceId(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId)
                .orElse(null);
    }

    /**
     * 设备是否在线：最后一次心跳距今是否在 90 秒内。
     * 与 {@link #heartbeat} 写入的 Redis key TTL 同源（都起于心跳时刻），
     * 但用时间戳判断可以避免统计时对每台设备各做一次 Redis 往返。
     */
    public boolean isOnline(SmsDevice device) {
        if (device.getLastHeartbeatAt() == null
                || !device.getLastHeartbeatAt().isAfter(LocalDateTime.now().minusSeconds(HEARTBEAT_TTL_SECONDS))) {
            return false;
        }

        // 设备主动报告过「网关已停止」时，只有比那次报告更晚的心跳才算重新上线。
        // 少了这一条，点了停止之后管理后台还要再挂 90 秒才变灰。
        return device.getReportedOfflineAt() == null
                || device.getLastHeartbeatAt().isAfter(device.getReportedOfflineAt());
    }

    /**
     * 设备主动报告「网关已停止」。
     *
     * <p>没有这条通道时，停了也要等 90 秒心跳超时才判离线，那 90 秒里管理后台一直显示在线，
     * 现场看到的是「我明明停了，它还绿着」。
     *
     * <p>只是尽力而为：进程被杀时设备发不出这个请求，那种情况仍旧由心跳超时兜底 ——
     * 所以 isOnline 是「超时」与「主动停」两个条件一起判的，缺一不可。
     */
    public void markOffline(String authenticatedDeviceId) {
        SmsDevice device = deviceRepository.findByDeviceId(authenticatedDeviceId).orElse(null);
        if (device == null) {
            return;
        }

        device.setReportedOfflineAt(LocalDateTime.now());
        deviceRepository.save(device);

        adminEvents.broadcast(AdminEventBroadcaster.EVENT_DEVICES,
                Map.of("deviceId", authenticatedDeviceId));
        // 文案刻意是中性的：「网关已停止」既可能是人在手机上点的，也可能是远程指令停的。
        // 原先写死「用户停止了网关」，远程停机上线之后那句话就成了假话，而排查时
        // 「谁停的」正是第一句要问的。是谁停的由**紧邻的**那条 DEVICE_COMMAND_ACKED /
        // DEVICE_COMMAND_ISSUED 事件说明（同一台设备、相邻时刻，列表上读得出来）。
        //
        // 拒绝的替代做法：在这里反查「最近 5 分钟有没有一条 STOP_GATEWAY 被回执」来决定文案。
        // 那是给每一条停止上报加一次查询、给两个模块加一层耦合，只为省掉读者看一眼相邻行。
        recordQuietly(EventType.DEVICE_OFFLINE_REPORTED, device, "网关已停止");
        log.info("Device reported gateway stopped: {}", authenticatedDeviceId);
    }

    /** 在线判定所用的时间窗起点，供仓储层统计查询复用。 */
    public static LocalDateTime onlineSince() {
        return LocalDateTime.now().minusSeconds(HEARTBEAT_TTL_SECONDS);
    }
}