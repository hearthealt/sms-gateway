package com.smsgateway.service;

import com.smsgateway.model.dto.DeviceCommandAckItem;
import com.smsgateway.model.dto.DeviceCommandPayload;
import com.smsgateway.model.dto.DeviceCommandView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.entity.DeviceCommand;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.model.enums.DeviceCommandStatus;
import com.smsgateway.model.enums.DeviceCommandType;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.repository.DeviceCommandRepository;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.service.notify.AlertOutbox;
import com.smsgateway.service.notify.AlertSubjects;
import com.smsgateway.util.PhoneUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 远程指令：服务端 → 设备的下行通道。
 *
 * <p><b>传输决定：指令搭心跳响应下发，不新开长轮询。</b>
 *
 * <ol>
 *   <li>设备没有第二条通往服务端的常连通道，而且它也不需要。部署在内网、只有扫码配网，
 *       没有 FCM/APNs —— 任何「服务端主动推」的方案在物理上都不成立，剩下的只有
 *       「设备来问」。而设备**已经**每 30 秒在问一次了。</li>
 *   <li>长轮询要多造一整条连接生命周期：Tomcat 侧的 hold 与超时、设备侧的重连与退避、
 *       与在线判定的冲突（长轮询挂着时到底算不算在线），以及一个必然出现的需求
 *       「长轮询断了怎么降级回心跳」—— 于是两套并存。换来的只是 30 秒 → 约 0 秒的
 *       延迟改进，而这几条指令**没有一条是时间敏感的**。</li>
 *   <li>心跳已经是设备学习服务端状态的唯一通道（{@code HeartbeatResponse.status}），
 *       把指令塞进同一个响应不引入新的信任边界，而新端点会。</li>
 * </ol>
 *
 * <p>被否决的第三个方案是「下发指令后临时把心跳提到 5 秒一次」：那是没有具体需求的
 * 配置面，而且它让「设备的心跳节奏」变成服务端可变状态 —— 排查线上问题时多一个未知量。
 *
 * <p><b>SENT 是个会被长期停留的状态，会被重复下发直到回执或过期。</b>这是
 * 「效果执行了、但回执丢在路上」的答案：那条指令停在 SENT，下一个心跳再下发一次，
 * 设备按 command id 认出「这条我做过」，于是**不重复执行、只把上次的结论重发一遍回执**。
 * 服务端收到即转 ACKED。
 *
 * <p>为什么不做「恰好一次投递」：那需要服务端的 claim/lease 协议（SENT 带一个租约，
 * 租约到期才允许重发），而设备侧每种指令本来就幂等（起一个已在跑的服务、删一次
 * 已上传行都是空操作）。投递语义的正确性有便宜一个数量级的实现方式。
 * 这条也是对「两台心跳并发捞到同一条 PENDING」的正面回答：**两个心跳都会下发它，
 * 设备看到两次 id 相同的指令，执行一次。这不是 bug，是设计。**
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCommandService {

    /**
     * 一次心跳最多下发几条。
     *
     * <p>不需要更大：这是「管理员手上点出来的动作」，不是批量任务，而每次心跳的
     * 响应体还会被设备解析。真的积压了十几条，多花几个心跳下发完也毫无影响。
     */
    private static final int MAX_DELIVER_PER_HEARTBEAT = 5;

    /**
     * 同一条指令两次下发之间的间隔。
     *
     * <p>必须**大于**心跳周期（30 秒）：相等的话每次心跳都会把它重塞一遍，
     * 而设备那边其实只是在回执链路上慢了一拍。取 90 秒留出余量。
     */
    private static final Duration REDELIVER_INTERVAL = Duration.ofSeconds(90);

    /**
     * 下发次数上限。用尽即放弃，由 {@link DeviceCommandJanitor} 判成 EXPIRED。
     *
     * <p>10 次 × 90 秒 ≈ 15 分钟。再久就不是「回执丢了」，而是设备根本没在执行，
     * 而一条永远挂在「已下发」的指令比一条明确失败的指令更难查。
     */
    static final int MAX_ATTEMPTS = 10;

    /**
     * 「还没结束」的状态：还能被下发，也还能被撤销。
     *
     * <p>撤销一条**已下发但没回执**的指令是常用操作（设备离线时发现点错了），
     * 所以 SENT 不能算终态。
     */
    private static final Set<DeviceCommandStatus> OPEN_STATUSES =
            EnumSet.of(DeviceCommandStatus.PENDING, DeviceCommandStatus.SENT);

    private final DeviceCommandRepository commandRepository;
    private final DeviceRepository deviceRepository;
    private final EventLogService eventLogService;
    private final AdminEventBroadcaster adminEvents;
    private final AlertOutbox alertOutbox;

    // ------------------------------------------------------------------ 签发

    /**
     * 签发一条指令。同一台设备上同类型的未结束指令**只保留一条**，重复签发返回已有的那条。
     *
     * <p>不加 {@code @Transactional}：{@code EventLogService.record} 标的是 REQUIRES_NEW，
     * 必须在外层事务**之外**调用，否则事件会先于业务提交落库（理由见那个类的类注释）。
     * 这里只有一次 save，本来也不需要事务。
     */
    public DeviceCommandView issue(String deviceCode, DeviceCommandType type, String rawArgument, String issuedBy) {
        if (type == null) {
            throw new IllegalArgumentException("指令类型不能为空");
        }

        SmsDevice device = deviceRepository.findByDeviceId(deviceCode)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceCode));

        String argument = normalizeArgument(type, rawArgument);

        // 同类型去重：管理员连点两下「停止网关」不该产生两条记录。第二条必然在第一条
        // 生效之后才送达，而它带来的效果完全相同 —— 多出来的那条只会在列表上制造
        // 「怎么有两条、是不是要执行两次」的疑问。
        Optional<DeviceCommand> pending = commandRepository
                .findFirstByDeviceIdAndCommandTypeAndStatusInOrderByIdDesc(device.getId(), type, OPEN_STATUSES);
        if (pending.isPresent()) {
            log.info("Device {} already has an open {} command (id={}), returning it",
                    deviceCode, type, pending.get().getId());
            // 不记 DEVICE_COMMAND_ISSUED：没有新指令产生。但照样广播一声，
            // 好让此刻打开着这个设备详情页的人看到「原来已经有一条在等着了」。
            broadcast(deviceCode);
            return toView(pending.get(), device);
        }

        LocalDateTime now = LocalDateTime.now();
        DeviceCommand command = new DeviceCommand();
        command.setDeviceId(device.getId());
        command.setDeviceCode(device.getDeviceId());
        command.setCommandType(type);
        command.setArgument(argument);
        command.setStatus(DeviceCommandStatus.PENDING);
        command.setAttempts(0);
        command.setNextDeliverAt(now);
        // 过期时刻**在签发时按类型定死并落库**，不在查询时算：将来调整了 TTL，
        // 历史记录的解读不会跟着漂移。
        command.setExpiresAt(now.plus(type.ttl()));
        command.setIssuedBy(issuedBy);
        commandRepository.save(command);

        eventLogService.record(EventType.DEVICE_COMMAND_ISSUED, device, describe(type, argument));
        broadcast(deviceCode);

        log.info("Issued device command: device={} type={} id={} by={}", deviceCode, type, command.getId(), issuedBy);
        return toView(command, device);
    }

    /**
     * 参数校验与规整。
     *
     * <p><b>{@code RE_REGISTER} 那条分支是本类最要紧的一段。</b>「远程重新注册」听起来
     * 自然的意思是「服务端把一份新的重注册密钥交给设备」，本项目**刻意不做**，
     * 理由是一条跨越密钥轮换的权限提升：
     *
     * <p>{@code deviceToken = HMAC-SHA256(deviceId, app.secret.key)} 是确定性推导 ——
     * 拿到主密钥的人本来就能冒充任意设备（README 安全章节第 2 条），这一点无法避免；
     * 但**轮换主密钥会让所有已签发令牌立即失效**，那是现有的止损手段。而一旦新增一条
     * 能把明文 {@code enrollSecret} 送到设备手上的下行通道，攻击者就能在被清理之前
     * 换一份新的重注册密钥 —— 主密钥轮换之后他仍然控制着那台设备，
     * **恢复手段被指令本身废掉了**。
     *
     * <p>第二条理由是这条指令的目的自相矛盾：恢复码存在的全部前提是「设备**丢了**密钥」，
     * 而能收到下行指令的设备手里有一份**能用**的 deviceToken。所以「远程重新注册」的
     * 真实含义只能是「主动轮换一个没丢的密钥」，而没有人提出过这个需求。
     *
     * <p>落地的是安全版本：{@link DeviceCommandType#RE_REGISTER} 让设备**用本机已有的**
     * 身份重跑一次注册，没有任何密钥走网络。设备真丢了密钥时，唯一的路是管理员在控制台
     * 签发恢复码、把二维码交给现场的人扫 —— 二维码必须进到手机的摄像头前，
     * 这是物理约束，不是设计缺陷。
     */
    private String normalizeArgument(DeviceCommandType type, String rawArgument) {
        String trimmed = rawArgument == null ? null : rawArgument.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }

        if (type == DeviceCommandType.RE_REGISTER && trimmed != null) {
            throw new IllegalArgumentException(
                    "重新注册不接受任何参数：这条指令只能让设备用**本机已有的**身份重跑一次注册。"
                            + "服务端不会、也不应当经下行通道下发新的重注册密钥 —— 那会让 app.secret.key "
                            + "泄漏后无法通过轮换止损（攻击者可以在被清理前换一份新密钥，"
                            + "从而在轮换之后继续控制那台设备）。"
                            + "设备真的丢了密钥时，请在控制台「生成恢复码」并把二维码交给现场的人扫。");
        }

        if (!type.requiresArgument()) {
            if (trimmed != null) {
                // 拒绝而不是忽略：参数会被静默丢掉，而调用方以为它生效了。
                // 「写死的东西不会配错」—— 这里连「允许传但不作数」这种半截状态都不留。
                throw new IllegalArgumentException(type.label() + " 不接受参数");
            }
            return null;
        }

        if (trimmed == null) {
            throw new IllegalArgumentException(type.label() + " 必须提供参数");
        }
        // 号码本身允许各种写法（带 +86、带空格横线），但必须能归一化出足够的数字位数，
        // 否则设备那边会存下一个既匹配不上任何短信、又看起来像配好了的号码。
        if (PhoneUtil.normalize(trimmed).length() < 5) {
            throw new IllegalArgumentException("号码格式不正确：至少需要 5 位数字");
        }
        // 与 sms_device.phone_number 的列宽（32）和 HeartbeatRequest 的 @Size 对齐。
        if (trimmed.length() > 32) {
            throw new IllegalArgumentException("号码超长（上限 32）");
        }
        return trimmed;
    }

    private static String describe(DeviceCommandType type, String argument) {
        return argument == null ? type.label() : type.label() + "：" + argument;
    }

    /** 告警摘要里用什么称呼这台设备：名字可能为空，那时退回业务标识（不能是空白）。 */
    private static String displayName(SmsDevice device) {
        return device.getDeviceName() == null || device.getDeviceName().isBlank()
                ? device.getDeviceId()
                : device.getDeviceName();
    }

    // ------------------------------------------------------------------ 下发

    /**
     * 心跳里调：捞出该下发的指令，并就地转成 SENT。
     *
     * @param probeOnly true 表示这是「网关已停止」时的低频探测心跳，只下发
     *                  {@link DeviceCommandType#START_GATEWAY}。理由见
     *                  {@code CommandProbeWorker} 与 README：用户停网关的意图是
     *                  「这台机器冻结住，短信留在本地」，从 WorkManager 上下文里
     *                  执行「清理已上传记录」或「改号码」是在**违背这个意图改本机状态**，
     *                  而且是在用户看不见的地方。
     */
    public List<DeviceCommandPayload> claimForDelivery(String deviceCode, boolean probeOnly) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceCode).orElse(null);
        if (device == null) {
            // 设备行不在（刚被删）—— 心跳本来就会 500，这里是防御性的静默返回。
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        Collection<DeviceCommandType> types = probeOnly
                ? EnumSet.of(DeviceCommandType.START_GATEWAY)
                : EnumSet.allOf(DeviceCommandType.class);

        List<DeviceCommand> due = commandRepository.findDeliverable(
                device.getId(), OPEN_STATUSES, types, MAX_ATTEMPTS, now,
                PageRequest.of(0, MAX_DELIVER_PER_HEARTBEAT));
        if (due.isEmpty()) {
            return List.of();
        }

        List<DeviceCommandPayload> payloads = new ArrayList<>(due.size());
        for (DeviceCommand command : due) {
            command.setStatus(DeviceCommandStatus.SENT);
            command.setSentAt(now);
            // attempts 在这里自增，是为了让「发给谁都不回」的那条最终能被放弃。
            // 两台心跳并发时这个自增可能丢一次（双方都读到同一个旧值）——
            // 可以接受：它只影响「还要再试几次」的计数，不影响正确性。
            command.setAttempts(command.getAttempts() + 1);
            command.setNextDeliverAt(now.plus(REDELIVER_INTERVAL));
            payloads.add(new DeviceCommandPayload(
                    command.getId(),
                    command.getCommandType().name(),
                    command.getArgument(),
                    command.getExpiresAt()));
        }
        commandRepository.saveAll(due);

        log.debug("Delivered {} command(s) to device {} (probeOnly={})", payloads.size(), deviceCode, probeOnly);
        return payloads;
    }

    // ------------------------------------------------------------------ 回执

    /**
     * 设备回执一批执行结果，返回**认下的条数**。
     *
     * <p>作用域判定（{@code deviceId} 必须匹配）不是可选项：少了它，拿着 A 设备令牌的
     * 人就能改 B 设备指令的状态。
     *
     * <p>重复回执幂等：已经走到终态的行不再改动，但仍计入「认下」的条数 ——
     * 设备据此停止重发，而不会以为自己发的东西服务端没收到。
     */
    public int ack(String deviceCode, List<DeviceCommandAckItem> items) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceCode)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceCode));

        List<Long> ids = items.stream().map(DeviceCommandAckItem::getId).distinct().toList();
        Map<Long, DeviceCommand> owned = new HashMap<>();
        for (DeviceCommand command : commandRepository.findByIdInAndDeviceId(ids, device.getId())) {
            owned.put(command.getId(), command);
        }

        Map<Long, DeviceCommandAckItem> byId = new HashMap<>();
        for (DeviceCommandAckItem item : items) {
            byId.put(item.getId(), item);
        }

        List<DeviceCommand> changed = new ArrayList<>();
        for (Map.Entry<Long, DeviceCommand> entry : owned.entrySet()) {
            DeviceCommand command = entry.getValue();
            if (command.getStatus().isTerminal()) {
                // 回执丢过一次、设备重发了：不改任何字段，但也不报错。
                log.debug("Duplicate ack for device command {} (already {})", command.getId(), command.getStatus());
                continue;
            }

            DeviceCommandAckItem item = byId.get(entry.getKey());
            DeviceCommandStatus target = mapAckStatus(item.getStatus());
            command.setStatus(target);
            command.setAckedAt(LocalDateTime.now());
            command.setResultDetail(truncate(item.getDetail(), 255));
            changed.add(command);
        }

        if (!changed.isEmpty()) {
            commandRepository.saveAll(changed);

            boolean stoppedGateway = false;
            for (DeviceCommand command : changed) {
                boolean ok = command.getStatus() == DeviceCommandStatus.ACKED;
                eventLogService.record(
                        ok ? EventType.DEVICE_COMMAND_ACKED : EventType.DEVICE_COMMAND_FAILED,
                        device,
                        describe(command.getCommandType(), command.getResultDetail()));
                if (ok && command.getCommandType() == DeviceCommandType.STOP_GATEWAY) {
                    stoppedGateway = true;
                } else if (!ok) {
                    // 执行失败要**主动叫人**：远程指令给了管理员「不看现场就能动手」的
                    // 能力，而一条没执行成功的指令如果只写进事件表，那就退化成了它本该
                    // 消灭的那类静默失败 —— 管理员以为停好了，设备还在上报。
                    alertOutbox.enqueue(
                            AlertType.DEVICE_COMMAND_FAILED,
                            AlertSubjects.device(device.getDeviceId()),
                            "设备「" + displayName(device) + "」的远程指令「"
                                    + command.getCommandType().label() + "」执行失败："
                                    + (command.getResultDetail() == null ? "无说明" : command.getResultDetail()));
                }
            }

            if (stoppedGateway) {
                markReportedOffline(device);
            }

            broadcast(deviceCode);
        }

        // 返回「认下」的条数而不是「改动了」的条数：设备用它判断服务端是否收到了
        // 这条回执。返回改动数的话，一次重发（0 改动）会被设备理解成「服务端没收到」，
        // 于是永远重发下去。
        return owned.size();
    }

    /**
     * 收到「停止网关已执行」的回执时，服务端自己也补一次「主动报停」。
     *
     * <p><b>为什么必须有这一手。</b>设备执行远程停机时也会自己调一次
     * {@code /api/device/offline}，但那是**尽力而为**的（网络断了就发不出去）。
     * 少了这一手，那种情况下服务端会把这次停机判成「心跳超时掉线」——
     * 于是 {@code reported_offline_at} 没被更新，紧接着上线的故障告警会把管理员
     * **自己刚做的事**报成一条「设备离线」。
     *
     * <p>两道防线是刻意的，互为兜底：设备那道立刻生效（不必等回执），
     * 这道只要回执送达就一定成立（不必等网络那一刻好不好）。
     *
     * <p>{@code reported_offline_at} 的语义在这里有一点点扩展：原本写的是
     * 「设备主动报告的时刻」，现在还包括「服务端确知有人主动停了它」。
     * 这个扩展是成立的 —— {@link DeviceService#isOnline} 用它表达的是
     * 「在有人主动停过之后，必须来一次更新的心跳才算重新上线」，
     * 而这正是远程停机之后该有的行为。
     *
     * <p>**不记 DEVICE_OFFLINE_REPORTED 事件**：设备自己那次会记一条，
     * 这里再记就是同一个动作两条事件。是谁停的由紧邻的 DEVICE_COMMAND_ACKED 说明。
     */
    private void markReportedOffline(SmsDevice device) {
        device.setReportedOfflineAt(LocalDateTime.now());
        deviceRepository.save(device);
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_DEVICES,
                Map.of("deviceId", device.getDeviceId()));
        log.info("Device {} marked offline after remote STOP_GATEWAY was acked", device.getDeviceId());
    }

    /**
     * 设备回报的状态 → 服务端状态。
     *
     * <p>用 String 而不是枚举接：设备发来一个不认识的值时，枚举会让 Jackson 抛
     * {@code HttpMessageNotReadableException}，而全局异常处理器没有接它 —— 结果是
     * 设备拿到 **500**「服务器内部错误」，而真相只是「设备端的词表比服务端新」。
     */
    private DeviceCommandStatus mapAckStatus(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("回执缺少 status");
        }
        return switch (raw.trim().toUpperCase()) {
            case "DONE" -> DeviceCommandStatus.ACKED;
            // REJECTED 是设备说「我看懂了这条指令，但我做不到」（例如旧版本 App 遇到了
            // 一个它不认识的新类型）。它与 FAILED 在服务端是同一个终态，但**必须存在**：
            // 没有这一档，那种指令会在「设备连续不回执」中慢慢耗到 EXPIRED，
            // 管理员看到的只是「设备没反应」，而真正的原因在客户端版本上。
            case "FAILED", "REJECTED" -> DeviceCommandStatus.FAILED;
            default -> throw new IllegalArgumentException("不认识的回执状态: " + raw);
        };
    }

    // ------------------------------------------------------------------ 撤销 / 查询

    public DeviceCommandView cancel(Long id) {
        DeviceCommand command = commandRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("指令不存在: " + id));

        if (command.getStatus().isTerminal()) {
            throw new IllegalArgumentException("这条指令已经结束（" + command.getStatus().label() + "），无法撤销");
        }

        command.setStatus(DeviceCommandStatus.CANCELLED);
        commandRepository.save(command);

        // 用 recordWithIdentity 而不是 record(type, device, ...)：撤销这条路径上
        // 手上只有指令里的设备标识，为此再查一次设备行只是白跑一趟。
        eventLogService.recordWithIdentity(EventType.DEVICE_COMMAND_CANCELLED, command.getDeviceId(),
                command.getDeviceCode(), command.getCommandType().label());
        broadcast(command.getDeviceCode());

        log.info("Cancelled device command {} for device {}", id, command.getDeviceCode());
        return toView(command, null);
    }

    public PageResult<DeviceCommandView> list(String deviceCode, int page, int pageSize) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceCode)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceCode));

        Page<DeviceCommand> result = commandRepository.findByDeviceId(
                device.getId(), PageRequest.of(page - 1, pageSize));

        List<DeviceCommandView> records = result.getContent().stream()
                .map(command -> toView(command, device))
                .collect(Collectors.toList());

        return PageResult.of(records, result.getTotalElements(), page, pageSize);
    }

    private DeviceCommandView toView(DeviceCommand command, SmsDevice device) {
        return new DeviceCommandView(
                command.getId(),
                command.getDeviceCode(),
                device == null ? null : device.getDeviceName(),
                command.getCommandType().name(),
                command.getCommandType().label(),
                command.getArgument(),
                command.getStatus().name(),
                command.getStatus().label(),
                command.getAttempts(),
                command.getNextDeliverAt(),
                command.getSentAt(),
                command.getExpiresAt(),
                command.getAckedAt(),
                command.getResultDetail(),
                command.getIssuedBy(),
                command.getCreatedAt());
    }

    private void broadcast(String deviceCode) {
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_COMMANDS,
                Map.of("deviceId", deviceCode == null ? "" : deviceCode));
    }

    /** 与 device_command.result_detail 的列宽一致。 */
    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
