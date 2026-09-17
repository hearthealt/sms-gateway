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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final String SMS_CODE_KEY_PREFIX = "sms:code:";
    private static final String SMS_DEDUP_KEY_PREFIX = "sms:dedup:";
    private static final long CODE_TTL_SECONDS = 300; // 5 min
    private static final long DEDUP_TTL_SECONDS = 600; // 10 min

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

    @Transactional
    public SmsReceiveResponse receiveSms(SmsReceiveRequest request, String idempotencyKey) {
        SmsDevice device = deviceRepository.findByDeviceId(request.getDeviceId())
                .orElseThrow(() -> new RuntimeException("Device not found: " + request.getDeviceId()));

        Long devicePk = device.getId();

        // 设备读不到本机号码时 phone 会是 null/空。库里该列是 NOT NULL，
        // 这里归一成空串，免得插入时违反约束、把整条短信一起丢掉。
        String phone = request.getPhone() == null ? "" : request.getPhone();

        // 1. Idempotency by (device_id, local_message_id)
        Optional<SmsMessage> existingByIdempotency =
                smsMessageRepository.findByDeviceIdAndLocalMessageId(devicePk, request.getLocalMessageId());
        if (existingByIdempotency.isPresent()) {
            SmsMessage msg = existingByIdempotency.get();
            log.info("Idempotency hit for deviceId={}, localMessageId={}", request.getDeviceId(), request.getLocalMessageId());
            return new SmsReceiveResponse(msg.getId(), true, msg.getStatus().name(), null);
        }

        // 2. Compute source hash for dedup: SHA-256(content)
        String sourceHash = HashUtil.sha256(request.getContent());

        // 3. Check dedup by source_hash
        String dedupRedisKey = SMS_DEDUP_KEY_PREFIX + sourceHash;
        Boolean isNewDedup = redisTemplate.opsForValue().setIfAbsent(dedupRedisKey, "1", DEDUP_TTL_SECONDS, TimeUnit.SECONDS);

        Optional<SmsMessage> existingByHash = smsMessageRepository.findBySourceHash(sourceHash);
        if (existingByHash.isPresent()) {
            SmsMessage msg = existingByHash.get();
            // Save as DUPLICATE record
            SmsMessage duplicateMsg = new SmsMessage();
            duplicateMsg.setDeviceId(devicePk);
            duplicateMsg.setLocalMessageId(request.getLocalMessageId());
            duplicateMsg.setPhone(phone);
            duplicateMsg.setSender(request.getSender());
            duplicateMsg.setContent(request.getContent());
            duplicateMsg.setCode(request.getCode());
            duplicateMsg.setStatus(SmsStatus.DUPLICATE);
            duplicateMsg.setSourceHash(sourceHash);
            if (request.getReceiveTime() != null) {
                duplicateMsg.setReceiveTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(request.getReceiveTime()), ZoneId.systemDefault()));
            }
            smsMessageRepository.save(duplicateMsg);
            log.info("Duplicate SMS detected for sourceHash={}, original msgId={}", sourceHash, msg.getId());
            return new SmsReceiveResponse(duplicateMsg.getId(), true, SmsStatus.DUPLICATE.name(), null);
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
                    decision.matchedRule(), request.getDeviceId(), request.getPhone(), request.getSender());
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

        log.info("SMS received and stored: deviceId={}, phone={}, sender={}, code={}",
                request.getDeviceId(), request.getPhone(), request.getSender(), code);

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