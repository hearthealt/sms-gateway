package com.smsgateway.repository;

import com.smsgateway.model.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    /**
     * 全部启用的规则。
     *
     * <p>与 {@code NotifyRouteRepository.findByEnabledTrue} 同一个取舍：规则数量是个位数，
     * 而匹配要走内存里的判断（类型 + 设备），所以整批捞出来在内存里筛，
     * 比为一个「可选条件」拼一条 JPQL 更清楚。
     */
    List<AlertRule> findByEnabledTrue();
}
