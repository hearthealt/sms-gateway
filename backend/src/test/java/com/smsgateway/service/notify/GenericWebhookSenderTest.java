package com.smsgateway.service.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通用 webhook 的载荷格式。
 *
 * <p>这是**对外的线上格式**，所以连字段的存在与否都要钉住：对端拿 {@code smsMessageId}
 * 去查一条不存在的记录、或者把空串当成一个真实号码，都不会报错，只会静默地做错事。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GenericWebhookSenderTest {

    @Mock private NotifyHttpClient http;
    @Mock private SsrfGuard ssrfGuard;

    /**
     * 真实实现（不桩）：要验的正是它建出来的 JSON 结构。
     *
     * <p>必须标 {@code @Spy} 而不是写成普通字段 —— {@code @InjectMocks} 只注入
     * 「标了 Mockito 注解的」字段。写成普通字段的话它会保持 null 被注进去，
     * 表现是每个用例都在 `createObjectNode()` 上 NPE（CI 上就是这样挂的）。
     */
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private GenericWebhookSender sender;

    private JsonNode payloadOf(RenderedMessage message) throws Exception {
        return objectMapper.readTree(sender.defaultPayload(message));
    }

    @Test
    @DisplayName("短信：元信息字段齐全")
    void smsPayloadCarriesMeta() throws Exception {
        RenderedMessage message = new RenderedMessage(
                "【备用机 · 13800138000 · 09-24 10:30:00】\n验证码 483920",
                1024L, "10690300", "13800138000", "备用机-1", "09-24 10:30:00");

        JsonNode node = payloadOf(message);

        assertThat(node.get("smsMessageId").asLong()).isEqualTo(1024L);
        assertThat(node.get("sender").asText()).isEqualTo("10690300");
        assertThat(node.get("phone").asText()).isEqualTo("13800138000");
        assertThat(node.get("device").asText()).isEqualTo("备用机-1");
        assertThat(node.has("alert")).isFalse();
    }

    @Test
    @DisplayName("告警：不带短信相关的字段，也不补 0 —— 0 看起来就像一条真实记录的 id")
    void alertPayloadOmitsSmsFields() throws Exception {
        // 告警的 smsMessageId 为 null，正文由 buildAlert 拼出来（这里直接用同样的形状）
        RenderedMessage message = new RenderedMessage(
                "【短信网关】设备「车间手机」已离线 32 分钟", null, null, null, null, "");

        JsonNode node = payloadOf(message);

        assertThat(node.get("text").asText()).contains("已离线 32 分钟");
        assertThat(node.get("alert").asBoolean()).isTrue();
        assertThat(node.has("smsMessageId")).isFalse();
        assertThat(node.has("sender")).isFalse();
        assertThat(node.has("phone")).isFalse();
        assertThat(node.has("time")).isFalse();
    }
}
