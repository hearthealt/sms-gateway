package com.smsgateway.repository;

import com.smsgateway.model.entity.EventLog;
import com.smsgateway.model.enums.EventLevel;
import com.smsgateway.model.enums.EventType;
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

@Repository
public interface EventLogRepository extends JpaRepository<EventLog, Long> {

    /**
     * 管理端的事件查询，条件均可选。
     *
     * <p><b>为什么用 {@code :allTypes} 这种布尔开关，而不是 {@code :type is null}</b>：
     * 与 {@code SmsMessageRepository.search} 里 {@code includeIgnored + ignoredStatus}
     * 是同一个写法。枚举参数传 null 去做 {@code is null} 判断时，Hibernate 6 对参数类型
     * 的推断是出过问题的；把「要不要过滤」和「过滤成什么」拆成两个参数，
     * 就永远不会真的绑一个 null 枚举进去。代价只是调用方多传一个布尔。
     *
     * @param allTypes   true 表示不按类型过滤
     * @param type       仅当 allTypes 为 false 时有意义；为 true 时调用方必须仍传一个非空值
     * @param allLevels 同上，按级别
     * @param level      同上
     */
    @Query("select e from EventLog e "
            + "where (:allTypes = true or e.eventType = :type) "
            + "and (:allLevels = true or e.level = :level) "
            + "and (:deviceId is null or e.deviceId = :deviceId) "
            + "and (:from is null or e.createdAt >= :from) "
            + "and (:to is null or e.createdAt < :to) "
            + "order by e.createdAt desc")
    Page<EventLog> search(@Param("allTypes") boolean allTypes,
                          @Param("type") EventType type,
                          @Param("allLevels") boolean allLevels,
                          @Param("level") EventLevel level,
                          @Param("deviceId") Long deviceId,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          Pageable pageable);

    /**
     * 早于某时刻的一批 id，供保留策略分批删。
     *
     * <p>用原生 SQL 是因为要带 LIMIT（与 {@code SmsMessageRepository.findIdsByReceiveTimeBefore}
     * 同一个理由：一次删几百万行会形成单个巨型事务）。
     */
    @Query(value = "select id from event_log where created_at < :before order by id limit :batchSize",
            nativeQuery = true)
    List<Long> findIdsByCreatedAtBefore(@Param("before") LocalDateTime before,
                                        @Param("batchSize") int batchSize);

    /** 按 id 批量删，返回实际删除行数。循环调用直到返回 0。 */
    @Modifying
    @Query("delete from EventLog e where e.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 把指向这些短信的引用置空。
     *
     * <p>短信被保留策略清掉之后，事件行还留着 sms_message_id —— 那张表上没有外键，
     * 不清的话管理端会展示一个指向已删短信的引用（点进去 404）。与
     * {@code SmsRetentionJob} 里「删短信前先删投递记录」是同一条「不留孤儿」原则，
     * 只是这里能置空就不必删事件行：事件本身仍然有信息量。
     */
    @Modifying
    @Query("update EventLog e set e.smsMessageId = null where e.smsMessageId in :ids")
    int clearSmsMessageIds(@Param("ids") Collection<Long> ids);

    /** 删除某台设备的全部事件。管理端删设备时一并调用。 */
    @Modifying
    @Query("delete from EventLog e where e.deviceId = :deviceId")
    int deleteByDeviceId(@Param("deviceId") Long deviceId);
}
