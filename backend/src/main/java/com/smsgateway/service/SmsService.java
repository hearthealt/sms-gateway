package com.smsgateway.service;

import com.smsgateway.model.dto.SmsReceiveRequest;
import com.smsgateway.model.dto.SmsReceiveResponse;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.SmsMessage;
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
import java.util.Map;
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
     * 上报一条短信。
     *
     * <p>用编程式事务而不是 {@code @Transactional}，是为了能接住并发下的唯一约束冲突 ——
     * 见 catch 块里的说明。写法与 {@link DeviceService#register} 一致。
     */
    public SmsReceiveResponse receiveSms(String authenticatedDeviceId, SmsReceiveRequest request) {
        try {
            return transactionTemplate.execute(status -> doReceiveSms(authenticatedDeviceId, request));
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
                    .map(device -> device.getId())
                    .orElse(null);
            return smsMessageRepository.findByDeviceIdAndSourceHash(
                            devicePk, HashUtil.sha256(request.getContent()))
                    .map(existing -> new SmsReceiveResponse(
                            existing.getId(), true, SmsStatus.DUPLICATE.name(), null))
                    .orElseThrow(() -> e);
        }
    }

    private SmsReceiveResponse doReceiveSms(String authenticatedDeviceId, SmsReceiveRequest request) {
        // 身份由调用方从拦截器的认证结果传入，**刻意不从请求体读**。
        // 做成显式参数而不是让 service 自己去 request 里取，是为了让「拿错身份」这件事
        // 在编译期就不可能发生 —— 将来多一个调用方也没法传错。
        SmsDevice device = deviceRepository.findByDeviceId(authenticatedDeviceId)
                .orElseThrow(() -> new RuntimeException("Device not found: " + authenticatedDeviceId));

        Long devicePk = device.getId();

        // 设备读不到本机号码时 phone 会是 null/空。库里该列是 NOT NULL，
        // 这里归一成空串，免得插入时违反约束、把整条短信一起丢掉。
        String phone = request.getPhone() == null ? "" : request.getPhone();

        // 1. Idempotency by (device_id, local_message_id)
        Optional<SmsMessage> existingByIdempotency =
                smsMessageRepository.findByDeviceIdAndLocalMessageId(devicePk, request.getLocalMessageId());
        if (existingByIdempotency.isPresent()) {
            SmsMessage msg = existingByIdempotency.get();
            log.info("Idempotency hit for deviceId={}, localMessageId={}", authenticatedDeviceId, request.getLocalMessageId());
            return new SmsReceiveResponse(msg.getId(), true, msg.getStatus().name(), null);
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
            return new SmsReceiveResponse(existing.getId(), true, SmsStatus.DUPLICATE.name(), null);
        }

        // 4. Apply collect rules (priority desc, first match wins; no match => collect)
        CollectRuleEngine.Decision decision = collectRuleEngine.decide(request.getSender(), request.getContent());

        // 5. Save new SMS message
        SmsMessage message = new SmsMessage();
        message.setDeviceId(devicePk);
        message.setLocalMessageId(request.getLocalMessageId());
        message.setPhone(phone);
        message.setSender(request.getSender());
        message.setContent(request.getContent());
        message.setCode(request.getCode());
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
                    decision.matchedRule(), authenticatedDeviceId, request.getPhone(), request.getSender());
            return new SmsReceiveResponse(message.getId(), false, SmsStatus.IGNORED.name(), null);
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

        String code = resolveCode(request.getCode(), request.getContent());
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
                    request.getPhone(), request.getSender());
        } else {
            // 设备读不到本机号（phone 为 null/空）时没有调用方能等到，写了也是死数据
            log.debug("Phone number unknown, skip Redis cache: sender={}", request.getSender());
        }

        // 7. Notify waiting consumers via Redis pub/sub
        // 同样只在解析出验证码时推送：用空验证码 complete future 会让等待方拿到空值。
        // 频道名保留 {phone}:{sender} —— 订阅方是全量模式订阅 "sms:channel:*"、靠消息体匹配的。
        if (!code.isEmpty() && !normalizedPhone.isEmpty()) {
            String waitChannel = "sms:channel:" + request.getPhone() + ":" + request.getSender();
            long receiveTimeEpoch = message.getReceiveTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            String messageJson = String.format(
                    "{\"sender\":\"%s\",\"content\":\"%s\",\"code\":\"%s\",\"phone\":\"%s\",\"receivedAt\":%d}",
                    escapeJson(request.getSender()),
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
                authenticatedDeviceId, request.getPhone(), request.getSender(),
                code != null && !code.isEmpty());

        return new SmsReceiveResponse(message.getId(), false, SmsStatus.RECEIVED.name(), code);
    }

    /**
     * 客户端已解析出验证码时优先采用；否则由后端解析。
     * 空串一律视为「未解析」——否则会绕过 CodeExtractor 的兜底。
     *
     * <p>提取逻辑搬到了 {@link CodeExtractor}：那是一段自成一体、有明确输入输出的
     * 正则逻辑，留在本类里只会让「这条短信为什么没出码」更难查。
     */
    private String resolveCode(String clientCode, String content) {
        if (clientCode != null && !clientCode.isBlank()) {
            return clientCode.trim();
        }
        return CodeExtractor.extract(content);
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