package com.smsgateway.service;

import com.smsgateway.model.dto.SmsReceiveRequest;
import com.smsgateway.model.dto.SmsReceiveResponse;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.model.enums.SmsStatus;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsMessageRepository;
import com.smsgateway.service.notify.NotifyOutbox;
import com.smsgateway.util.CodeExtractor;
import com.smsgateway.util.HashUtil;
import com.smsgateway.util.PhoneUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsService {

    private final SmsMessageRepository smsMessageRepository;
    private final DeviceRepository deviceRepository;
    private final StringRedisTemplate redisTemplate;
    private final CollectRuleEngine collectRuleEngine;

    /**
     * 运行事件。**只在业务事务之外调用** —— 它标着 REQUIRES_NEW，
     * 在事务里调会让事件先于业务提交落库。理由见 {@link EventLogService} 的类注释。
     */
    private final EventLogService eventLogService;

    /**
     * 编程式事务。理由与 {@link DeviceService} 相同：需要「撞唯一约束后另起一次查询」的兜底，
     * 而 {@code @Transactional} 一旦被标记 rollback-only，同一事务里后续查询会直接失败。
     */
    private final TransactionTemplate transactionTemplate;

    /** 有新短信（或重复计数变化）时推一条，让管理后台自己去拉最新数据。 */
    private final AdminEventBroadcaster adminEvents;

    /**
     * 事务性发件箱的写入侧。见 {@link NotifyOutbox} 的说明 ——
     * 它必须在本方法的**事务内**被调用，这是「不存在『短信存了但没转发』」的全部实现。
     */
    private final NotifyOutbox notifyOutbox;

    private static final String SMS_CODE_KEY_PREFIX = "sms:code:";
    /** 验证码的写入时刻（epoch 毫秒）。与验证码同 TTL，见写缓存处。 */
    private static final String SMS_CODE_AT_KEY_PREFIX = "sms:code:at:";
    private static final long CODE_TTL_SECONDS = 300; // 5 min

    /**
     * 一次上报在服务端这一侧的结果。
     *
     * <p>存在的唯一理由是**把「这条短信最后怎么了」带出事务**：原先 {@code doReceiveSms}
     * 只返回给设备看的响应，而事件需要额外知道「命中了哪条采集规则」—— 那是排查
     * 「为什么这条没进系统」时最关键的一句话，却只活在事务内的一个局部变量里。
     *
     * @param response  回给设备端的响应，内容与改动前完全一致
     * @param eventType 这次上报该记成哪种运行事件（存下 / 重复 / 被规则忽略）
     * @param reason    事件的原因文案；命中的规则名在这里面（那正是原先带不出事务的东西）
     * @param sms       落库（或命中的既有）那条短信，供事件带上发送方与号码；可能为 null
     */
    private record ReceiveOutcome(SmsReceiveResponse response,
                                  EventType eventType,
                                  String reason,
                                  SmsMessage sms) {
    }

    /**
     * 上报一条短信。
     *
     * <p>用编程式事务而不是 {@code @Transactional}，是为了能接住并发下的唯一约束冲突 ——
     * 见 catch 块里的说明。写法与 {@link DeviceService#register} 一致。
     */
    public SmsReceiveResponse receiveSms(String authenticatedDeviceId, SmsReceiveRequest request) {
        // 先查设备：事件要带上「是哪台机器」。查不到时为 null（下面 doReceiveSms 会抛），
        // 那种情况下事件仍然要记 —— 一条认不出设备的异常上报恰恰是最该看的。
        SmsDevice device = deviceRepository.findByDeviceId(authenticatedDeviceId).orElse(null);

        ReceiveOutcome outcome;
        try {
            outcome = transactionTemplate.execute(status -> doReceiveSms(authenticatedDeviceId, request));
        } catch (DataIntegrityViolationException e) {
            // 同一台设备**同时**上报内容相同的短信时，双方都会在判重那一步扑空、
            // 都走到插入，后落地的那个撞 uk_device_source_hash。
            //
            // 内容相同本来就意味着这是重复上报，所以重查一次按「重复」返回即可，
            // 不能让它变成 500 —— 设备端把 500 归为可重试，这条短信会永远传不上去、
            // 一直烧电重试。这与「重复短信分支自己撞约束」是同一个症状的两个入口。
            //
            // 必须在事务**外面**接：事务一旦被标记 rollback-only，同一个事务里再查询会直接失败。
            //
            // 这里要多查一次设备主键：判重已经收窄到设备内，重查也得带上设备条件，
            // 否则并发时会把**别的设备**收到同一段内容的那条当成自己的重复返回。
            log.info("Concurrent duplicate detected, returning existing row as duplicate");
            Long devicePk = deviceRepository.findByDeviceId(authenticatedDeviceId)
                    .map(d -> d.getId())
                    .orElse(null);
            outcome = smsMessageRepository.findByDeviceIdAndSourceHash(
                            devicePk, HashUtil.sha256(request.getContent()))
                    .map(existing -> new ReceiveOutcome(
                            new SmsReceiveResponse(existing.getId(), true, SmsStatus.DUPLICATE.name(), null),
                            EventType.SMS_DUPLICATE,
                            "并发撞唯一约束，判定为同内容重复",
                            existing))
                    .orElseThrow(() -> e);
        }

        recordReceiveEvent(device, outcome);
        return outcome.response();
    }

    /**
     * 把这次上报的结果记成一条运行事件。
     *
     * <p><b>位置很关键</b>：在 {@code transactionTemplate.execute} **返回之后**调用。
     * 事件标着 REQUIRES_NEW，在事务里调会让「存下」先于业务提交落库 ——
     * 万一业务随后回滚，那条事件就成了假记录，会把排查引到完全错误的方向。
     * 反过来，业务真的回滚了也不影响已经提交的事件（这正是我们要的：
     * 「被拒」「冲突」描述的本来就是没成功的那一次）。
     *
     * <p>写事件失败不能把上报本身打回去：设备那边已经把短信交出去了，
     * 这里抛异常只会让它白重试一遍。所以整段吞掉异常只记日志 —— 与设备端
     * {@code SmsReceiver} 里「入库失败只记日志」是同一个取舍。
     */
    private void recordReceiveEvent(SmsDevice device, ReceiveOutcome outcome) {
        try {
            eventLogService.recordForSms(outcome.eventType(), device, outcome.sms(), outcome.reason());
        } catch (Exception e) {
            log.warn("Failed to record receive event {} for device={}",
                    outcome.eventType(), device == null ? null : device.getDeviceId(), e);
        }
    }

    private ReceiveOutcome doReceiveSms(String authenticatedDeviceId, SmsReceiveRequest request) {
        // 身份由调用方从拦截器的认证结果传入，**刻意不从请求体读**。
        // 返回 ReceiveOutcome 而不是直接返回响应：要把「命中了哪条采集规则」带出事务，
        // 事件那一侧要用（见 ReceiveOutcome 的说明）。
        // 做成显式参数而不是让 service 自己去 request 里取，是为了让「拿错身份」这件事
        // 在编译期就不可能发生 —— 将来多一个调用方也没法传错。
        SmsDevice device = deviceRepository.findByDeviceId(authenticatedDeviceId)
                .orElseThrow(() -> new RuntimeException("Device not found: " + authenticatedDeviceId));

        Long devicePk = device.getId();

        // 设备读不到本机号码时 phone 会是 null/空。库里该列是 NOT NULL，
        // 这里归一成空串，免得插入时违反约束、把整条短信一起丢掉。
        String phone = request.getPhone() == null ? "" : request.getPhone();

        // sender 同理，且**必须在放开 @NotBlank 之后补上**：@Size 不拦 null，
        // 而 sms_message.sender 是 NOT NULL —— 不归一的话，一个 sender: null 的上报
        // 会从「400 被拒」变成「500 插入失败」。旧版本 App 只会送空串不会送 null，
        // 但接口是对外开放的，不能假设调用方一定是设备。
        String sender = (request.getSender() == null) ? "" : request.getSender();

        // 1. Idempotency by (device_id, local_message_id)
        Optional<SmsMessage> existingByIdempotency =
                smsMessageRepository.findByDeviceIdAndLocalMessageId(devicePk, request.getLocalMessageId());
        if (existingByIdempotency.isPresent()) {
            SmsMessage msg = existingByIdempotency.get();
            log.info("Idempotency hit for deviceId={}, localMessageId={}", authenticatedDeviceId, request.getLocalMessageId());
            return new ReceiveOutcome(
                    new SmsReceiveResponse(msg.getId(), true, msg.getStatus().name(), null),
                    EventType.SMS_DUPLICATE,
                    "同一 localMessageId 已上报过（设备端重试）",
                    msg);
        }

        // 2. Compute source hash for dedup: SHA-256(content)
        String sourceHash = HashUtil.sha256(request.getContent());

        // 3. Check dedup by (device, source_hash) —— 去重按设备做，见仓储层的说明
        //
        // 这里原本还有一句 Redis setIfAbsent 做「最近见过同样的内容」，但它的返回值
        // 赋值后从未被读过 —— 也就是说那个守卫从来没生效过，纯属误导。已删。
        Optional<SmsMessage> existingByHash =
                smsMessageRepository.findByDeviceIdAndSourceHash(devicePk, sourceHash);
        if (existingByHash.isPresent()) {
            SmsMessage existing = existingByHash.get();

            // 重复到达**照样留痕**，但仍然只有一行 —— 就在这一行上记：
            //   duplicate_count 加一（列表上显示「重复 3 次」），
            //   保存实体顺带把 updated_at 顶上去（那列是 ON UPDATE CURRENT_TIMESTAMP），
            //   于是「最后又是什么时候收到的」也查得到。
            //
            // 原先这里什么都不做，只回一个 DUPLICATE：现场于是完全看不出同一个码
            // 又来过几次 —— 而那恰恰是判断「是不是被重放 / 是不是双卡各收了一遍」
            // 的唯一线索。
            existing.setDuplicateCount(existing.getDuplicateCount() + 1);
            smsMessageRepository.save(existing);

            log.info("Duplicate SMS counted for device={}, msgId={}, count={}",
                    authenticatedDeviceId, existing.getId(), existing.getDuplicateCount());

            // 这一行的「重复 N 次 · 最后 xx」变了，列表上得跟着动。
            // 用 singletonMap 而不是 Map.of：后者不接受 null 值，
            // 而这里只是附带信息（前端只用它判断「该刷新了」），
            // 不值得为它把整条上报路径搭进去。
            adminEvents.broadcast(AdminEventBroadcaster.EVENT_SMS,
                    Collections.singletonMap("id", existing.getId()));
            return new ReceiveOutcome(
                    new SmsReceiveResponse(existing.getId(), true, SmsStatus.DUPLICATE.name(), null),
                    EventType.SMS_DUPLICATE,
                    "同一内容重复上报（按设备做内容去重）",
                    existing);
        }

        // 4. Apply collect rules (priority desc, first match wins; no match => collect)
        CollectRuleEngine.Decision decision = collectRuleEngine.decide(sender, request.getContent());

        // 验证码一律由服务端从正文里提取，**不再采用客户端送来的那个值**。
        //
        // 设备端（SmsCodeParser）那套规则与本项目的 CodeExtractor 不一致，而且更宽：
        // 它会把订单号/流水号当成验证码（"您的验证码已发送，流水号 123456" 会给出 123456），
        // 也会从更长的数字串里截一段（"验证码是1234567890" 会给出 12345678）。
        // 那个值此前被原样写进 sms:code:{phone} 并推给等待方 —— 等验证码的调用方
        // 拿到的是一个**错码**，而两端都没有任何信号，比超时更难查。
        //
        // 现在只留这一处提取：规则改一次全设备生效，不必等设备端发版。
        //
        // 提取放在建实体之前，是为了让「存进 sms_message.code 的」与「写进缓存、回给
        // 调用方的」**是同一个值**。原先前者是 request.getCode() 原文、后者走 resolveCode，
        // 两个口子各算各的，一旦规则不同就会分叉。
        String code = CodeExtractor.extract(request.getContent());

        // 5. Save new SMS message
        SmsMessage message = new SmsMessage();
        message.setDeviceId(devicePk);
        message.setLocalMessageId(request.getLocalMessageId());
        message.setPhone(phone);
        message.setSender(sender);
        message.setContent(request.getContent());
        message.setCode(code);
        message.setStatus(decision.ignored() ? SmsStatus.IGNORED : SmsStatus.RECEIVED);
        message.setSourceHash(sourceHash);
        if (request.getReceiveTime() != null) {
            message.setReceiveTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(request.getReceiveTime()), ZoneId.systemDefault()));
        } else {
            message.setReceiveTime(LocalDateTime.now());
        }
        smsMessageRepository.save(message);

        if (decision.ignored()) {
            // 只留档：不写验证码缓存、不推送、也不转发，避免污染正在等待验证码的调用方
            log.info("SMS ignored by rule '{}': deviceId={}, phone={}, sender={}",
                    decision.matchedRule(), authenticatedDeviceId, request.getPhone(), sender);
            return new ReceiveOutcome(
                    new SmsReceiveResponse(message.getId(), false, SmsStatus.IGNORED.name(), null),
                    EventType.SMS_IGNORED_BY_RULE,
                    // 规则名是排查「为什么这条没进系统」最关键的一句话，
                    // 原先只活在这个局部变量里，管理端看不到。
                    decision.matchedRule() == null ? "命中忽略规则" : "命中规则：" + decision.matchedRule(),
                    message);
        }

        // 转发任务入队。**位置很重要**：在保存 sms_message 之后（要 message.getId()）、
        // 在同一个事务里（outbox 的全部意义所在）、且只对未被规则忽略的短信做
        // （被忽略的短信不进转发，与它们不进验证码缓存是同一个道理）。
        notifyOutbox.enqueue(device, message);

        // 列表上多了一条，推给管理后台。
        // 放在这里而不是方法末尾：被规则忽略的那些不进默认列表，不必惊动前端。
        //
        // 用 singletonMap 而不是 Map.of：**Map.of 遇到 null 值直接抛 NPE**，
        // 而这里的 id 来自「save 之后由 JPA 回填」这一副作用（单元测试里桩掉 save
        // 就复现了），推送又只是旁路信号 —— 不能让它有机会把上报整条打断。
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_SMS,
                Collections.singletonMap("id", message.getId()));

        // 外部调用方按号码取短信、不关心发送方，所以归一化后的号码是唯一的匹配维度。
        // 归一化必须与 WaitingService 用同一套规则，否则又是一次静默超时。
        String normalizedPhone = PhoneUtil.normalize(request.getPhone());

        // 6. Store in Redis: sms:code:{phone}
        // 仅当真的解析出验证码才写：空串会把上一条真实验证码覆盖掉，
        // 而 WaitingService 用 isEmpty() 判断有无验证码，会导致等待方直接超时。
        if (!code.isEmpty() && !normalizedPhone.isEmpty()) {
            String codeKey = SMS_CODE_KEY_PREFIX + normalizedPhone;
            redisTemplate.opsForValue().set(codeKey, code, CODE_TTL_SECONDS, TimeUnit.SECONDS);

            // 时间戳单独存一个键，而不是把值改成 "code|ts"：
            // 无论是 WaitingService 还是人工排查 Redis，都按「这个键里就是验证码」来理解，
            // 改格式会破坏这个直觉。两个键同 TTL 一起写，不会漂移。
            //
            // 存它是因为 /wait 命中缓存时会**立即返回**，而缓存里的码可能已经存在 4 分钟、
            // 早被别的调用方取走用过了 —— 调用方拿到的是一条过期数据却毫无察觉。
            // 有了这个时间戳，它至少能自己判断新旧。
            redisTemplate.opsForValue().set(
                    SMS_CODE_AT_KEY_PREFIX + normalizedPhone,
                    String.valueOf(System.currentTimeMillis()),
                    CODE_TTL_SECONDS, TimeUnit.SECONDS);
        } else if (code.isEmpty()) {
            log.debug("No verification code parsed, skip Redis cache: phone={}, sender={}",
                    request.getPhone(), sender);
        } else {
            // 设备读不到本机号（phone 为 null/空）时没有调用方能等到，写了也是死数据
            log.debug("Phone number unknown, skip Redis cache: sender={}", sender);
        }

        // 7. Notify waiting consumers via Redis pub/sub
        // 同样只在解析出验证码时推送：用空验证码 complete future 会让等待方拿到空值。
        // 频道名保留 {phone}:{sender} —— 订阅方是全量模式订阅 "sms:channel:*"、靠消息体匹配的。
        if (!code.isEmpty() && !normalizedPhone.isEmpty()) {
            String waitChannel = "sms:channel:" + request.getPhone() + ":" + sender;
            long receiveTimeEpoch = message.getReceiveTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            String messageJson = String.format(
                    "{\"sender\":\"%s\",\"content\":\"%s\",\"code\":\"%s\",\"phone\":\"%s\",\"receivedAt\":%d}",
                    escapeJson(sender),
                    escapeJson(request.getContent()),
                    escapeJson(code),
                    escapeJson(normalizedPhone),
                    receiveTimeEpoch
            );
            redisTemplate.convertAndSend(waitChannel, messageJson);
        }

        // deviceId 用认证出的那个：请求体里的值已经不作数，照打会误导排查
        // （而且攻击者能借此让日志显示成受害者的设备号）。
        //
        // 验证码本身**不打**。它就是这个系统的核心资产，日志文件的生命周期远比
        // 5 分钟的 TTL 长，落盘等于长期留存。只记「有没有解析出来」，
        // 排查「这条为什么没出码」够用了。
        log.info("SMS received and stored: deviceId={}, phone={}, sender={}, hasCode={}",
                authenticatedDeviceId, request.getPhone(), sender,
                code != null && !code.isEmpty());

        return new ReceiveOutcome(
                new SmsReceiveResponse(message.getId(), false, SmsStatus.RECEIVED.name(), code),
                EventType.SMS_STORED,
                null,
                message);
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}