package com.smsgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 转发功能的**进程级**配置（{@code app.notify.*}）。
 *
 * <p><b>这里只放两类东西：密钥，和「改了就必须重启」的底层参数。</b>
 * 运行期能调的（转发总开关、轮询间隔、失败阈值）都在 {@code sys_config} 表里，
 * 管理后台「系统设置」页改完立即生效 —— 见 {@code SysConfigKey} 上的说明。
 *
 * <p>判断某项该放哪边的标准很简单：**运维过程中会不会想调、调完想不想立刻看到效果**。
 * 会，就进库；不会（超时、并发数、批次大小），留在这里，少几个表单项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.notify")
public class NotifyProperties {

    /**
     * 渠道凭据的加密密钥，**Base64 编码的 32 字节**（AES-256）。
     *
     * <p>不能有默认值：跟着仓库走的默认密钥等于没有密钥。生成方式：
     * <pre>openssl rand -base64 32</pre>
     *
     * <p><b>它必须留在环境变量里，不能进库</b>：用库里的密钥去解库里的密文是循环依赖。
     * 所以「启用转发」这个动作要检查它在不在 —— 不在就给一条带操作指引的错误。
     *
     * <p>这个密钥丢了，所有渠道凭据都要重配 —— 密文在库里，但没有密钥读不出来。
     */
    private String encryptKey;

    private Dispatcher dispatcher = new Dispatcher();
    private Http http = new Http();

    /** 调度器盯上多久没动的 SENDING 记录，把它退回 PENDING（进程被杀后的兜底）。 */
    private int stuckSendingSeconds = 120;

    @Data
    public static class Dispatcher {
        /**
         * 单轮最多取多少条。取多了会在一轮里吃满线程池，下一轮又是空的。
         */
        private int batchSize = 100;
        /** 并发投递的渠道数。同一渠道内部仍是串行，见 NotifyDispatcher。 */
        private int maxConcurrentChannels = 8;
        /** 退避基数（毫秒）。实际等待是 Full Jitter：随机取 [0, base * 2^attempt)。 */
        private long retryBaseMs = 5000;
        /** 退避上限（毫秒）。 */
        private long retryCapMs = 3600_000;
    }

    @Data
    public static class Http {
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 10000;
        /**
         * 响应体最多读多少字节。
         *
         * <p>对端是管理员填的任意地址（GENERIC_WEBHOOK），不设上限的话一个超大响应
         * 就能把这个进程的内存吃掉。我们对响应体没有实际用途 —— 只在失败时截一小段
         * 放进错误摘要里。
         */
        private int maxResponseBytes = 8192;
    }
}
