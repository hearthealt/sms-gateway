package com.smsgateway.repository;

import com.smsgateway.model.entity.NotifyChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotifyChannelRepository extends JpaRepository<NotifyChannel, Long> {

    /**
     * 可投递的渠道。**停用的渠道不进这个列表**。
     *
     * <p>注意投递记录早已按渠道建好（outbox 是在事务里写的），所以停用发生在
     * 投递之前时，那些记录会停在 PENDING —— 重新启用后会被继续投出去。
     * 这是刻意的：停用是「暂时别发」，不是「丢掉」。
     */
    List<NotifyChannel> findByEnabledTrue();
}
