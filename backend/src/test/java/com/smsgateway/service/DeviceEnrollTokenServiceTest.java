package com.smsgateway.service;

import com.smsgateway.exception.EnrollTokenRequiredException;
import com.smsgateway.model.dto.EnrollTokenView;
import com.smsgateway.model.entity.DeviceEnrollToken;
import com.smsgateway.repository.DeviceEnrollTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接入口令的生成与校验。
 *
 * <p>这组测试守的是两条方向相反的边界，缺任何一条都会出事故：
 *
 * <p>**收紧的那一侧** —— 口令启用了就必须真的挡住：不带、带错、带了个被轮换掉的旧值，
 * 都得是 403（{@link EnrollTokenRequiredException}），否则这个功能等于没做。
 *
 * <p>**放松的那一侧** —— 没生成过口令、口令被停用时必须一律放行。这条不是「顺带」，
 * 而是向后兼容的全部依据：老部署灌完新脚本后，注册接口必须和以前一模一样，
 * 否则现场会在升级那一刻涌进一批「新设备怎么都注册不上」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceEnrollTokenServiceTest {

    private static final String TOKEN = "c2VjcmV0LXRva2VuLXZhbHVl";

    @Mock
    private DeviceEnrollTokenRepository repository;

    @InjectMocks
    private DeviceEnrollTokenService service;

    private DeviceEnrollToken stored(String token, boolean enabled) {
        DeviceEnrollToken entity = new DeviceEnrollToken();
        entity.setId(DeviceEnrollToken.SINGLETON_ID);
        entity.setToken(token);
        entity.setEnabled(enabled);
        return entity;
    }

    private void storedToken(String token, boolean enabled) {
        when(repository.findById(DeviceEnrollToken.SINGLETON_ID))
                .thenReturn(Optional.of(stored(token, enabled)));
    }

    // ---------------------------------------------------------------- 放松的一侧

    @Test
    @DisplayName("从未生成过口令时放行 —— 老部署升级后注册接口行为不变")
    void allowsWhenNoTokenEverGenerated() {
        when(repository.findById(DeviceEnrollToken.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> service.verify(null)).doesNotThrowAnyException();
        assertThatCode(() -> service.verify("随便什么")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("口令被停用时放行，哪怕库里还留着它")
    void allowsWhenDisabled() {
        storedToken(TOKEN, false);

        assertThatCode(() -> service.verify(null)).doesNotThrowAnyException();
        assertThatCode(() -> service.verify("完全不搭边")).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 收紧的一侧

    @Test
    @DisplayName("口令已启用却没带时拒绝，并指向控制台的二维码")
    void rejectsWhenMissing() {
        storedToken(TOKEN, true);

        assertThatThrownBy(() -> service.verify(null))
                .isInstanceOf(EnrollTokenRequiredException.class)
                .hasMessageContaining("快速连接");

        assertThatThrownBy(() -> service.verify("   "))
                .isInstanceOf(EnrollTokenRequiredException.class);
    }

    @Test
    @DisplayName("口令已启用但带错时拒绝，并提示可能已被轮换")
    void rejectsWhenWrong() {
        storedToken(TOKEN, true);

        assertThatThrownBy(() -> service.verify("wrong-token"))
                .isInstanceOf(EnrollTokenRequiredException.class)
                .hasMessageContaining("轮换");
    }

    @Test
    @DisplayName("带对时放行；首尾空白不影响判定")
    void allowsWhenCorrect() {
        storedToken(TOKEN, true);

        assertThatCode(() -> service.verify(TOKEN)).doesNotThrowAnyException();
        // 二维码是拍照扫出来的，用户手抄时也常带上空白，这里不该因此判错
        assertThatCode(() -> service.verify("  " + TOKEN + "\n")).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 生成与轮换

    @Test
    @DisplayName("首次生成：写入固定主键那一行，口令够长且默认启用")
    void rotateCreatesSingletonRow() {
        when(repository.findById(DeviceEnrollToken.SINGLETON_ID)).thenReturn(Optional.empty());

        EnrollTokenView view = service.rotate();

        ArgumentCaptor<DeviceEnrollToken> saved = ArgumentCaptor.forClass(DeviceEnrollToken.class);
        verify(repository).save(saved.capture());

        // 必须是固定主键：自增的话第二次生成会插出第二行，口令就有了两个来源
        assertThat(saved.getValue().getId()).isEqualTo(DeviceEnrollToken.SINGLETON_ID);
        // 32 字节 → base64url 无填充 = 43 字符
        assertThat(view.getToken()).hasSize(43);
        assertThat(view.getToken()).matches("[A-Za-z0-9_-]+");
        assertThat(view.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("再次生成即轮换：仍是同一行，口令换掉，且**沿用**原来的停用状态")
    void rotateReplacesExistingRow() {
        DeviceEnrollToken existing = stored(TOKEN, false);
        when(repository.findById(DeviceEnrollToken.SINGLETON_ID)).thenReturn(Optional.of(existing));

        EnrollTokenView view = service.rotate();

        assertThat(view.getToken()).isNotEqualTo(TOKEN);
        // 停用状态要保持：换一张码不该顺手把管理员刚做的「停用」改回启用。
        // （停用期间注册本来就是放行的，所以新码不会「扫了被拒」—— 见 rotate 的注释）
        assertThat(view.isEnabled()).isFalse();
        // 没有新开一行
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("轮换不改变「已启用」状态：换一张码不会把准入关掉")
    void rotateKeepsEnabledState() {
        DeviceEnrollToken existing = stored(TOKEN, true);
        when(repository.findById(DeviceEnrollToken.SINGLETON_ID)).thenReturn(Optional.of(existing));

        EnrollTokenView view = service.rotate();

        assertThat(view.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("轮换后旧口令立刻失效，新口令可用")
    void rotateInvalidatesOldToken() {
        DeviceEnrollToken existing = stored(TOKEN, true);
        when(repository.findById(DeviceEnrollToken.SINGLETON_ID)).thenReturn(Optional.of(existing));

        String rotated = service.rotate().getToken();

        assertThat(rotated).isNotEqualTo(TOKEN);
        assertThatThrownBy(() -> service.verify(TOKEN))
                .isInstanceOf(EnrollTokenRequiredException.class);
        assertThatCode(() -> service.verify(rotated)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("从未生成过口令时不允许启停 —— 静默成功会让人以为已经关掉了准入")
    void setEnabledWithoutTokenIsRejected() {
        when(repository.findById(DeviceEnrollToken.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setEnabled(false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未生成");
    }

    @Test
    @DisplayName("停用不删除口令，重新启用后旧口令仍然有效")
    void disablingKeepsToken() {
        DeviceEnrollToken existing = stored(TOKEN, true);
        when(repository.findById(DeviceEnrollToken.SINGLETON_ID)).thenReturn(Optional.of(existing));

        EnrollTokenView view = service.setEnabled(false);

        assertThat(view.isEnabled()).isFalse();
        assertThat(view.getToken()).isEqualTo(TOKEN);
    }
}
