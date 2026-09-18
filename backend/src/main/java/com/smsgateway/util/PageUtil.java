package com.smsgateway.util;

/**
 * 分页参数的统一限幅。
 *
 * <p>对外接口（{@code /api/sms/list}）一直限着 pageSize 上限，但管理端和设备端没限 ——
 * 而 {@code /api/device/sms} 是设备可以直接调的：一个被窃取的设备令牌就能发
 * {@code ?pageSize=50000000}，让 Hibernate 执行 {@code setMaxResults(50000000)}
 * 并把整张 sms_message 拉进内存，同库的所有写入一起被拖垮。
 *
 * <p>限幅收在这里而不是四处各写一遍：原先就是「一处限了、三处没限」这么来的。
 */
public final class PageUtil {

    /** 单页条数上限。 */
    public static final int MAX_PAGE_SIZE = 100;

    private PageUtil() {
    }

    /** 页码下限 1。{@code PageRequest.of} 对 0 或负数会直接抛异常。 */
    public static int safePage(int page) {
        return Math.max(page, 1);
    }

    /**
     * 把每页条数夹进 [1, {@link #MAX_PAGE_SIZE}]。
     * 超出上限是静默收敛而不是报错 —— 与对外接口一直以来的行为保持一致。
     */
    public static int safePageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }
}
