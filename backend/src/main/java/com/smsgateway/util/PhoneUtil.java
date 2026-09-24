package com.smsgateway.util;

/**
 * 手机号归一化，用于跨端匹配。
 *
 * <p>设备上报的号码来自 {@code TelephonyManager}，带不带 {@code +86}、有没有空格横线，
 * 完全取决于手机厂商；而等待验证码走的是 Redis key 精确匹配，两边格式只要差一个字符
 * 就会**静默超时**（不报参数错，调用方极难排查）。
 * 所以写入方（SmsService）和读取方（WaitingService）都必须先过这里。
 */
public final class PhoneUtil {

    private PhoneUtil() {
    }

    /**
     * 打码：保留前 3 位与后 4 位，中间用星号。
     *
     * <pre>
     * "13800138000"    → "138****8000"
     * "+86 138-0013-8000" → "138****8000"
     * "12345"          → "1****"      （太短时只留首位，后段可能重叠，所以整段打掉）
     * ""/null          → null
     * </pre>
     *
     * <p>**这是给「离开服务端的数据」用的**：设备端的诊断包会把号码带出去给外部看，
     * 那里只需要知道「有没有号码、两张卡分不分得清」，不需要完整值 ——
     * 而完整值在管理后台里管理员本来就看得到，所以打码不损失任何运维信息
     * （与运行日志页保留完整值并不冲突：那一条不出服务端）。
     *
     * <p>先归一化再打码：不这么做的话 {@code +86 138****8000} 这种形态里
     * 星号旁边的数字还能拼出一部分原号，而「格式不同、打出来的结果不同」本身
     * 也会让对比两条记录变得不可靠。
     */
    public static String mask(String raw) {
        String digits = normalize(raw);
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.length() >= 11) {
            return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
        }
        if (digits.length() >= 7) {
            // 7~10 位：前 3 后 2，中间全部打掉
            return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 2);
        }
        // 位数太少，前 3 后 2 会重叠 —— 那等于把整个号码露出来，所以只留首位
        return digits.substring(0, 1) + "****";
    }

    /**
     * 归一化为纯数字，并去掉中国大陆的 86 国家码。
     *
     * <pre>
     * "+86 138-0013-8000" → "13800138000"
     * "8613800138000"     → "13800138000"
     * "13800138000"       → "13800138000"
     * "+1 415-555-1234"   → "14155551234"   （11 位，不受影响）
     * null / ""           → ""
     * </pre>
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // 用 ASCII 判断而不是 Character.isDigit：后者对阿拉伯-印度数字等
            // 非 ASCII 数字也返回 true，那些字符进 Redis key 只会带来麻烦
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }

        String value = digits.toString();
        // 只剥「86 + 11 位号码」这种 13 位形态；13 位但不以 86 开头的不动
        if (value.length() == 13 && value.startsWith("86")) {
            return value.substring(2);
        }
        return value;
    }
}
