package com.smsgateway.exception;

/**
 * 首次注册没有带上（或带错了）本服务器的接入口令。
 *
 * <p>与 {@link EnrollmentRequiredException} 并列，但语义不同，不要合并：
 * 那个是「你没证明你是**这台设备**」（针对**已存在**的 deviceId），
 * 这个是「你没有接入**本服务器**的凭证」（只在设备**不存在**时判定）。
 * 前者认的是设备身份，后者认的是服务器准入 —— 混成一个异常，现场就分不清
 * 该去签恢复码还是该去重新扫码。
 *
 * <p>同样映射到 **403 而不是 400**：设备端把 400 归为终态直接放弃，403 才带得出
 * 可执行的指路提示（去管理后台「快速连接」重新扫一张）。
 */
public class EnrollTokenRequiredException extends RuntimeException {

    public EnrollTokenRequiredException(String message) {
        super(message);
    }
}
