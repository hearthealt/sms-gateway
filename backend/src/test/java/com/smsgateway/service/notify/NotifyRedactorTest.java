package com.smsgateway.service.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 凭据脱敏。
 *
 * <p>webhook 地址本身就是凭证（企微的 {@code ?key=} 就是全部鉴权），而对端报错时
 * 常把整个请求 URL 回显在响应体里 —— 我们要把那段截下来存进 {@code last_error}、
 * 打进度日志。少了这一层，密钥就被写进了数据库和日志文件。
 */
class NotifyRedactorTest {

    @Test
    @DisplayName("查询参数里的 key 被打掉")
    void redactsKeyParam() {
        String url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=693a91f6-7xxx-4bc4-9d3c-5aaa";

        String redacted = NotifyRedactor.redact(url);

        assertThat(redacted).doesNotContain("5aaa").doesNotContain("693a91f6");
        assertThat(redacted).contains("qyapi.weixin.qq.com");
    }

    @Test
    @DisplayName("access_token / sign / secret 这些参数名都认")
    void redactsCommonParamNames() {
        assertThat(NotifyRedactor.redact("https://oapi.dingtalk.com/robot/send?access_token=abc123def456"))
                .doesNotContain("abc123def456");
        assertThat(NotifyRedactor.redact("https://x.com/hook?sign=deadbeef&timestamp=1"))
                .doesNotContain("deadbeef");
        assertThat(NotifyRedactor.redact("https://x.com/hook?SECRET=topsecret"))
                .doesNotContain("topsecret");
    }

    @Test
    @DisplayName("路径里的长随机串也打掉 —— Server酱 / WxPusher 把凭据放在路径上")
    void redactsLongPathSegment() {
        // 按参数名匹配一个也拦不住这类：凭据根本不在查询串里
        String url = "https://sctapi.ftqq.com/SCT123456789abcdefghijklmnop.send";

        String redacted = NotifyRedactor.redact(url);

        assertThat(redacted).doesNotContain("SCT123456789abcdefghijklmnop");
        assertThat(redacted).contains("sctapi.ftqq.com");
    }

    @Test
    @DisplayName("URL 嵌在一句话中间也能认出来")
    void redactsUrlInsideText() {
        String body = "{\"errcode\":40001,\"errmsg\":\"invalid credential, url=https://x.com/hook?key=leakme123\"}";

        String redacted = NotifyRedactor.redact(body);

        assertThat(redacted).doesNotContain("leakme123");
    }

    @Test
    @DisplayName("不带 scheme 的裸查询串也处理（有些对端只回显这个）")
    void redactsBareQueryString() {
        assertThat(NotifyRedactor.redact("key=leakme123&other=1")).doesNotContain("leakme123");
    }

    @Test
    @DisplayName("正常文本不被误伤")
    void leavesNormalTextAlone() {
        String body = "{\"errcode\":0,\"errmsg\":\"ok\"}";
        assertThat(NotifyRedactor.redact(body)).isEqualTo(body);
    }

    @Test
    @DisplayName("safeEndpoint 只留 scheme://host，路径与查询全丢")
    void safeEndpointKeepsOnlyHost() {
        assertThat(NotifyRedactor.safeEndpoint("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=secret"))
                .isEqualTo("https://qyapi.weixin.qq.com");
        assertThat(NotifyRedactor.safeEndpoint("https://x.com")).isEqualTo("https://x.com");
    }

    @Test
    @DisplayName("maskSecret 保留头尾各 4 位 —— 全打星号的话管理员认不出是哪一个")
    void maskSecretKeepsEnds() {
        assertThat(NotifyRedactor.maskSecret("693a91f6-7xxx-4bc4-9d3c-5aaa"))
                .startsWith("693a").endsWith("5aaa").contains("****");
        // 太短的值不保留，否则等于没打
        assertThat(NotifyRedactor.maskSecret("abc")).isEqualTo("***");
    }

    @Test
    @DisplayName("forStorage 先脱敏再截断，顺序不能反")
    void forStorageRedactsThenTruncates() {
        String longBody = "https://x.com/hook?key=leakme123  " + "填充".repeat(400);

        String stored = NotifyRedactor.forStorage(longBody, 500);

        assertThat(stored).doesNotContain("leakme123");
        assertThat(stored.length()).isLessThanOrEqualTo(500);
    }
}
