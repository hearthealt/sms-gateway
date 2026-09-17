package com.smsgateway.repository;

import com.smsgateway.model.entity.SmsDevice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<SmsDevice, Long> {

    Optional<SmsDevice> findByDeviceId(String deviceId);

    Optional<SmsDevice> findByDeviceToken(String deviceToken);

    boolean existsByDeviceId(String deviceId);

    /**
     * 管理后台设备分页查询，两个条件均可为 null（表示不过滤）。
     * 用 concat 拼接通配符，避免 JPQL 中 like %:param% 的语法问题。
     */
    @Query("select d from SmsDevice d "
            + "where (:deviceId is null or d.deviceId like concat('%', :deviceId, '%')) "
            + "and (:phone is null or d.phoneNumber like concat('%', :phone, '%')) "
            + "order by d.id desc")
    Page<SmsDevice> search(@Param("deviceId") String deviceId,
                           @Param("phone") String phone,
                           Pageable pageable);

    /** 在线设备数：未被禁用且最后心跳在给定时间之后。 */
    @Query("select count(d) from SmsDevice d "
            + "where d.status <> 'DISABLED' and d.lastHeartbeatAt is not null and d.lastHeartbeatAt > :since")
    long countOnlineSince(@Param("since") LocalDateTime since);

    /** 离线设备数：未被禁用，且从未心跳或最后心跳已过期。 */
    @Query("select count(d) from SmsDevice d "
            + "where d.status <> 'DISABLED' and (d.lastHeartbeatAt is null or d.lastHeartbeatAt <= :since)")
    long countOfflineSince(@Param("since") LocalDateTime since);
}
