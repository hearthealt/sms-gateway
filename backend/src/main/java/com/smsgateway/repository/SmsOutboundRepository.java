package com.smsgateway.repository;

import com.smsgateway.model.entity.SmsOutbound;
import com.smsgateway.model.enums.SmsOutboundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SmsOutboundRepository extends JpaRepository<SmsOutbound, Long> {

    /**
     * 心跳下发：这台设备身上还没交出去的那些。
     *
     * <p>只捞 PENDING —— 已经 DISPATCHED 的**绝不重发**：指令重发的代价是
     * 「设备可能多做一次」，而短信重发的代价是真的又发一条出去、又计费一次。
     */
    @Query("select o from SmsOutbound o "
            + "where o.deviceId = :deviceId and o.status = :status "
            + "order by o.id asc")
    List<SmsOutbound> findDeliverable(@Param("deviceId") Long deviceId,
                                      @Param("status") SmsOutboundStatus status,
                                      Pageable pageable);

    /**
     * 回执时按 key 取，**并且必须限定在这台设备名下**。
     *
     * <p>少了 {@code deviceId} 这个条件，拿着 A 设备令牌的人就能把 B 设备的外发短信
     * 标成「已发出」—— 而那条短信其实还躺在队列里没发出去。这与
     * {@code DeviceCommandRepository.findByIdInAndDeviceId} 是同一条授权检查。
     */
    List<SmsOutbound> findByOutboundKeyInAndDeviceId(Collection<String> keys, Long deviceId);

    /** 每日限额：这台设备今天已经建了多少条。**按入队时刻算**，不按发出时刻 —— 见 SmsOutboundService。 */
    long countByDeviceIdAndCreatedAtGreaterThanEqual(Long deviceId, LocalDateTime since);

    Optional<SmsOutbound> findById(Long id);

    /**
     * 管理端列表。两个条件都可选。
     *
     * <p>状态用「布尔开关 + 恒非空的占位值」，与 {@code EventLogRepository.search} 同一个写法：
     * 枚举参数传 null 去做 {@code is null} 判断时，Hibernate 6 对参数类型的推断出过问题。
     */
    @Query("select o from SmsOutbound o "
            + "where (:allStatuses = true or o.status = :status) "
            + "and (:deviceCode is null or o.deviceCode = :deviceCode) "
            + "order by o.id desc")
    Page<SmsOutbound> search(@Param("allStatuses") boolean allStatuses,
                             @Param("status") SmsOutboundStatus status,
                             @Param("deviceCode") String deviceCode,
                             Pageable pageable);

    /**
     * 交给设备很久却没回执的 —— 由清理任务判成「结果未知」。
     *
     * <p>留着它们停在「已下发」会让人以为还在等回执，而实际上设备要么把它丢了、
     * 要么发出了但回执没回到（心跳断了、App 被强停）。
     */
    @Query("select o from SmsOutbound o "
            + "where o.status = :status and o.dispatchedAt < :before "
            + "order by o.id asc")
    List<SmsOutbound> findStuckDispatched(@Param("status") SmsOutboundStatus status,
                                          @Param("before") LocalDateTime before,
                                          Pageable pageable);

    /**
     * 删除某台设备的全部外发记录。管理端删设备时一并调用。
     *
     * <p>与投递记录、远程指令同源：{@code device_id} 上没有外键，不主动删就是永久孤儿。
     */
    @Modifying
    @Query("delete from SmsOutbound o where o.deviceId = :deviceId")
    int deleteByDeviceId(@Param("deviceId") Long deviceId);

    /**
     * 保留策略：删掉某时刻以前的记录。
     *
     * <p>⚠️ 这是个 {@code @Modifying} 语句，**必须在可写事务里执行**：
     * Spring Data 给查询方法默认挂的是只读事务，那里 Hibernate 会直接拒绝更新/删除。
     * 调用方（{@code SmsOutboundJanitor}）用 {@code TransactionTemplate} 包着它 ——
     * 理由与 {@code DeviceCommandJanitor.purgeOld} 那次的坑完全一样。
     */
    @Modifying
    @Query("delete from SmsOutbound o where o.createdAt < :before")
    int deleteCreatedBefore(@Param("before") LocalDateTime before);
}
