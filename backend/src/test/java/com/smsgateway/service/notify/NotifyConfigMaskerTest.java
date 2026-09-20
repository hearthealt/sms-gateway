package com.smsgateway.service.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置回显打码与「未改动」识别。
 *
 * <p>这组测试守的是一个很隐蔽的事故：管理端回显打码值 → 管理员只改了渠道名就保存
 * → 打码值 {@code 693a****5aaa} 被当成新 webhook 地址存进库 →
 * **渠道从此静默失效，而且看不出是什么时候坏的**（保存那一刻一切正常）。
 */
class NotifyConfigMaskerTest {

    private Map<String, Object> config() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("webhookUrl", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=693a91f6-7xxx-4bc4-9d3c-5aaa");
        config.put("allowPrivateNetwork", false);
        config.put("payloadTemplate", "{\"text\": {text}}");
        return config;
    }

    @Test
    @DisplayName("回显时敏感的字符串值打码，其余原样")
    void masksOnlySensitiveValues() {
        Map<String, Object> masked = NotifyConfigMasker.mask(config());

        assertThat(String.valueOf(masked.get("webhookUrl"))).contains("****").doesNotContain("4bc4");
        // 模板不是凭据，管理员要看要改，打码就没法用了
        assertThat(masked.get("payloadTemplate")).isEqualTo("{\"text\": {text}}");
        assertThat(masked.get("allowPrivateNetwork")).isEqualTo(false);
    }

    @Test
    @DisplayName("把打码值原样提交回来 = 不改动，保留原值")
    void submittingMaskedValueKeepsOriginal() {
        // 这正是上面那个事故的入口
        Map<String, Object> original = config();
        Map<String, Object> submitted = NotifyConfigMasker.mask(original);

        Map<String, Object> merged = NotifyConfigMasker.merge(original, submitted);

        assertThat(merged.get("webhookUrl")).isEqualTo(original.get("webhookUrl"));
        assertThat(String.valueOf(merged.get("webhookUrl"))).doesNotContain("****");
    }

    @Test
    @DisplayName("提交空值 / 空串 = 不改动")
    void submittingBlankKeepsOriginal() {
        Map<String, Object> original = config();
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("webhookUrl", "");
        submitted.put("payloadTemplate", null);

        Map<String, Object> merged = NotifyConfigMasker.merge(original, submitted);

        assertThat(merged.get("webhookUrl")).isEqualTo(original.get("webhookUrl"));
        assertThat(merged.get("payloadTemplate")).isEqualTo(original.get("payloadTemplate"));
    }

    @Test
    @DisplayName("提交了真正的新值就换掉")
    void submittingNewValueReplaces() {
        Map<String, Object> original = config();
        Map<String, Object> submitted = Map.of("webhookUrl", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=brandnew");

        Map<String, Object> merged = NotifyConfigMasker.merge(original, submitted);

        assertThat(String.valueOf(merged.get("webhookUrl"))).contains("brandnew");
        // 没提交的键保持原值，不会被抹掉
        assertThat(merged.get("payloadTemplate")).isEqualTo(original.get("payloadTemplate"));
    }

    @Test
    @DisplayName("提交空 Map = 整体不改动")
    void submittingEmptyMapKeepsEverything() {
        Map<String, Object> original = config();
        assertThat(NotifyConfigMasker.merge(original, Map.of())).isEqualTo(original);
    }

    @Test
    @DisplayName("短值打码成整串星号，此时提交回来也算「未改动」")
    void shortSecretStillDetectedAsUnchanged() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("hmacSecret", "abc");   // 短到 maskSecret 会整串打掉

        Map<String, Object> submitted = NotifyConfigMasker.mask(original);
        assertThat(submitted.get("hmacSecret")).isEqualTo("***");

        // 「***」不是合法的新密钥，但它恰好等于原值的打码形式，所以认成未改动
        Map<String, Object> merged = NotifyConfigMasker.merge(original, submitted);
        assertThat(merged.get("hmacSecret")).isEqualTo("abc");
    }
}
