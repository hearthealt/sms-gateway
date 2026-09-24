package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 下发给设备的待发短信。
 *
 * <p>{@code key} 必须带上：设备的回执按它对应回来，而**内容相同不等于同一条**
 * （给同一个人连发两条一样的提醒是正常需求）。用自增 id 也行，但 id 是内部标识，
 * 而 key 是这条记录自己的、不可猜的串 —— 设备不该看到也不该依赖服务端的自增序列。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsOutboundPayload {

    private Long id;

    /** 幂等键，回执时原样带回。 */
    private String key;

    private String phone;

    private String content;

    /** 指定卡槽；null = 设备自己选。 */
    private Integer simSlot;
}
