package com.smsgateway.service;

import com.smsgateway.model.dto.ClientOutboundView;
import com.smsgateway.model.dto.OutboundResult;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.dto.SmsOutboundPayload;
import com.smsgateway.model.dto.SmsOutboundView;
import com.smsgateway.model.dto.SmsSendRequest;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.SmsOutbound;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.model.enums.SmsOutboundStatus;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsOutboundRepository;
import com.smsgateway.util.PhoneUtil;
import com.smsgateway.util.SecretGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 外发短信：服务端下发，由某台设备真的发出去。
 *
 * <p><b>这是全项目第一个会主动产生费用的动作。</b>发一条短信要钱、要受运营商与
 * 合规约束，所以这里有三道闸，每一道都值得单独说：
 *
 * <ol>
 *   <li><b>每日限额</b>（每台设备，默认 20 条）：一次误操作不该把一台设备变成短信轰炸机。
 *       按**入队时刻**计数而不是发出时刻 —— 否则「积压一批、集中发出」的那天会绕开限额。</li>
 *   <li><b>绝不重发</b>：与远程指令最关键的差别。指令重发的代价是「设备可能多做一次」
 *       （而那些动作本来幂等），短信重发的代价是**真的又发一条出去**、又计费一次。
 *       所以回执丢了只能记成「结果未知」，靠人去对账，不靠重发去确认。</li>
 *   <li><b>交给设备与设备发出分开记</b>（DISPATCHED / SENT）：合成一个就会在回执丢的时候
 *       把「不知道发没发」显示成「已发出」。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsOutboundService {

    /**
     * 正文的字符上限。
     *
     * <p>按**字符数**而不是字节数：这条短信要计费，而一条 500 字的中文会被拆成
     * 约 8 条（UCS-2 每段 67 字），调用方在界面上能看到这个分段数。再长就是在
     * 绕过「这是要花钱的」这件事了。
     */
    static final int MAX_CONTENT_CHARS = 500;

    /**
     * 一次心跳最多交几条。
     *
     * <p>比指令少（那边是 5）：每一条到了设备上都是一次真实的发送动作与费用，
     * 一批塞太多万一参数写错，代价是几十条短信费。
     */
    private static final int MAX_DELIVER_PER_HEARTBEAT = 3;

    private final SmsOutboundRepository outboundRepository;
    private final DeviceRepository deviceRepository;
    private final EventLogService eventLogService;
    private final AdminEventBroadcaster adminEvents;
    private final SysConfigService sysConfigService;

    // ------------------------------------------------------------------ 入队

    /**
     * 入队一条外发短信（管理端）。
     *
     * <p>不加 {@code @Transactional}：{@code EventLogService.record} 标的是 REQUIRES_NEW，
     * 必须在外层事务之外调用（见那个类的类注释）。这里只有一次 save，本来也不需要事务。
     */
    public SmsOutboundView enqueue(String deviceCode, SmsSendRequest request, String createdBy) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceCode)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceCode));
        return enqueueFor(device, request, "ADMIN", createdBy);
    }

    /**
     * 入队（外部 API）。{@code deviceId} 可省略 —— **只有在服务器上恰好只有一台设备时**
     * 才自动用它，否则要求显式指定。
     *
     * <p><b>为什么不按号码反查设备。</b>请求里的 {@code phone} 是**收信方**，
     * 而设备是靠它自己的号码（{@code phoneNumber}）登记的 —— 两者绝大多数时候不是
     * 同一个号。拿收信方的号码去反查「用哪台设备发」是把两个字段搞混了，
     * 而它一旦匹配上（比如正好给设备自己的号发一条）会**用一个看起来正确的答案
     * 掩盖掉一次错误的调用**。所以这里只认「唯一一台设备」这种无歧义的情形。
     */
    public ClientOutboundView enqueueFromClient(SmsSendRequest request, String apiKeyName) {
        SmsDevice device = resolveDevice(request.getDeviceId());
        SmsOutboundView view = enqueueFor(device, request, "API", apiKeyName);
        return new ClientOutboundView(view.getId(), view.getPhone(), view.getStatus(),
                view.getCreateTime(), view.getSentAt(), view.getErrorReason());
    }

    private SmsDevice resolveDevice(String deviceCode) {
        if (deviceCode != null && !deviceCode.isBlank()) {
            return deviceRepository.findByDeviceId(deviceCode.trim())
                    .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceCode));
        }

        List<SmsDevice> all = deviceRepository.findAll();
        if (all.isEmpty()) {
            throw new IllegalArgumentException(
                    "服务器上还没有任何设备，无法发送。请先让一台设备接入。");
        }
        if (all.size() > 1) {
            throw new IllegalArgumentException(
                    "服务器上有 " + all.size() + " 台设备，无法确定用哪一台。"
                            + "请在请求里显式指定 deviceId。");
        }
        return all.get(0);
    }

    private SmsOutboundView enqueueFor(SmsDevice device, SmsSendRequest request, String source, String createdBy) {
        // 被禁用的设备不能被用来发短信。
        //
        // 这是刻意的：禁用是管理员明确的「这台别用了」，而**下发走的是心跳**——
        // 被禁用的设备心跳照常（那是它发现自己被恢复的唯一通道），所以少了这道闸，
        // 一条外发短信会真的从那台「已经停用」的手机上发出去。
        if (AdminDeviceService.STATUS_DISABLED.equals(device.getStatus())) {
            throw new IllegalArgumentException(
                    "设备「" + (device.getDeviceName() == null ? device.getDeviceId() : device.getDeviceName())
                            + "」已被禁用，不能用它发送短信。请先在设备管理里启用它。");
        }

        String phone = validatePhone(request.getPhone());
        String content = validateContent(request.getContent());
        checkDailyLimit(device);

        SmsOutbound outbound = new SmsOutbound();
        outbound.setDeviceId(device.getId());
        outbound.setDeviceCode(device.getDeviceId());
        outbound.setPhone(phone);
        outbound.setContent(content);
        outbound.setStatus(SmsOutboundStatus.PENDING);
        outbound.setSource(source);
        outbound.setCreatedBy(createdBy);
        // 幂等键：设备回执时原样带回。用随机串而不是自增 id —— id 是内部标识，
        // 设备不该看到、也不该依赖服务端的自增序列。
        outbound.setOutboundKey("out-" + SecretGenerator.randomSecret());
        outbound.setSimSlot(request.getSimSlot());
        outboundRepository.save(outbound);

        eventLogService.record(EventType.SMS_OUTBOUND_QUEUED, device,
                "发给 " + phone + "（" + source + "）：" + preview(content));
        broadcast();

        log.info("外发短信已入队：device={}, phone={}, source={}, by={}, id={}",
                device.getDeviceId(), phone, source, createdBy, outbound.getId());
        return toView(outbound, device);
    }

    /**
     * 号码归一化与基本校验。
     *
     * <p>归一化的理由与收信侧一样（{@code PhoneUtil} 的类注释）：带不带 +86、有没有
     * 空格横线完全取决于调用方，而发出去的号码要交给运营商 —— 一个带空格的号码
     * 有些机型会直接发失败，而失败信息里看不出是格式问题。
     */
    private String validatePhone(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        String digits = PhoneUtil.normalize(trimmed);
        if (digits.length() < 5) {
            throw new IllegalArgumentException("号码格式不正确：至少需要 5 位数字");
        }
        if (trimmed.length() > 32) {
            throw new IllegalArgumentException("号码超长（上限 32）");
        }
        return trimmed;
    }

    private String validateContent(String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("短信内容不能为空");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(
                    "短信内容超长（上限 " + MAX_CONTENT_CHARS + " 字）。这条短信是要计费的，"
                            + "太长会被拆成很多条。");
        }
        return content;
    }

    /**
     * 每日限额。{@code outbound.daily-limit-per-device}，**0 表示不限**。
     *
     * <p>按入队时刻算（见 schema 里那条注释）：按发出时刻算的话，「今天入队一批、
     * 明天集中发出」会绕开限额 —— 而那种情况恰恰是最需要拦住的。
     */
    private void checkDailyLimit(SmsDevice device) {
        int limit = sysConfigService.getInt(SysConfigKey.OUTBOUND_DAILY_LIMIT_PER_DEVICE);
        if (limit <= 0) {
            return;
        }

        long today = outboundRepository.countByDeviceIdAndCreatedAtGreaterThanEqual(
                device.getId(), LocalDate.now().atStartOfDay());
        if (today >= limit) {
            throw new IllegalArgumentException(
                    "这台设备今天已经创建了 " + today + " 条外发短信，达到每日上限（" + limit + " 条）。"
                            + "上限可在「系统设置 → 外发短信」里调整。");
        }
    }

    /** 事件里的内容预览。事件表是长期留存的，不放整段正文。 */
    private static String preview(String content) {
        return content.length() <= 30 ? content : content.substring(0, 30) + "…";
    }

    // ------------------------------------------------------------------ 下发

    /**
     * 心跳里调：把待发的交给设备。
     *
     * <p>从 PENDING 转 DISPATCHED，**此后绝不重发**。回执丢了怎么办？记成
     * 「结果未知」（见 {@code SmsOutboundJanitor}），由人去对账 —— 不靠重发去确认，
     * 因为重发是真的又发一条出去、又计费一次。
     */
    public List<SmsOutboundPayload> claimForDelivery(String deviceCode) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceCode).orElse(null);
        if (device == null) {
            return List.of();
        }

        List<SmsOutbound> due = outboundRepository.findDeliverable(
                device.getId(), SmsOutboundStatus.PENDING, PageRequest.of(0, MAX_DELIVER_PER_HEARTBEAT));
        if (due.isEmpty()) {
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        List<SmsOutboundPayload> payloads = new ArrayList<>(due.size());
        for (SmsOutbound outbound : due) {
            outbound.setStatus(SmsOutboundStatus.DISPATCHED);
            outbound.setDispatchedAt(now);
            payloads.add(new SmsOutboundPayload(
                    outbound.getId(), outbound.getOutboundKey(), outbound.getPhone(),
                    outbound.getContent(), outbound.getSimSlot()));
        }
        outboundRepository.saveAll(due);
        broadcast();

        log.info("交给设备 {} 条外发短信：{} 条", deviceCode, payloads.size());
        return payloads;
    }

    // ------------------------------------------------------------------ 回执

    /**
     * 设备回报一批发送结果（随心跳带上），返回**认下的条数**。
     *
     * <p>三个要点：
     *
     * <ul>
     *   <li><b>作用域判定</b>：key 必须属于这台设备。少了它，拿 A 设备令牌的人能把
     *       B 设备的外发短信标成「已发出」—— 而那条还躺在队列里没发出去。</li>
     *   <li><b>幂等</b>：设备会在每一次心跳里重复带上未确认的结果（它不知道服务端
     *       收没收到），所以同一份结果会被应用很多次。已经终态的跳过即可。</li>
     *   <li><b>UNKNOWN 也接受回执</b>：那是「我们不知道结果」，而迟到的真相比
     *       「不知道」好。清理任务判成 UNKNOWN 之后回执才到的情形虽然少见，但确实存在
     *       （设备离线了一阵，回来的第一次心跳就把它带上了）。</li>
     * </ul>
     */
    public int applyResults(String deviceCode, List<OutboundResult> results) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceCode).orElse(null);
        if (device == null || results == null || results.isEmpty()) {
            return 0;
        }

        Set<String> keys = results.stream()
                .map(OutboundResult::getKey)
                .filter(k -> k != null && !k.isBlank())
                .collect(Collectors.toSet());
        if (keys.isEmpty()) {
            return 0;
        }

        List<SmsOutbound> owned = outboundRepository.findByOutboundKeyInAndDeviceId(keys, device.getId());
        Map<String, OutboundResult> byKey = new HashMap<>();
        for (OutboundResult result : results) {
            byKey.put(result.getKey(), result);
        }

        LocalDateTime now = LocalDateTime.now();
        List<SmsOutbound> changed = new ArrayList<>();
        for (SmsOutbound outbound : owned) {
            SmsOutboundStatus current = outbound.getStatus();
            if (current != SmsOutboundStatus.DISPATCHED && current != SmsOutboundStatus.UNKNOWN) {
                // PENDING（还没交出去）/ 已终态：都不该被回执改动
                continue;
            }

            OutboundResult result = byKey.get(outbound.getOutboundKey());
            SmsOutboundStatus target = mapStatus(result.getStatus());
            if (target == null) {
                log.warn("设备回执了不认识的外发状态：key={}, status={}",
                        outbound.getOutboundKey(), result.getStatus());
                continue;
            }

            outbound.setStatus(target);
            outbound.setErrorReason(truncate(result.getErrorReason(), 255));
            // 分段数由设备回报：只有它知道这条长短信实际被拆成了几条（那直接等于计费条数）
            if (result.getSegments() != null && result.getSegments() > 0) {
                outbound.setSegments(result.getSegments());
            }
            if (target == SmsOutboundStatus.SENT) {
                outbound.setSentAt(now);
                if ("DELIVERED".equalsIgnoreCase(safeTrim(result.getStatus()))) {
                    outbound.setDeliveredAt(now);
                }
            }
            changed.add(outbound);
        }

        if (changed.isEmpty()) {
            return owned.size();
        }

        outboundRepository.saveAll(changed);
        for (SmsOutbound outbound : changed) {
            eventLogService.record(
                    outbound.getStatus() == SmsOutboundStatus.SENT
                            ? EventType.SMS_OUTBOUND_SENT
                            : EventType.SMS_OUTBOUND_FAILED,
                    device,
                    "发给 " + outbound.getPhone() + "："
                            + (outbound.getErrorReason() == null ? "已发出" : outbound.getErrorReason()));
        }
        broadcast();

        // 返回「认下」的条数而不是「改动了」的条数：设备据此清掉本地待上报的结果。
        // 返回改动数的话，一次重发（0 改动）会被设备理解成「服务端没收到」，于是永远重报。
        return owned.size();
    }

    /** @return null 表示不认识的状态，调用方跳过而不是当成失败。 */
    private static SmsOutboundStatus mapStatus(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "SENT", "DELIVERED" -> SmsOutboundStatus.SENT;
            case "FAILED" -> SmsOutboundStatus.FAILED;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 撤销 / 查询

    /**
     * 撤销一条还没下发的。
     *
     * <p>已经交给设备的**不能撤**：那时它可能已经在对方手机上发出去了，
     * 「撤销」在界面上会读成「那条没发出去」，而实际上它发了。
     */
    public SmsOutboundView cancel(Long id) {
        SmsOutbound outbound = outboundRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("外发记录不存在: " + id));

        if (outbound.getStatus() == SmsOutboundStatus.DISPATCHED) {
            throw new IllegalArgumentException(
                    "这条已经下发给设备了，无法撤销 —— 它可能已经发了出去。");
        }
        if (outbound.getStatus().isTerminal()) {
            throw new IllegalArgumentException(
                    "这条已经结束（" + outbound.getStatus().label() + "），无法撤销");
        }

        outbound.setStatus(SmsOutboundStatus.CANCELLED);
        outboundRepository.save(outbound);

        eventLogService.recordWithIdentity(EventType.SMS_OUTBOUND_CANCELLED,
                outbound.getDeviceId(), outbound.getDeviceCode(),
                "发给 " + outbound.getPhone());
        broadcast();

        log.info("撤销外发短信 {}", id);
        SmsDevice device = deviceRepository.findByDeviceId(outbound.getDeviceCode()).orElse(null);
        return toView(outbound, device);
    }

    /**
     * 重发一条**失败的**。
     *
     * <p><b>只允许从 FAILED 重发，UNKNOWN 一律拒绝。</b>这是整个外发功能里最要紧的一条界线：
     *
     * <ul>
     *   <li>{@code FAILED} 是**设备明确说了「没发出去」**（无信号、没权限、内容无效…）。
     *       重发它不会重复计费 —— 上一次根本没发出去。</li>
     *   <li>{@code UNKNOWN} 是「不知道发没发」。重发可能变成**真的发第二条**，
     *       而对方会收到两条一样的短信、我们被计费两次。所以那条路只能靠人去对账，
     *       不能给一个点了就重发的按钮。</li>
     * </ul>
     *
     * <p>不新建一行、也不动每日限额的计数：这本来就是同一条短信第二次尝试，
     * 而限额防的是「一次误操作发一批」，不是「同一条重试几次」。
     */
    public SmsOutboundView retry(Long id) {
        SmsOutbound outbound = outboundRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("外发记录不存在: " + id));

        if (outbound.getStatus() == SmsOutboundStatus.UNKNOWN) {
            throw new IllegalArgumentException(
                    "这条的结果未知（设备一直没回执），重发可能让它真的发出第二条、并被计费两次。"
                            + "请先在那台手机上确认它到底发出去没有。");
        }
        if (outbound.getStatus() != SmsOutboundStatus.FAILED) {
            throw new IllegalArgumentException(
                    "只有「发送失败」的能重发。这条现在是「" + outbound.getStatus().label() + "」。");
        }

        outbound.setStatus(SmsOutboundStatus.PENDING);
        outbound.setErrorReason(null);
        outbound.setDispatchedAt(null);
        outboundRepository.save(outbound);

        SmsDevice device = deviceRepository.findByDeviceId(outbound.getDeviceCode()).orElse(null);
        eventLogService.recordWithIdentity(EventType.SMS_OUTBOUND_QUEUED,
                outbound.getDeviceId(), outbound.getDeviceCode(),
                "重发：" + outbound.getPhone());
        broadcast();

        log.info("重发外发短信 {}（原失败原因已清）", id);
        return toView(outbound, device);
    }

    public PageResult<SmsOutboundView> list(String deviceCode, String status, int page, int pageSize) {
        SmsOutboundStatus parsed = parseStatus(status);

        Page<SmsOutbound> result = outboundRepository.search(
                parsed == null,
                parsed == null ? SmsOutboundStatus.PENDING : parsed,
                blankToNull(deviceCode),
                PageRequest.of(page - 1, pageSize));

        List<SmsOutbound> rows = result.getContent();
        Map<Long, SmsDevice> devices = devicesOf(rows);
        List<SmsOutboundView> views = rows.stream()
                .map(row -> toView(row, devices.get(row.getDeviceId())))
                .collect(Collectors.toList());

        return PageResult.of(views, result.getTotalElements(), page, pageSize);
    }

    /** 对外接口查一条的状态。查不到时抛 400（调用方拿到的 id 一定是它自己刚建的）。 */
    public ClientOutboundView clientView(Long id) {
        SmsOutbound outbound = outboundRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("外发记录不存在: " + id));
        return new ClientOutboundView(outbound.getId(), outbound.getPhone(),
                outbound.getStatus().name(), outbound.getCreatedAt(),
                outbound.getSentAt(), outbound.getErrorReason());
    }

    // ------------------------------------------------------------------ 辅助

    private SmsOutboundStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SmsOutboundStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的外发状态：" + raw);
        }
    }

    /** 一次查全这一页涉及的设备，避免逐条 findById（N+1）。 */
    private Map<Long, SmsDevice> devicesOf(List<SmsOutbound> rows) {
        Set<Long> ids = rows.stream()
                .map(SmsOutbound::getDeviceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, SmsDevice> devices = new HashMap<>();
        for (SmsDevice device : deviceRepository.findAllById(ids)) {
            devices.put(device.getId(), device);
        }
        return devices;
    }

    private SmsOutboundView toView(SmsOutbound outbound, SmsDevice device) {
        return new SmsOutboundView(
                outbound.getId(),
                outbound.getDeviceCode(),
                device == null ? null : device.getDeviceName(),
                outbound.getPhone(),
                outbound.getContent(),
                outbound.getStatus().name(),
                outbound.getStatus().label(),
                outbound.getSource(),
                outbound.getCreatedBy(),
                outbound.getSegments(),
                outbound.getErrorReason(),
                outbound.getDispatchedAt(),
                outbound.getSentAt(),
                outbound.getDeliveredAt(),
                outbound.getCreatedAt());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String safeTrim(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void broadcast() {
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_OUTBOUND, Map.of());
    }

    /** 给清理任务用：把「下发很久没回执」的判成结果未知。 */
    void markUnknown(SmsOutbound outbound) {
        outbound.setStatus(SmsOutboundStatus.UNKNOWN);
        outbound.setErrorReason("设备一直没回执，实际有没有发出去无法确认");
        outboundRepository.save(outbound);
        eventLogService.recordWithIdentity(EventType.SMS_OUTBOUND_UNKNOWN,
                outbound.getDeviceId(), outbound.getDeviceCode(),
                "发给 " + outbound.getPhone() + "：设备一直没回执");
    }

    /** 清理任务用。包级可见只为单测。 */
    List<SmsOutbound> findStuckDispatched(LocalDateTime before, int batchSize) {
        return outboundRepository.findStuckDispatched(
                SmsOutboundStatus.DISPATCHED, before, PageRequest.of(0, batchSize));
    }
}
