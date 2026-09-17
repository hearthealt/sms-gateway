package com.smsgateway.repository;

import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.SmsStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SmsMessageRepository extends JpaRepository<SmsMessage, Long> {

    Optional<SmsMessage> findByDeviceIdAndLocalMessageId(Long deviceId, String localMessageId);

    Optional<SmsMessage> findBySourceHash(String sourceHash);

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
}
