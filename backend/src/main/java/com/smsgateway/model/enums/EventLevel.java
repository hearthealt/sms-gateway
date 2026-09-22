package com.smsgateway.model.enums;

/**
 * 运行事件的重要程度。管理端的列表按它上色，也是「只看出问题的」那个筛选维度。
 *
 * <p>只有三档，刻意不再细分：
 * <ul>
 *   <li>{@link #INFO} 正常路径的锚点。一条短信走完了全程、设备上线，都在这档。</li>
 *   <li>{@link #WARN} 没成功、但**系统自己会处理**（重复、重试中、设备离线）。
 *       看到它说明有事发生，但不一定要做什么。</li>
 *   <li>{@link #ERROR} 需要人看一眼的（令牌失效、注册被拒、渠道被自动停用）。</li>
 * </ul>
 */
public enum EventLevel {
    INFO,
    WARN,
    ERROR
}
