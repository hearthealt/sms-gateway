package com.smsgateway.service.notify;

/**
 * 转发功能没配好就被人用了 —— 通常是没设那两个环境变量。
 *
 * <p><b>单独一个类型，是为了让它的消息能原样送到使用者眼前。</b>它落进兜底的
 * {@code Exception} 处理器会变成一句「服务器内部错误」+ 后端一行堆栈 ——
 * 而现场需要的是「去设哪两个变量、怎么生成密钥、然后重启」。
 * 这不是服务器内部错误，是配置没做完。
 *
 * <p>映射到 **503** 而不是 500：503 的语义是「服务暂时不可用」，比 500 更贴切，
 * 也便于前端把它和真正的故障区分开（前端据此显示一条配置指引，而不是一句报错）。
 */
public class NotifyNotConfiguredException extends RuntimeException {

    public NotifyNotConfiguredException(String message) {
        super(message);
    }
}
