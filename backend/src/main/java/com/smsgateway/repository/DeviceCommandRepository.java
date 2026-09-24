package com.smsgateway.repository;

import com.smsgateway.model.entity.DeviceCommand;
import com.smsgateway.model.enums.DeviceCommandStatus;
import com.smsgateway.model.enums.DeviceCommandType;
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
public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, Long> {

    /**
     * 心跳下发时的主查询：这台设备身上「还没结束、且到了该再发一次的时候」的指令。
     *
     * <p><b>{@code types} 是参数而不是分支</b>：正常心跳传全部类型，停机后的低频探测
     * 只传 {@code START_GATEWAY}。写成两个方法或让 SQL 里判 null，都会多一处
     * 「哪条查询对应哪种模式」的记忆负担。
     *
     * <p>{@code attempts < :maxAttempts} 与 {@code expiresAt > :now} 把「不该再发」的
     * 行挡在查询之外，而不是捞出来再逐个判断 —— 后者每次心跳都要白读几行。
     * 真正把它们终结的是 {@code DeviceCommandJanitor}。
     *
     * <p>按 id 升序：先下发的先送达，一个人连点了几下时顺序与他的预期一致。
     */
    @Query("select c from DeviceCommand c "
            + "where c.deviceId = :deviceId "
            + "and c.status in :statuses "
            + "and c.commandType in :types "
            + "and c.attempts < :maxAttempts "
            + "and c.nextDeliverAt <= :now "
            + "and c.expiresAt > :now "
            + "order by c.id asc")
    List<DeviceCommand> findDeliverable(@Param("deviceId") Long deviceId,
                                       @Param("statuses") Collection<DeviceCommandStatus> statuses,
                                       @Param("types") Collection<DeviceCommandType> types,
                                       @Param("maxAttempts") int maxAttempts,
                                       @Param("now") LocalDateTime now,
                                       Pageable pageable);

    /**
     * 「这台设备身上已经有同类指令在等着了」—— 签发时的去重判据。
     *
     * <p>管理员连点两下「停止网关」不该产生两条记录：第二条必然在第一条生效后才送达，
     * 而它带来的效果完全相同。返回已有的那一条，界面就能显示「已有一条相同指令在等待下发」。
     */
    Optional<DeviceCommand> findFirstByDeviceIdAndCommandTypeAndStatusInOrderByIdDesc(
            Long deviceId, DeviceCommandType commandType, Collection<DeviceCommandStatus> statuses);

    Page<DeviceCommand> findByDeviceId(Long deviceId, Pageable pageable);

    /**
     * 回执时按 id 取，**并且必须限定在这台设备名下**。
     *
     * <p>少了 {@code deviceId} 这个条件，拿着 A 设备令牌的人就能把 B 设备指令的状态
     * 改成「已执行」—— 管理端于是看到一台从未动作过的设备「已经停好了」。
     * 这不是防御性写法，是这个接口唯一的授权检查。
     */
    List<DeviceCommand> findByIdInAndDeviceId(Collection<Long> ids, Long deviceId);

    /**
     * 该被终结的指令：过了有效期，或下发次数用尽。
     *
     * <p>两个条件合成一条查询是有意的 —— 它们都是「这条指令的故事该结束了」，
     * 由同一个 {@code DeviceCommandJanitor} 每分钟扫一遍，终结时记的事件也只需在
     * reason 上区分。分成两条查询会让那个类多一次循环、多一处「哪一种先判」的顺序问题。
     */
    @Query("select c from DeviceCommand c "
            + "where c.status in :statuses "
            + "and (c.expiresAt <= :now or c.attempts >= :maxAttempts) "
            + "order by c.id asc")
    List<DeviceCommand> findExhausted(@Param("statuses") Collection<DeviceCommandStatus> statuses,
                                       @Param("maxAttempts") int maxAttempts,
                                       @Param("now") LocalDateTime now,
                                       Pageable pageable);

    /**
     * 保留策略：删掉某时刻以前的指令。
     *
     * <p>30 天，硬编码，**不进 SysConfig**。取舍：短信与事件的保留天数进配置，是因为
     * 它们回答的是「用户数据留多久」（合规问题）；而这张表里只有「谁在什么时候点了
     * 哪个按钮」和一句受控文案，没有任何用户数据，一天最多长几十行 ——
     * 给它一个配置项，只会造出一个没人会调、调了也不会有人发现的表单项。
     */
    @Modifying
    @Query("delete from DeviceCommand c where c.createdAt < :before")
    int deleteCreatedBefore(@Param("before") LocalDateTime before);

    /**
     * 删除某台设备的全部指令。管理端删设备时一并调用。
     *
     * <p>与投递记录同源：{@code device_id} 上**没有外键**，不主动删就是永久孤儿 ——
     * 它会一直挂在设备详情页上，渲染成一条认不出设备的指令。
     */
    @Modifying
    @Query("delete from DeviceCommand c where c.deviceId = :deviceId")
    int deleteByDeviceId(@Param("deviceId") Long deviceId);
}
