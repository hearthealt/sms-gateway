package com.smsgateway.repository;

import com.smsgateway.model.entity.SmsCollectRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CollectRuleRepository extends JpaRepository<SmsCollectRule, Long> {

    List<SmsCollectRule> findByEnabledTrueOrderByPriorityDesc();

    /** 管理后台规则列表：不区分启用状态，按优先级倒序。 */
    List<SmsCollectRule> findAllByOrderByPriorityDesc();
}
