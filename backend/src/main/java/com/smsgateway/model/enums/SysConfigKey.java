package com.smsgateway.model.enums;

import java.util.regex.Pattern;

/**
 * 可以在管理后台改的运行期配置。
 *
 * <p><b>为什么这些不放 application.yml 而要进数据库</b>：yml 里的值改了必须重启，
 * 而这几项都是运维过程中会想调、且调完就想立刻看到效果的。项目里已有先例 ——
 * {@code api_key} 当初就是从 yml 的 {@code app.client.token} 迁到库里的，
 * {@code schema.sql} 里写着理由。这不是新发明，是把同一个决定再做一遍。
 *
 * <p><b>哪些不放进来</b>：
 * <ul>
 *   <li><b>密钥一律留在环境变量</b>（{@code app.secret.key}、{@code NOTIFY_ENCRYPT_KEY}）。
 *       加密密钥尤其不能进库：用库里的密钥解库里的密文是循环依赖。</li>
 *   <li><b>引导类配置</b>（管理员初始密码、数据源）—— 只在启动那一刻有意义。</li>
 *   <li><b>每次调用都读、且极少改的</b>（HTTP 超时、并发数）—— 收益抵不过多出来的表单项，
 *       会让真正要紧的那几个淹没在里面。</li>
 * </ul>
 *
 * <p>枚举里带上 label / 说明 / 分组，是为了让管理端直接渲染 —— 否则同一份文案要在
 * 前后端各写一遍，改了一处另一处就不一致。
 *
 * <p><b>说明文字一律写短。</b>设置页上每一行都摆一段解释，真正要紧的那几句会被淹没。
 * 只留「不知道就会做错决定」的那些信息，其余的写在代码注释里 —— 那是给改代码的人看的。
 */
public enum SysConfigKey {

    // ---------------------------------------------------------------- 消息转发

    NOTIFY_ENABLED(
            "notify.enabled",
            "false",
            Type.BOOLEAN,
            Group.NOTIFY,
            "消息转发",
            "开启后短信才会被转发到已配置的渠道。"),

    NOTIFY_INCLUDE_META(
            "notify.include-meta",
            "true",
            Type.BOOLEAN,
            Group.NOTIFY,
            "附带来源信息",
            "在正文前加一行「设备 · 发送方 · 时间」。多台手机或双卡时建议保留，否则分不清是哪台收的。"),

    NOTIFY_POLL_INTERVAL_MS(
            "notify.poll-interval-ms",
            "1000",
            Type.INT,
            Group.NOTIFY,
            "轮询间隔（毫秒）",
            "投递延迟主要由它决定。调小会增加数据库压力。"),

    NOTIFY_FAILURE_THRESHOLD(
            "notify.failure-threshold",
            "10",
            Type.INT,
            Group.NOTIFY,
            "渠道失败阈值",
            "渠道连续失败达到这个次数后自动停用。"),

    // ---------------------------------------------------------------- 短信数据

    SMS_RETENTION_DAYS(
            "sms.retention-days",
            "0",
            Type.INT,
            Group.DATA,
            "短信保留天数",
            "0 表示不清理。每条短信都存着正文与验证码，不清理表只会增 —— 请按合规要求决定。"),

    SMS_CLEANUP_TIME(
            "sms.cleanup-time",
            "03:30",
            Type.TIME,
            Group.DATA,
            "清理执行时间",
            "每天在这个时刻清理。仅在保留天数大于 0 时生效。"),

    // ---------------------------------------------------------------- 安全与会话

    ADMIN_TOKEN_TTL_SECONDS(
            "admin.token-ttl-seconds",
            "72000",
            Type.INT,
            Group.SECURITY,
            "登录有效期（秒）",
            "登录后多久需要重新登录。已登录的会话不受影响，新时长只对之后登录的人生效。"),

    API_KEY_CACHE_TTL_SECONDS(
            "api-key.cache-ttl-seconds",
            "60",
            Type.INT,
            Group.SECURITY,
            "API Key 校验缓存（秒）",
            "改大减轻数据库压力，但禁用密钥后要过这么久才生效（主动禁用会立刻清缓存）。");

    public enum Type {
        BOOLEAN, INT, STRING,
        /** {@code HH:mm}，给前端用时间选择器而不是让人手填 cron。 */
        TIME
    }

    /** 管理端按它把配置分成几张卡片。 */
    public enum Group {
        NOTIFY("消息转发"),
        DATA("短信数据"),
        SECURITY("安全与会话");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");

    private final String key;
    private final String defaultValue;
    private final Type type;
    private final Group group;
    private final String label;
    private final String description;

    SysConfigKey(String key, String defaultValue, Type type, Group group,
                 String label, String description) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.type = type;
        this.group = group;
        this.label = label;
        this.description = description;
    }

    /** 存库用的键名。**不要改**：改了等于把已有的设置丢回默认值。 */
    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public Type type() {
        return type;
    }

    public Group group() {
        return group;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /**
     * 按键名反查。
     *
     * @return 不认识时返回 null —— 表里出现不认识的键时静默忽略，不要报错：
     *         那种行多半是旧版本留下的（或回滚后的残留），不该因为它的存在起不来。
     */
    public static SysConfigKey byKey(String key) {
        for (SysConfigKey candidate : values()) {
            if (candidate.key.equals(key)) {
                return candidate;
            }
        }
        return null;
    }

    /** TIME 类型的格式校验，给 {@code SysConfigService} 用。 */
    public static boolean isValidTime(String value) {
        return value != null && TIME_PATTERN.matcher(value.trim()).matches();
    }
}
