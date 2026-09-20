package com.smsgateway.repository;

import com.smsgateway.model.entity.NotifyRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface NotifyRouteRepository extends JpaRepository<NotifyRoute, Long> {

    /**
     * 启用的路由规则。
     *
     * <p>不排序 —— 与采集规则不同，路由是**并集**语义，匹配顺序不影响结果集
     * （命中的渠道合起来，不短路）。给它加一个 order by 只会让人误以为顺序有意义。
     */
    List<NotifyRoute> findByEnabledTrue();

    /**
     * 一条渠道都没有的规则。
     *
     * <p>删渠道把引用清掉之后会有这种规则：它匹配得上短信，却没有任何投递目标 ——
     * 一条永远不干活的规则。管理端会把它显示成「无目标」，并**自动停用**它
     * （见 {@code NotifyChannelService.delete}）。
     *
     * <p>{@code is empty} 是 JPQL 对集合关联的判空，Hibernate 会翻成
     * {@code not exists (select … from notify_route_channel …)}。
     */
    @Query("select r from NotifyRoute r where r.channelIds is empty")
    List<NotifyRoute> findRoutesWithoutChannels();

    /**
     * 删掉所有规则里对某个渠道的引用。删渠道时调用。
     *
     * <p>为什么要清：`notify_route_channel` 里留着死 id 的话，那条规则在页面上
     * **看起来还是配着的**（只多一句「（渠道已删除）」），而实际上它永远不会投递。
     * 清掉之后规则的「转发到」会变成空的，那是个看得见的、会促使人去修的破状态。
     *
     * <p>用 native query：这张表是 {@code @ElementCollection} 的关联表、不是实体，
     * JPQL 够不着它。
     *
     * @return 删掉的引用行数。一条规则引用该渠道就是一行，所以它同时也是
     *         「有多少条规则因此失去了目标」。
     */
    @Modifying
    @Transactional
    @Query(value = "delete from notify_route_channel where channel_id = :channelId", nativeQuery = true)
    int deleteChannelReferences(@Param("channelId") Long channelId);
}
