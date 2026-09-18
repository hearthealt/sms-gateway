package com.smsgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
// 短信历史的定期清理（SmsRetentionJob）靠它。默认不删数据，见那个类的说明。
@EnableScheduling
public class SmsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmsGatewayApplication.class, args);
    }

}