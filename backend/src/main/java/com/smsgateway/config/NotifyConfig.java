package com.smsgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 转发用的线程池。
 *
 * <p><b>必须显式配，不能直接用 {@code @Async}</b>：{@code SmsGatewayApplication} 上已经开了
 * {@code @EnableAsync} 但全项目没有任何线程池配置，于是 {@code @Async} 会走
 * {@code SimpleAsyncTaskExecutor} —— 那个实现**每个任务新建一个线程、不复用**。
 * 投递是高频 IO，用它会直接把线程数打爆。
 *
 * <p>队列满时用 {@code CallerRunsPolicy} 而不是丢弃：让调用方（调度线程）自己跑，
 * 形成背压。丢弃等于静默丢消息，而丢弃的还恰好是高峰期的那些。
 */
@Configuration
public class NotifyConfig {

    @Bean("notifyExecutor")
    public Executor notifyExecutor(NotifyProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int max = properties.getDispatcher().getMaxConcurrentChannels();
        executor.setCorePoolSize(Math.max(2, max / 2));
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
