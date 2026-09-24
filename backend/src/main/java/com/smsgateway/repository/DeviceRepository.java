package com.smsgateway.repository;

import com.smsgateway.model.entity.SmsDevice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * 「沉默下去」超过给定时刻的设备 —— 离线告警的判据。
     *
     * <p>三个条件缺一不可，理由见 {@code OfflineAlertWatcher} 的类注释：
     *
     * <ol>
     *   <li>{@code status <> 'DISABLED'} —— 被禁用的设备本来就该安静，
     *       为它告警等于对着一个已经处理过的状态重复叫人。</li>
     *   <li>{@code lastHeartbeatAt is not null} —— **从没心跳过的设备是「还没启用」，
     *       不是「离线」**。少了这一条，每一台刚扫码接入、还没被打开的机器都会在
     *       十几分钟后收到一条离线告警。</li>
     *   <li>{@code reportedOfflineAt is null or lastHeartbeatAt > reportedOfflineAt}
     *       —— 只挑「沉默下去的」，不挑「说过再见的」。{@code reported_offline_at}
     *       只在有人主动停网关时写入（设备自己报的，或服务端在收到远程停机回执时补的），
     *       那不是故障，不该告警。**否则管理员远程停掉一台设备之后，会收到一条
     *       由他自己的动作触发的离线告警。**</li>
     * </ol>
     *
     * <p>判据与 {@link #countOnlineSince} / {@code DeviceService.isOnline} 同源，
     * 所以不会出现「仪表盘说在线、告警说离线」这种两套说法。
     */
    @Query("select d from SmsDevice d "
            + "where d.status <> 'DISABLED' and d.lastHeartbeatAt is not null "
            + "and (d.reportedOfflineAt is null or d.lastHeartbeatAt > d.reportedOfflineAt) "
            + "and d.lastHeartbeatAt < :silentSince "
            + "order by d.id asc")
    List<SmsDevice> findSilentSince(@Param("silentSince") LocalDateTime silentSince);
}
