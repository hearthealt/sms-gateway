package com.smsgateway.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 设备注册请求。
 *
 * <p>字段名统一用 {@code phone}，与本项目其余契约（{@code SmsReceiveRequest}、
 * {@code DeviceView}、{@code SmsView}、{@code SmsWaitResponse}、{@code ClientSmsView}）
 * 保持一致。这里原先叫 {@code phoneNumber} —— 那是把实体列名泄漏到了 API 层，
 * 再靠 {@code @JsonAlias("phone")} 去兼容客户端实际发的 {@code phone} 把差异抹掉。
 * 别名方向反过来保留一个，以防还有别处的调用方在用旧名字。
 *
 * <p>长度上限对齐建表脚本的列宽：超长若不在这一层挡住，会一路走到 INSERT 才炸，
 * 被兜成 500，而调用方看到的是「服务器内部错误」，无从知道是自己字段太长。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegisterRequest {

    @NotBlank(message = "deviceId cannot be empty")
    @Size(max = 128, message = "deviceId 超长（上限 128）")
    private String deviceId;

    @Size(max = 255, message = "deviceName 超长（上限 255）")
    private String deviceName;

    /** Android 客户端发的就是这个名字，见类注释。 */
    @JsonAlias("phoneNumber")
    @Size(max = 32, message = "phone 超长（上限 32）")
    private String phone;

    @Size(max = 50, message = "platform 超长（上限 50）")
    private String platform;

    @Size(max = 50, message = "appVersion 超长（上限 50）")
    private String appVersion;

    /**
     * 重注册密钥，由**设备自己生成**并保管，服务端只存它的 SHA-256。
     *
     * <p>首次注册时用它建立身份；此后该 deviceId 只要已存在，就必须带上它才返还令牌 ——
     * 否则任何知道设备号的人都能把自己冒充成这台设备。重装丢失密钥的设备，
     * 由管理员在控制台签发恢复码取回。
     *
     * <p>由客户端生成而不是服务端下发：这样密钥不经过网络传输的第二条路径，
     * 也不需要服务端保存明文。
     */
    // 刻意不加 @NotBlank：缺字段时会被它挡成 400「enrollSecret cannot be empty」，
    // 而现场真正需要看到的是一句能指路的话。改由 DeviceService 判定并抛 403，
    // 消息里会写清「升级 App」还是「去控制台签恢复码」。
    @Size(max = 128, message = "enrollSecret 超长（上限 128）")
    private String enrollSecret;

    /**
     * 服务器接入口令，随管理后台「快速连接」的二维码下发。
     *
     * <p>与上面的 {@code enrollSecret} 是**两回事**，不要混淆：那个证明「我是这台设备」
     * （设备自己生成，只在 deviceId 已存在时校验）；这个证明「我被允许接入本服务器」
     * （管理员生成，只在设备**不存在**、也就是首次注册时校验）。
     *
     * <p>服务端未启用接入口令时，这个字段留空即可 —— 校验会放行（见
     * {@code DeviceEnrollTokenService.verify}）。
     */
    // 同样刻意不加 @NotBlank：缺字段要由 Service 判定并抛 403，
    // 消息里才能写清「去管理后台重新扫码」这条能指路的话。
    @Size(max = 64, message = "enrollToken 超长（上限 64）")
    private String enrollToken;
}
