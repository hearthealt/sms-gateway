package com.smsgateway.model.enums;

import java.time.Duration;

/**
 * 远程指令的类型。
 *
 * <p><b>这个枚举是唯一真源</b>：中文名、有效期、是否需要参数都挂在这里。加一种指令
 * 只改这一个文件 —— 管理端的中文从后端取（与 {@link EventType#label()} 同一个做法），
 * 前端不硬编码，否则加一种就要改两处，漏掉的那处表现是界面上冒出一串英文常量名。
 *
 * <p><b>为什么 TTL 是常量而不是 SysConfig 里的一项配置。</b>一个全局的
 * {@code device-command.ttl-minutes} 对上下面每一种指令都是错的取值，而
 * 「按类型定死」本身没有可调的空间；给它做成配置只会造出一个「把它改成 0
 * 就永远下发不出去」的表单项。这条与 README 里「拒绝没有具体需求的配置面」一致。
 *
 * <p><b>过期的「停止」比过期的「启动」有害得多。</b>启动一条过期的指令没什么损失
 * （设备本来就该在跑），而一周前下发的停止如果在设备回来后生效，现场只会看到一台
 * 莫名不动的机器，且没有人记得为什么 —— 所以过期不是形同虚设的兜底，它是**正确性**。
 *
 * <p><b>这里永远不会有「下发一份新的重注册密钥」这种类型。</b>原因见
 * {@code DeviceCommandService.issue} 的说明：那条通道会让 {@code app.secret.key}
 * 泄漏后无法通过轮换止损，因为攻击者可以在被清理前换一份新密钥，从而在轮换之后
 * 继续控制那台设备。设备丢了密钥只能由现场的人扫二维码取回。
 */
public enum DeviceCommandType {

    START_GATEWAY("启动网关", Duration.ofHours(1), false),

    STOP_GATEWAY("停止网关", Duration.ofHours(1), false),

    SET_PHONE("修改本机号码", Duration.ofHours(1), true),

    REUPLOAD("触发一次重传", Duration.ofHours(1), false),

    CLEAR_UPLOADED("清理本地已上传记录", Duration.ofHours(1), false),

    /**
     * 让设备**用本机已有的身份**重跑一次注册。
     *
     * <p>没有任何密钥走网络：设备拿本地存着的 {@code enrollSecret} 自证身份，
     * 服务端只做一次 {@code verifyEnrollment}。它解决的是真实存在的三种情况：
     * App 升级后把 appVersion / deviceName 同步上来；服务端那一行被人改乱了想重新对齐；
     * 以及服务端行被误删后设备自建一行（此时用本机存着的接入口令过准入）。
     *
     * <p>TTL 取 10 分钟而不是 1 小时，因为它**是唯一一种成批改写服务端字段的指令**
     * （deviceName / phone / platform / appVersion）。一周之后才送达的重注册会把设备
     * 早已走过的旧值重新写回服务端 —— 那是一条过期的指令在制造数据回退。
     */
    RE_REGISTER("重新注册（用本机已有身份）", Duration.ofMinutes(10), false);

    private final String label;
    private final Duration ttl;
    private final boolean requiresArgument;

    DeviceCommandType(String label, Duration ttl, boolean requiresArgument) {
        this.label = label;
        this.ttl = ttl;
        this.requiresArgument = requiresArgument;
    }

    public String label() {
        return label;
    }

    /** 从签发时刻算起，超过这个时长就不再下发。落库到 {@code expires_at}。 */
    public Duration ttl() {
        return ttl;
    }

    /** 参数为空时是否应当拒绝签发。目前只有 {@link #SET_PHONE} 需要。 */
    public boolean requiresArgument() {
        return requiresArgument;
    }
}
