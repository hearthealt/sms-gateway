package com.smsgateway.service;

import com.smsgateway.model.dto.SmsReceiveRequest;
import com.smsgateway.model.dto.SmsReceiveResponse;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.SmsStatus;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsMessageRepository;
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

    private static final String SMS_CODE_KEY_PREFIX = "sms:code:";
    private static final long CODE_TTL_SECONDS = 300; // 5 min

    /**
     * 验证码提取模式。分两类：关键词在前（「验证码是123456」）与关键词在后
     * （「123456 是您的验证码」）—— 后者此前完全漏掉。
     *
     * <p>数字两侧的 (?&lt;!\d)/(?!\d) 用于防止从更长的数字串里截取一段
     * （如订单号 1234567890 被当成验证码）。
     *
     * <p>这里刻意**不再**保留「任意 6 位数字」兜底：那会把订单号、快递单号
     * 误判成验证码，返回错码比返回空更糟。
     */
    private static final java.util.regex.Pattern[] CODE_PATTERNS = {
            java.util.regex.Pattern.compile(
                    "(?:验证码|校验码|动态码|安全码)[是为：:\\s]*(?<!\\d)(\\d{4,8})(?!\\d)"),
            java.util.regex.Pattern.compile(
                    "(?<!\\d)(\\d{4,8})(?!\\d)\\s*(?:是|为)?\\s*(?:您的|你的)?\\s*(?:登录|注册|支付|校验|动态)?\\s*(?:验证码|校验码|动态码)"),
            java.util.regex.Pattern.compile(
                    "(?:verification\\s+code|code)[是为：:\\s]*(?<!\\d)(\\d{4,8})(?!\\d)",
                    java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile(
                    "(?<!\\d)(\\d{4,8})(?!\\d)\\s*(?:is)?\\s*(?:your)?\\s*(?:verification\\s+code|code)",
                    java.util.regex.Pattern.CASE_INSENSITIVE)
    };

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
            // 两台设备**同时**上报内容相同的短信时，双方都会在 findBySourceHash 扑空、
            // 都走到插入，后落地的那个撞 uk_source_hash。
            //
            // 内容相同本来就意味着这是重复上报，所以重查一次按「重复」返回即可，
            // 不能让它变成 500 —— 设备端把 500 归为可重试，这条短信会永远传不上去、
            // 一直烧电重试。这与「重复短信分支自己撞约束」是同一个症状的两个入口。
            //
            // 必须在事务**外面**接：事务一旦被标记 rollback-only，同一个事务里再查询会直接失败。
            log.info("Concurrent duplicate detected, returning existing row as duplicate");
            return smsMessageRepository.findBySourceHash(HashUtil.sha256(request.getContent()))
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

        // 3. Check dedup by source_hash
        //
        // 这里原本还有一句 Redis setIfAbsent 做「最近见过同样的内容」，但它的返回值
        // 赋值后从未被读过 —— 也就是说那个守卫从来没生效过，纯属误导。
        // 已删：真正的去重由 unique key uk_source_hash 保证，并发下的撞约束由
        // receiveSms 的 catch 兜住，两者合起来比一个带 TTL 的 Redis 标记可靠。
        Optional<SmsMessage> existingByHash = smsMessageRepository.findBySourceHash(sourceHash);
        if (existingByHash.isPresent()) {
            SmsMessage msg = existingByHash.get();

            // 只回报重复，**不再另插一行**。
            //
            // 原先这里新建一条 status=DUPLICATE 的记录，但把 source_hash 设成了与已存在行
            // 完全相同的值 —— 而那一列上有唯一索引 uk_source_hash。这条 INSERT 100% 撞约束、
            // 抛 DataIntegrityViolationException、被兜成 500；设备端又把 500 归为可重试，
            // 于是这条短信永远传不上去，还一直烧电重试。
            //
            // 「一段内容一行」并不是新加的约定：查询侧 findBySourceHash 返回 Optional、
            // 建表侧 uk_source_hash 唯一，两边一直是这么写的，只有这段插入跑偏了。
            // 代价是管理后台不会再出现 status=DUPLICATE 的行 —— 但它本来也从没成功出现过。
            // 响应结构保持不变，设备端的「内容重复」提示照常工作。
            log.info("Duplicate SMS detected for sourceHash={}, original msgId={}", sourceHash, msg.getId());
            return new SmsReceiveResponse(msg.getId(), true, SmsStatus.DUPLICATE.name(), null);
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
            // 只留档：不写验证码缓存、不推送，避免污染正在等待验证码的调用方
            log.info("SMS ignored by rule '{}': deviceId={}, phone={}, sender={}",
                    decision.matchedRule(), authenticatedDeviceId, request.getPhone(), request.getSender());
            return new SmsReceiveResponse(message.getId(), false, SmsStatus.IGNORED.name(), null);
        }

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
     * 空串一律视为「未解析」——否则会绕过 extractVerificationCode 的兜底。
     */
    private String resolveCode(String clientCode, String content) {
        if (clientCode != null && !clientCode.isBlank()) {
            return clientCode.trim();
        }
        return extractVerificationCode(content);
    }

    private String extractVerificationCode(String content) {
        if (content == null || content.isBlank()) return "";
        for (java.util.regex.Pattern pattern : CODE_PATTERNS) {
            java.util.regex.Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
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