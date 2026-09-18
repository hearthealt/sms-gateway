package com.smsgateway.exception;

/**
 * 该 deviceId 已存在，但注册请求没有带上（或带错了）重注册密钥。
 *
 * <p>单独定义一个类型，是为了能映射到 **403 而不是 400**。这中间的区别对现场很重要：
 * 400 的语义是「请求本身写错了」，而这里的语义是「你没有证明你是这台设备」。
 * 而且设备端会把 400 归为终态错误直接放弃，403 才能带出一条可执行的提示 ——
 * 让现场知道该去控制台签一张恢复码，而不是反复重试。
 */
public class EnrollmentRequiredException extends RuntimeException {

    public EnrollmentRequiredException(String message) {
        super(message);
    }
}
