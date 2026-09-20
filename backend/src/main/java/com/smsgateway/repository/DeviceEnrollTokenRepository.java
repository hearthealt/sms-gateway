package com.smsgateway.repository;

import com.smsgateway.model.entity.DeviceEnrollToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 单行表，只需要按固定主键取那一行，所以没有任何自定义查询方法。
 *
 * <p>刻意**不提供 findAll 之类**的用法入口：这张表正常状态下至多一行，
 * 多出来的行一定是异常写入，按「取第一行」处理只会把问题藏起来。
 * 读取一律走 {@code findById(DeviceEnrollToken.SINGLETON_ID)}。
 */
@Repository
public interface DeviceEnrollTokenRepository extends JpaRepository<DeviceEnrollToken, Long> {
}
