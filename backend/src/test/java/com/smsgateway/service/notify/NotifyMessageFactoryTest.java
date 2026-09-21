package com.smsgateway.service.notify;

import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.service.SysConfigService;
import org.mockito.Mockito;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 出站消息的拼装与截断。
 *
 * <p>不做模板、不打码 —— 转发出去的就是短信原文，这些测试守着「原文一字不改」
 * 和「按字节截断不切坏汉字」两件事。
 */
class NotifyMessageFactoryTest {

    private final SysConfigService sysConfigService = Mockito.mock(SysConfigService.class);

    private final NotifyMessageFactory factory = new NotifyMessageFactory(sysConfigService);

    /** 默认带上来源信息 —— 与 SysConfigKey 里的默认值一致。 */
    private void withMetaLine(boolean include) {
        when(sysConfigService.getBoolean(SysConfigKey.NOTIFY_INCLUDE_META)).thenReturn(include);
    }

    @BeforeEach
    void setUp() {
        // 多数用例在测「带来源信息」的形态，所以默认按 true 来。
        // 不设的话 Mockito 给 boolean 的默认值是 false，会静默地把所有用例
        // 都变成在测另一个分支。
        withMetaLine(true);
    }

    private SmsMessage sms(String content) {
        SmsMessage sms = new SmsMessage();
        sms.setId(42L);
        sms.setSender("10690300");
        sms.setPhone("13800138000");
        sms.setContent(content);
        sms.setReceiveTime(LocalDateTime.of(2026, 9, 20, 10, 30, 0));
        return sms;
    }

    @Test
    @DisplayName("正文原样带上，一个字符都不改")
    void keepsContentVerbatim() {
        // 不打码：验证码原样出现，这是当前的设计选择
        String content = "【某某】您的验证码是483920，5分钟内有效。";

        RenderedMessage message = factory.build(sms(content), "备用机-1");

        assertThat(message.text()).contains(content);
    }

    @Test
    @DisplayName("元信息（设备/接收方/时间）拼在前面，双卡时能分清是哪个号收的")
    void prefixesMetadata() {
        RenderedMessage message = factory.build(sms("正文"), "备用机-1");

        assertThat(message.text()).startsWith("【");
        assertThat(message.text()).contains("备用机-1").contains("13800138000").contains("10:30:00");
        assertThat(message.text()).endsWith("正文");
    }

    @Test
    @DisplayName("读不到设备名时不留一个空的分隔符")
    void handlesMissingDeviceName() {
        RenderedMessage message = factory.build(sms("正文"), null);

        assertThat(message.text()).contains("13800138000");
        assertThat(message.text()).doesNotContain("· ·").doesNotContain("【 ·");
    }

    @Test
    @DisplayName("元信息字段都读不到时只发正文，不留一个空括号")
    void handlesAllMetadataMissing() {
        SmsMessage bare = new SmsMessage();
        bare.setContent("正文");

        assertThat(factory.build(bare, null).text()).isEqualTo("正文");
    }

    @Test
    @DisplayName("在「系统设置」里关掉来源信息后，发出去的**只有**短信原文")
    void canOmitMetadataLine() {
        withMetaLine(false);

        RenderedMessage message = factory.build(sms("【某某】您的验证码是483920。"), "备用机-1");

        // 一个字符都不多带 —— 关掉这项的人要的就是这个。
        // 注意别断言「不含【」：这条短信的正文自己就以【开头（发送方署名就是那个写法），
        // 那种断言会误伤，而且它想表达的其实是「不含来源信息那一行」。
        assertThat(message.text()).isEqualTo("【某某】您的验证码是483920。");
        assertThat(message.text()).doesNotContain("备用机-1").doesNotContain("13800138000");
    }

    // ---------------------------------------------------------------- 字节截断

    @Test
    @DisplayName("按 UTF-8 字节截断，不切断汉字（否则结尾是半截乱码）")
    void truncatesByBytesWithoutSplittingCharacters() {
        // 中文 1 字 3 字节 —— 企微/钉钉按字节限长，而它们会**静默截断不报错**，
        // 所以必须我们自己截，否则消息会被对端从中间某个字节切掉。
        String text = "验证码".repeat(100);

        String truncated = NotifyMessageFactory.truncateUtf8(text, 300);

        assertThat(truncated.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(300);
        assertThat(truncated).endsWith("...");
        // 出现替换字符就说明切坏了
        assertThat(truncated).doesNotContain("�");
    }

    @Test
    @DisplayName("没超长时原样返回，不加省略号")
    void shortTextUnchanged() {
        String text = "验证码：483920";
        assertThat(NotifyMessageFactory.truncateUtf8(text, 4096)).isEqualTo(text);
    }

    @Test
    @DisplayName("刚好卡在边界也不切坏字符")
    void boundaryDoesNotSplitCharacter() {
        String text = "验".repeat(10);   // 30 字节

        String truncated = NotifyMessageFactory.truncateUtf8(text, 31);

        assertThat(truncated).doesNotContain("�");
        assertThat(truncated.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(31);
    }
}
