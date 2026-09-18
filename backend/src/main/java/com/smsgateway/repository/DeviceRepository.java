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
import java.util.Set;

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

    /**
     * 在线设备数：未被禁用、最后心跳在给定时间之后、**且晚于设备主动上报的停止时刻**。
     *
     * <p>最后那半句不能少：设备点了「停止网关」会主动报一次（见 DeviceService.markOffline），
     * 统计口径必须与 DeviceService.isOnline 完全一致，否则仪表盘说 3 台在线、
     * 设备列表里 2 台是灰的 —— 两套说法比都不准更糟。
     */
    @Query("select count(d) from SmsDevice d "
            + "where d.status <> 'DISABLED' and d.lastHeartbeatAt is not null and d.lastHeartbeatAt > :since "
            + "and (d.reportedOfflineAt is null or d.lastHeartbeatAt > d.reportedOfflineAt)")
    long countOnlineSince(@Param("since") LocalDateTime since);

    /** 离线设备数。判据与 countOnlineSince 严格互补（未被禁用的整体减去在线）。 */
    @Query("select count(d) from SmsDevice d "
            + "where d.status <> 'DISABLED' and (d.lastHeartbeatAt is null or d.lastHeartbeatAt <= :since "
            + "or (d.reportedOfflineAt is not null and d.lastHeartbeatAt <= d.reportedOfflineAt))")
    long countOfflineSince(@Param("since") LocalDateTime since);

    /** 当前在线的设备主键集合。给 DevicePresenceWatcher 判断「集合变了没有」。 */
    @Query("select d.id from SmsDevice d "
            + "where d.status <> 'DISABLED' and d.lastHeartbeatAt is not null and d.lastHeartbeatAt > :since "
            + "and (d.reportedOfflineAt is null or d.lastHeartbeatAt > d.reportedOfflineAt)")
    Set<Long> findOnlineIds(@Param("since") LocalDateTime since);
}
