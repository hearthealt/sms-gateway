package com.smsgateway.service.notify;

import com.smsgateway.config.NotifyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 出站 HTTP：转发渠道往对端发消息走这一条。
 *
 * <p>用 JDK 自带的 {@link HttpClient} 而不是引 Apache HttpClient：项目只需要
 * 「POST 一段 JSON、读一小段响应」这一件事，而 JDK 的客户端原生支持
 * {@code followRedirects(NEVER)}（SSRF 防护要求，见 {@link SsrfGuard}），
 * 不必为了一个开关多引一个依赖。
 *
 * <p>连接池由 HttpClient 内部维护，全进程共用一个实例 —— 每次 new 一个会各自建池，
 * 高频投递下连接数会失控。
 */
@Slf4j
@Component
public class NotifyHttpClient {

    private final NotifyProperties properties;
    private final HttpClient client;

    public NotifyHttpClient(NotifyProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getHttp().getConnectTimeoutMs()))
                // **必须显式写出来。** JDK 默认就是 NEVER，但这里的「默认对」靠不住：
                // SSRF 校验过的地址是我们解析出来的那一个，而对端完全可以 302 到内网，
                // 一旦跟随重定向，那次校验就白做了。写成显式的，改动它时会被看见。
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * POST 一段 JSON。
     *
     * @return 状态码与**截断后**的响应体。不抛异常 —— 连不上、超时都是投递失败的一种，
     *         由调用方按 {@code NotifyErrorClassifier} 分类，而不是各自 try/catch。
     */
    public NotifyHttpResponse postJson(String url, String jsonBody) {
        return postJson(url, jsonBody, Map.of());
    }

    /** @param extraHeaders 额外请求头，通用 webhook 的 HMAC 签名走这里。 */
    public NotifyHttpResponse postJson(String url, String jsonBody, Map<String, String> extraHeaders) {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getHttp().getReadTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "sms-gateway/2.0");
            extraHeaders.forEach(builder::header);
            request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            // URI.create 对非法地址抛这个。归成失败而不是让它冒到调度器上。
            return new NotifyHttpResponse(0, "地址不合法：" + e.getMessage(), null);
        }

        try {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new NotifyHttpResponse(
                    response.statusCode(),
                    readLimited(response.body()),
                    null);
        } catch (IOException e) {
            // 网络层的失败：超时、连接被拒、DNS 挂了 —— 都是**可重试**的，
            // 所以只带回原因，由分类器决定。
            return new NotifyHttpResponse(0, null, describe(e));
        } catch (InterruptedException e) {
            // 恢复中断标志：吞掉它会让上层的取消机制失效
            Thread.currentThread().interrupt();
            return new NotifyHttpResponse(0, null, "请求被中断");
        }
    }

    /**
     * 只读前 {@code maxResponseBytes} 字节。
     *
     * <p>不设上限的话，对端（GENERIC_WEBHOOK 是管理员填的任意地址）回一个超大响应
     * 就能把这个进程的内存吃掉。而我们对响应体其实没有用途 —— 只在失败时截一小段
     * 放进错误摘要给人看。
     */
    private String readLimited(InputStream body) {
        int limit = properties.getHttp().getMaxResponseBytes();
        try (InputStream in = body) {
            byte[] buffer = in.readNBytes(limit);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** 把异常翻译成一句能放进错误摘要的话。 */
    private String describe(IOException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return e.getClass().getSimpleName() + ": " + message;
    }

    /**
     * GET 一个地址。只给 WxPusher 的 SPT 极简模式用 —— 那是唯一一个把内容放进 URL 的渠道。
     *
     * <p>注意它的长度受 URL 上限约束，长短信会被对端拒绝；那条渠道的说明里写了这件事。
     */
    public NotifyHttpResponse get(String url) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getHttp().getReadTimeoutMs()))
                    .header("User-Agent", "sms-gateway/2.0")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            return new NotifyHttpResponse(0, "地址不合法：" + e.getMessage(), null);
        }

        try {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new NotifyHttpResponse(response.statusCode(), readLimited(response.body()), null);
        } catch (IOException e) {
            return new NotifyHttpResponse(0, null, describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new NotifyHttpResponse(0, null, "请求被中断");
        }
    }

    /**
     * @param statusCode HTTP 状态码；**0 表示请求根本没发出去**（网络层失败或地址非法），
     *                   此时 {@code error} 非空
     * @param body       截断后的响应体，可能为 null
     * @param error      网络层失败原因，成功往返时为 null
     */
    public record NotifyHttpResponse(int statusCode, String body, String error) {

        /** 请求确实到了对端并拿到响应。 */
        public boolean hasResponse() {
            return statusCode > 0;
        }
    }
}
