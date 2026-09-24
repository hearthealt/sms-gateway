package com.smsgateway.model.enums;

/**
 * 一条外发短信的状态。
 *
 * <pre>
 * PENDING ──心跳下发──&gt; DISPATCHED ──设备回执 SENT──&gt; SENT
 *    │                        ├─设备回执 FAILED──&gt; FAILED
 *    │                        └─超时无回执────────&gt; UNKNOWN
 *    └─管理员撤销──&gt; CANCELLED
 * </pre>
 *
 * <p><b>DISPATCHED 与 SENT 必须分开</b>：前者只说明「我们把它塞进了心跳响应」，
 * 后者才说明「那条短信真的离开了那台手机」。合成一个的话，回执一丢就会把
 * 「不知道发没发」显示成「已发出」—— 而这条短信是**要计费**的，
 * 而外发短信又**绝不重发**（重发是真的再发一条出去），所以这个区分没有替代品。
 *
 * <p>{@link #UNKNOWN} 是这份状态机里最诚实的一格：那些「下发了、但一直没等到回执」
 * 的记录。它们既不能算成功（没证据），也不能算失败（可能已经发出去了），
 * 更不能重发（可能重复计费）。控制台上照实说「结果未知」，让人自己去对账。
 */
public enum SmsOutboundStatus {

    PENDING("待下发", false),
    DISPATCHED("已下发", false),
    SENT("已发出", true),
    FAILED("发送失败", true),
    CANCELLED("已撤销", true),
    UNKNOWN("结果未知", true);

    private final String label;
    private final boolean terminal;

    SmsOutboundStatus(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }

    public String label() {
        return label;
    }

    /**
     * 是否已经走到终态。
     *
     * <p>PENDING 与 DISPATCHED 都还能被 {@code DeviceCommandJanitor} 之外的东西改动 ——
     * 只有 PENDING 能撤销：一旦交给设备，就没法再叫回来了。
     */
    public boolean isTerminal() {
        return terminal;
    }
}
