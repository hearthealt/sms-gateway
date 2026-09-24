package com.smsgateway.model.enums;

/**
 * 远程指令的状态机。
 *
 * <pre>
 * PENDING ─下发→ SENT ─回执 DONE→ ACKED
 *                  ├─回执 FAILED/REJECTED→ FAILED
 *                  ├─下次 next_deliver_at 到点 → 再下发（attempts++）
 *                  └─attempts 用尽 / 超过 expires_at → EXPIRED
 *     └─管理员撤销（PENDING 与 SENT 都可）→ CANCELLED
 * </pre>
 *
 * <p><b>SENT 是一个会被长期停留的状态，而不是「等一下就结束」的中间态。</b>
 * 回执丢了、设备离线、进程被杀，都会让一条指令停在 SENT —— 这正是它需要被
 * 重复下发的原因（见 {@code DeviceCommandService.claimForDelivery}）。
 * 把 SENT 当成「已送达」的终态来展示，现场就会看到一条永远停在「已下发」、
 * 既没有失败也没有成功的记录，而那恰恰是最难查的一种。
 *
 * <p>中文名挂在枚举上（同 {@link EventType#label()}）：管理端从后端取，
 * 且枚举的构造器要求每个值都必须给一个 —— 加状态忘了补文案会编译不过。
 */
public enum DeviceCommandStatus {

    PENDING("待下发", false),
    SENT("已下发", false),
    ACKED("已执行", true),
    FAILED("执行失败", true),
    EXPIRED("已过期", true),
    CANCELLED("已撤销", true);

    private final String label;
    private final boolean terminal;

    // terminal 显式传进来，**不在构造器里用 `this == ACKED` 那种比较**：
    // 枚举常量在构造自己那一个时，同类型的其他常量的静态字段还没被赋值，
    // 于是 `this == ACKED` 在构造 ACKED 自身时拿到的是 null 与 null 比较 ——
    // 结果恒为 false，ACKED 会被算成非终态。这类错误不会报错，只会让一个
    // 「已完成的指令」还能被撤销、还能被继续下发。
    DeviceCommandStatus(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }

    public String label() {
        return label;
    }

    /**
     * 是否已经走到终态：不再下发，且不可撤销。
     *
     * <p>PENDING 与 SENT 都还能撤销 —— 撤销一条已下发但没回执的指令，是唯一能
     * 阻止它在设备下次联系服务器时生效的手段，而那个场景很常见：管理员在设备离线
     * 时点了「停止网关」，随后发现点错了。
     */
    public boolean isTerminal() {
        return terminal;
    }
}
