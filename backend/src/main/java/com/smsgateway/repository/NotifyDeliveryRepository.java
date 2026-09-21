package com.smsgateway.repository;

import com.smsgateway.model.entity.NotifyDelivery;
import com.smsgateway.model.enums.NotifyDeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface NotifyDeliveryRepository extends JpaRepository<NotifyDelivery, Long> {

    /**
     * 调度主查询：到点的待投递记录，**且所属渠道处于启用状态**。
     *
     * <p>按 id 升序 = 先来先发。短信是有时效的（验证码 5 分钟就过期），
     * 所以**不能让新记录插队**——否则高峰期一条迟到的验证码会永远排在队尾。
     * 用 id 而不是 next_retry_at 排序：重试过的记录 next_retry_at 更大，
     * 按它排就等于让失败记录永远垫底。
     *
     * <p><b>「渠道启用」这个条件是必须的，不是优化。</b>停用的渠道不进投递
     * （见 {@code NotifyDispatcher.sendBatch}），它们的记录会一直停在待投递 ——
     * 而这条查询是「取全局最旧的 N 条」。少了这个条件，一个停用渠道只要积压到
     * 批次上限，就会**每次轮询都占满整批**，别的启用渠道一条也进不来：
     * 停用一个渠道会连带把其他渠道的转发一起停掉，而且看不出是谁干的。
     */
    @Query("select d from NotifyDelivery d "
            + "where d.status = :status and d.nextRetryAt <= :now "
            + "and exists (select 1 from NotifyChannel c where c.id = d.channelId and c.enabled = true) "
            + "order by d.id asc")
    List<NotifyDelivery> findDue(@Param("status") NotifyDeliveryStatus status,
                                 @Param("now") LocalDateTime now,
                                 Pageable pageable);

    /**
     * 卡死在 SENDING 的记录。
     *
     * <p>进程在「已标记 SENDING、还没发完」之间被杀时，这一行会永远停在 SENDING ——
     * 调度器只捞 PENDING，于是这条短信**静默地再也不发了**，而且管理端上看不出
     * 它和「正在发」有什么区别。调度器定期把停留过久的退回 PENDING。
     */
    @Query("select d from NotifyDelivery d "
            + "where d.status = :status and d.updatedAt < :before")
    List<NotifyDelivery> findStuck(@Param("status") NotifyDeliveryStatus status,
                                   @Param("before") LocalDateTime before,
                                   Pageable pageable);

    /**
     * 把一个渠道下所有**还没发出去**的记录一次性置为终态。
     *
     * <p>停用渠道时调用。不清的话那些记录会一直停在待投递 —— 而它们的
     * {@code next_retry_at} 停在过去的时间，管理端显示成「待投递 + 下次重试 13:51」，
     * 读起来像马上要发出去，实际不会再发。清完之后它们是「已取消」，一眼能看懂。
     *
     * <p>只动 PENDING / FAILED / SENDING 三种非终态；已经 SUCCESS 或 DEAD 的不碰。
     *
     * @return 被取消的条数，便于日志与界面说清「停用顺带取消了什么」
     */
    @Modifying
    @Transactional
    @Query("update NotifyDelivery d set d.status = com.smsgateway.model.enums.NotifyDeliveryStatus.CANCELLED, "
            + "d.lastError = :reason "
            + "where d.channelId = :channelId "
            + "and d.status in (com.smsgateway.model.enums.NotifyDeliveryStatus.PENDING, "
            + "com.smsgateway.model.enums.NotifyDeliveryStatus.FAILED, "
            + "com.smsgateway.model.enums.NotifyDeliveryStatus.SENDING)")
    int cancelUnsentForChannel(@Param("channelId") Long channelId, @Param("reason") String reason);

    /**
     * 把所有「所属渠道已停用或已删除」的未投递记录一次性置为终态。
     *
     * <p>调度器每分钟跑一次当兜底。为什么不能只在停用渠道那一步清：**停用有两个入口**——
     * 管理员点停用（走 {@code NotifyChannelService.setEnabled}），
     * 以及渠道连续失败到阈值**自动停用**（走 {@code NotifyDispatcher.disableChannel}）。
     * 后者不经过前者，积压就漏了。还有「发送途中渠道被停用」那个窗口。
     *
     * <p>交给一条按状态的清扫查询，三个入口就都覆盖了，而且不必在每条路径上都记得补一刀。
     *
     * <p><b>不含 SENDING</b>：那条记录正在发，结果还没回来，此时判死会与
     * {@code applyResult} 抢着写同一行。它要么自己走到终态，要么被
     * {@code findStuck} 退回 PENDING，下一轮清扫就收掉它了。
     */
    @Modifying
    @Transactional
    @Query("update NotifyDelivery d set d.status = com.smsgateway.model.enums.NotifyDeliveryStatus.CANCELLED, "
            + "d.lastError = :reason "
            + "where d.status in (com.smsgateway.model.enums.NotifyDeliveryStatus.PENDING, "
            + "com.smsgateway.model.enums.NotifyDeliveryStatus.FAILED) "
            + "and not exists (select 1 from NotifyChannel c where c.id = d.channelId and c.enabled = true)")
    int cancelUnsentForDisabledChannels(@Param("reason") String reason);

    /** 管理端投递记录分页。渠道与状态均可为 null（表示不过滤）。 */
    @Query("select d from NotifyDelivery d "
            + "where (:channelId is null or d.channelId = :channelId) "
            + "and (:status is null or d.status = :status) "
            + "order by d.id desc")
    Page<NotifyDelivery> search(@Param("channelId") Long channelId,
                                @Param("status") NotifyDeliveryStatus status,
                                Pageable pageable);

    /** 某条短信的投递情况，用于短信详情页显示「已转发到：运维群 ✓ / 我的微信 ✗」。 */
    List<NotifyDelivery> findBySmsMessageId(Long smsMessageId);

    /**
     * 删除某台设备的全部投递记录。
     *
     * <p>投递记录只存 {@code sms_message_id}、**不自带 device_id**，所以必须在短信被删
     * 之前调用：短信一没就再也认不回它们（这张表上也没有外键，数据库不会连带删、也不挡）。
     * 调用方见 {@code AdminDeviceService.delete}。
     *
     * <p>用原生 SQL 是因为要 join 到 sms_message —— JPQL 的 delete 不支持 join。
     */
    @Modifying
    @Query(value = "delete d from notify_delivery d join sms_message m on m.id = d.sms_message_id "
            + "where m.device_id = :deviceId", nativeQuery = true)
    int deleteByDeviceId(@Param("deviceId") Long deviceId);

    /**
     * 删除这批短信的投递记录。保留策略清旧短信时调用（见 {@code SmsRetentionJob}），
     * 同样必须在短信被删之前。
     *
     * <p>调用方要保证集合非空：JPQL 的 {@code in ()} 是非法语法。
     */
    @Modifying
    @Query("delete from NotifyDelivery d where d.smsMessageId in :smsMessageIds")
    int deleteBySmsMessageIdIn(@Param("smsMessageIds") Collection<Long> smsMessageIds);

    /**
     * 一个渠道积压了多少条还没发出去的。
     *
     * <p>管理端拿它做「这个渠道是不是卡住了」的最直接信号 —— 比连续失败次数更早，
     * 因为限流推迟的那些记录不算失败，但积压会一直在涨。
     */
    @Query("select count(d) from NotifyDelivery d "
            + "where d.channelId = :channelId and d.status in ('PENDING', 'SENDING', 'FAILED')")
    long countBacklog(@Param("channelId") Long channelId);
}
