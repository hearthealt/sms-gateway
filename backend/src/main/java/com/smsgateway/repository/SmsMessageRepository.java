package com.smsgateway.repository;

import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.SmsStatus;
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
public interface SmsMessageRepository extends JpaRepository<SmsMessage, Long> {

    Optional<SmsMessage> findByDeviceIdAndLocalMessageId(Long deviceId, String localMessageId);

    /**
     * 按「同一台设备 + 同一段内容」找那条正本。
     *
     * <p><b>去重是按设备做的，不是全局。</b>每台手机是一条独立的线路，各自的验证码要各自
     * 记一条：两台不同手机收到同一段内容（同一家的营销短信、或同一个码发给两台测试机）
     * 如果被判成重复，第二台那条就没有自己的记录了 —— 而那恰恰是「这台机器的码到没到」
     * 的答案。对应 uk_device_source_hash(device_id, source_hash)。
     */
    Optional<SmsMessage> findByDeviceIdAndSourceHash(Long deviceId, String sourceHash);

    /**
     * 短信分页查询，所有条件均可为 null（表示不过滤）。
     * includeIgnored 为 false 时排除 IGNORED（命中 ignore 规则的短信）。
     *
     * <p>没有 sender 维度：按发送方筛选已从管理后台界面上移除，
     * 对外接口也只按号码和时间查（见 ClientSmsService），所以查询里不再保留这个条件。
     */
    @Query("select m from SmsMessage m "
            + "where (:phone is null or m.phone like concat('%', :phone, '%')) "
            + "and (:code is null or m.code like concat('%', :code, '%')) "
            + "and (:deviceId is null or m.deviceId = :deviceId) "
            + "and (:startTime is null or m.receiveTime >= :startTime) "
            + "and (:endTime is null or m.receiveTime <= :endTime) "
            + "and (:includeIgnored = true or m.status <> :ignoredStatus) "
            + "order by m.receiveTime desc")
    Page<SmsMessage> search(@Param("phone") String phone,
                            @Param("code") String code,
                            @Param("deviceId") Long deviceId,
                            @Param("startTime") LocalDateTime startTime,
                            @Param("endTime") LocalDateTime endTime,
                            @Param("includeIgnored") boolean includeIgnored,
                            @Param("ignoredStatus") SmsStatus ignoredStatus,
                            Pageable pageable);

    /** 设备详情页的短信列表，同样默认排除 IGNORED。 */
    @Query("select m from SmsMessage m "
            + "where m.deviceId = :deviceId "
            + "and (:includeIgnored = true or m.status <> :ignoredStatus) "
            + "order by m.receiveTime desc")
    Page<SmsMessage> findByDeviceIdOrderByReceiveTimeDesc(@Param("deviceId") Long deviceId,
                                                          @Param("includeIgnored") boolean includeIgnored,
                                                          @Param("ignoredStatus") SmsStatus ignoredStatus,
                                                          Pageable pageable);

    /**
     * 同上，再加一个关键词：发送方**或**正文命中。
     *
     * <p>关键词在**服务端**过滤，而不是让设备端在已加载的几页里筛 —— 设备端的记录页是
     * 无限滚动的（一次只加载一页），前端筛只能筛到「已经拉到的那几页」，
     * 于是「明明有这条却说没有」，比不做搜索更误导。
     *
     * <p>{@code :allKeywords} 是那个老写法（与 {@code EventLogRepository.search} 同源）：
     * 枚举/参数传 null 去做 {@code is null} 判断时 Hibernate 6 的类型推断出过问题，
     * 所以「要不要过滤」与「过滤成什么」拆成两个参数。这里关键词是字符串，本可以用
     * {@code :keyword is null}，但保持一致更好读。
     */
    @Query("select m from SmsMessage m "
            + "where m.deviceId = :deviceId "
            + "and (:includeIgnored = true or m.status <> :ignoredStatus) "
            + "and (:allKeywords = true "
            + "     or m.sender like concat('%', :keyword, '%') "
            + "     or m.content like concat('%', :keyword, '%')) "
            + "order by m.receiveTime desc")
    Page<SmsMessage> searchByDevice(@Param("deviceId") Long deviceId,
                                    @Param("includeIgnored") boolean includeIgnored,
                                    @Param("ignoredStatus") SmsStatus ignoredStatus,
                                    @Param("allKeywords") boolean allKeywords,
                                    @Param("keyword") String keyword,
                                    Pageable pageable);

    long countByReceiveTimeBetween(LocalDateTime start, LocalDateTime end);

    long countByReceiveTimeBetweenAndCodeIsNotNull(LocalDateTime start, LocalDateTime end);

    /**
     * 某台设备在时间范围内的短信数。
     *
     * 与管理端仪表盘不同，这里**不排除 IGNORED**：设备端的记录页把被规则忽略的短信
     * 也列了出来（并标注「被规则忽略」），所以计数必须把它们算进去，
     * 否则设备上会再次出现「数字是 0、点进去却有内容」这种对不上的情况。
     */
    @Query("select count(m) from SmsMessage m "
            + "where m.deviceId = :deviceId and m.receiveTime between :start and :end")
    long countByDeviceAndReceiveTimeBetween(@Param("deviceId") Long deviceId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    /** 同上，但只数真的解析出验证码的。 */
    @Query("select count(m) from SmsMessage m "
            + "where m.deviceId = :deviceId and m.receiveTime between :start and :end "
            + "and m.code is not null and m.code <> ''")
    long countCodesByDeviceAndReceiveTimeBetween(@Param("deviceId") Long deviceId,
                                                 @Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);

    /**
     * 按天分组的统计，用于仪表盘趋势图。
     * 排除 IGNORED，避免被规则忽略的营销短信计入总量。
     * 返回 [java.sql.Date, 总数, 验证码数] 的数组列表。
     */
    @Query("select function('date', m.receiveTime), count(m), "
            + "sum(case when m.code is not null and m.code <> '' then 1 else 0 end) "
            + "from SmsMessage m "
            + "where m.receiveTime >= :from "
            + "and m.status <> :ignoredStatus "
            + "group by function('date', m.receiveTime) "
            + "order by function('date', m.receiveTime)")
    List<Object[]> countDailySince(@Param("from") LocalDateTime from,
                                   @Param("ignoredStatus") SmsStatus ignoredStatus);

    /**
     * 按天分组的统计，**限定某台设备**。设备端主页的「近 7 天」用它。
     *
     * 与 countDailySince 同源（同样排除 IGNORED），只多一个设备条件 ——
     * 设备端只能看自己那台，不能把整个服务器的量当成自己的。
     * 返回 [java.sql.Date, 总数, 验证码数]。
     */
    @Query("select function('date', m.receiveTime), count(m), "
            + "sum(case when m.code is not null and m.code <> '' then 1 else 0 end) "
            + "from SmsMessage m "
            + "where m.deviceId = :deviceId "
            + "and m.receiveTime >= :from "
            + "and m.status <> :ignoredStatus "
            + "group by function('date', m.receiveTime) "
            + "order by function('date', m.receiveTime)")
    List<Object[]> countDailySinceByDevice(@Param("deviceId") Long deviceId,
                                           @Param("from") LocalDateTime from,
                                           @Param("ignoredStatus") SmsStatus ignoredStatus);

    /**
     * 按小时分组的统计，用于设备端主页的「今日分布」。调用方传今天 0 点作为 from。
     *
     * 只返回**有数据的小时**，缺的小时由服务层补 0 —— SQL 里补零要靠日历表或递归 CTE，
     * 为一张 24 根柱的小图不值得。
     * 返回 [小时(0-23), 总数, 验证码数]。
     */
    @Query("select function('hour', m.receiveTime), count(m), "
            + "sum(case when m.code is not null and m.code <> '' then 1 else 0 end) "
            + "from SmsMessage m "
            + "where m.deviceId = :deviceId "
            + "and m.receiveTime >= :from "
            + "and m.status <> :ignoredStatus "
            + "group by function('hour', m.receiveTime) "
            + "order by function('hour', m.receiveTime)")
    List<Object[]> countHourlySinceByDevice(@Param("deviceId") Long deviceId,
                                            @Param("from") LocalDateTime from,
                                            @Param("ignoredStatus") SmsStatus ignoredStatus);

    /**
     * 取一批早于给定时刻的短信 id，最多 {@code batchSize} 条。
     *
     * <p><b>清理必须先取 id 再删</b>，不能直接 {@code delete ... limit}：投递记录只存
     * {@code sms_message_id}（那张表上还没有外键），短信一删就再也找不出哪些投递记录
     * 属于它们，控制台上会留下一行行空白。所以顺序是「取 id → 删投递记录 → 删短信」。
     *
     * <p>用原生 SQL 是因为要带 LIMIT。
     */
    @Query(value = "select id from sms_message where receive_time < :before order by id limit :batchSize",
            nativeQuery = true)
    List<Long> findIdsByReceiveTimeBefore(@Param("before") LocalDateTime before,
                                         @Param("batchSize") int batchSize);

    /**
     * 按 id 批量删，返回实际删除行数。
     *
     * <p>必须分批：一次删几百万行会形成单个巨型事务，长时间持有锁、把 undo log 撑爆，
     * 期间同库的短信写入都会被拖住。
     *
     * <p>调用方见 {@code SmsRetentionJob}，它循环调用直到返回 0。
     */
    @Modifying
    @Query("delete from SmsMessage m where m.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 删除某台设备的全部短信。管理端删设备时一并调用（见 AdminDeviceService.delete）。
     *
     * <p>用批量 delete 而不是派生方法：派生方法会先把所有行加载进内存再逐条删，
     * 一台设备积下几万条时那是几万次 DELETE。返回受影响行数，便于日志里交代删了多少。
     */
    @Modifying
    @Query("delete from SmsMessage m where m.deviceId = :deviceId")
    int deleteByDeviceId(@Param("deviceId") Long deviceId);
}
