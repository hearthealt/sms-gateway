package com.smsgateway.repository;

import com.smsgateway.model.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /** 外部调用方密钥校验。 */
    Optional<ApiKey> findByApiKey(String apiKey);

    /** 管理后台密钥列表：新签发的排在前面。 */
    List<ApiKey> findAllByOrderByCreatedAtDesc();
}
