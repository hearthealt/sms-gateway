package com.smsgateway.service;

import com.smsgateway.exception.EnrollTokenRequiredException;
import com.smsgateway.model.dto.EnrollTokenView;
import com.smsgateway.model.entity.DeviceEnrollToken;
import com.smsgateway.repository.DeviceEnrollTokenRepository;
import com.smsgateway.util.SecretGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * 设备接入口令：生成、轮换、启停，以及首次注册时的校验。
 *
 * <p>「快速连接」要让一台全新手机扫个码就接进来，而注册接口必须免鉴权（设备得先能
 * 注册才拿得到令牌）。在那之前没有任何东西能区分「自己人」和「碰巧知道服务器地址的人」——
 * 这个口令就是补上这一段。
 *
 * <p>刻意**不做缓存**（对比 {@code ApiKeyService} 的 Redis 缓存）：那个缓存是因为每次
 * 外部 API 调用都要校验一次密钥，而这里每个设备一辈子只用一次（首次注册）。为一个
 * 每设备一次的操作引入缓存与失效逻辑，只会在轮换口令时多出一个「缓存没清干净」的故障面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceEnrollTokenService {

    private final DeviceEnrollTokenRepository repository;

    /** 当前口令行，尚未生成时返回 empty。 */
    public Optional<DeviceEnrollToken> current() {
        return repository.findById(DeviceEnrollToken.SINGLETON_ID);
    }

    /**
     * 给管理后台的状态视图。
     *
     * <p>未生成时返回的不是 null，而是一个 **token 为 null 的空视图** ——
     * 判断「有没有生成过」要看 {@code token} 字段，别看这个对象在不在
     * （控制台原先就是拿对象当判据，于是把「没生成过」显示成了「已停用」，
     * 点「启用」后端只能回 400）。
     */
    public EnrollTokenView view() {
        return current().map(this::toView).orElseGet(EnrollTokenView::new);
    }

    /**
     * 生成一张新口令。已有口令时即轮换，旧口令立即失效。
     *
     * <p><b>启用状态沿用上一次</b>：第一次生成必然是启用（否则这张码没有用处），
     * 轮换则保持原状 —— 刚停用过的人，不该因为「换一张码」而被动把它改回启用。
     *
     * <p>早先这里是无条件 {@code setEnabled(true)}，理由写的是「怕管理员看到一张新码、
     * 扫了却被拒」。这个理由其实不成立：**停用期间注册接口本来就是放行的**（见
     * {@link #verify}），而二维码里照样带着口令，所以沿用停用状态也扫得进去。
     * 反倒是原来那种做法会在管理员刚做了「停用」这个决定之后，悄悄把安全边界改回去。
     * 控制台那边也会把停用状态连同「立即启用」摆在二维码上方，不会让人对着新码发懵。
     */
    @Transactional
    public EnrollTokenView rotate() {
        DeviceEnrollToken existing = current().orElse(null);
        DeviceEnrollToken entity = existing != null ? existing : new DeviceEnrollToken();
        if (existing == null) {
            // 单行表的固定主键，不用自增（理由见实体注释）
            entity.setId(DeviceEnrollToken.SINGLETON_ID);
        }

        // 先读旧状态再改口令：下面这两行都会写同一个实体
        boolean wasEnabled = existing == null || existing.isEnabled();
        entity.setToken(SecretGenerator.randomSecret());
        entity.setEnabled(wasEnabled);
        repository.save(entity);

        log.warn("设备接入口令已生成/轮换，旧口令立即失效；启用状态={}", wasEnabled ? "启用" : "仍停用");
        return toView(entity);
    }

    /**
     * 启用 / 停用准入校验。停用**不删除**口令，重新启用不必换一张。
     *
     * @throws IllegalArgumentException 尚未生成过口令 —— 此时没有可停用的东西，
     *         静默返回成功会让管理员以为「已经关掉了准入」，而实际从来就没开过。
     */
    @Transactional
    public EnrollTokenView setEnabled(boolean enabled) {
        DeviceEnrollToken entity = current().orElseThrow(
                () -> new IllegalArgumentException("尚未生成接入口令，无法启用或停用"));

        entity.setEnabled(enabled);
        repository.save(entity);

        log.warn("设备接入口令已{}", enabled ? "启用" : "停用");
        return toView(entity);
    }

    /**
     * 校验首次注册携带的接入口令。不通过时抛 {@link EnrollTokenRequiredException}（403）。
     *
     * <p><b>未生成口令、或口令被停用时一律放行。</b>这是刻意的向后兼容：老部署灌完
     * 新脚本后，新设备不会突然接不进来；要收紧得由管理员显式生成一张口令。
     *
     * <p>调用点只有一处 —— {@code DeviceService.doRegister} 的新设备分支。
     * **已存在设备的重新注册不要走这里**：那条路认的是设备身份（enrollSecret），
     * 若也要求口令，一开启准入所有老设备重装后就全部失联了。
     */
    public void verify(String presented) {
        DeviceEnrollToken current = current().orElse(null);
        if (current == null || !current.isEnabled()) {
            return;
        }

        if (presented == null || presented.isBlank()) {
            throw new EnrollTokenRequiredException(
                    "本服务器已启用接入口令，注册请求没有携带它。"
                            + "请用管理后台「快速连接」页里的二维码扫码接入；"
                            + "若你确实扫了码，说明这台设备的 App 版本过旧、读不出二维码里的口令字段，升级后再试。");
        }

        // 定长比较，不用 equals：这是本服务器唯一的准入凭证，而 String.equals 在前几个
        // 字符对不上时就提前返回 —— 逐位试错的时间差可被测量，等于把口令一个字符一个
        // 字符地问出来。签名、令牌这类值的比较一律走 MessageDigest.isEqual。
        boolean match = MessageDigest.isEqual(
                current.getToken().getBytes(StandardCharsets.UTF_8),
                presented.trim().getBytes(StandardCharsets.UTF_8));

        if (!match) {
            throw new EnrollTokenRequiredException(
                    "接入口令不正确或已失效。口令可能已被管理员轮换，"
                            + "请重新扫一次管理后台「快速连接」里的二维码。");
        }
    }

    private EnrollTokenView toView(DeviceEnrollToken entity) {
        return new EnrollTokenView(entity.getToken(), entity.isEnabled(), entity.getUpdatedAt());
    }
}
