package com.smsgateway.service;

import com.smsgateway.model.entity.SmsCollectRule;
import com.smsgateway.repository.CollectRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectRuleEngineTest {

    private static final String COLLECT = "collect";
    private static final String IGNORE = "ignore";

    @Mock
    private CollectRuleRepository collectRuleRepository;

    @InjectMocks
    private CollectRuleEngine engine;

    private static SmsCollectRule rule(String name, String senderPattern, String matchType,
                                       String keyword, String action, int priority) {
        SmsCollectRule r = new SmsCollectRule();
        r.setRuleName(name);
        r.setSenderPattern(senderPattern);
        r.setMatchType(matchType);
        r.setKeywordPattern(keyword);
        r.setAction(action);
        r.setPriority(priority);
        r.setEnabled(true);
        return r;
    }

    private void givenRules(SmsCollectRule... rules) {
        // 仓储层按 priority 降序返回，引擎依赖这个顺序做「首条命中即定论」
        when(collectRuleRepository.findByEnabledTrueOrderByPriorityDesc())
                .thenReturn(List.of(rules));
    }

    @Test
    @DisplayName("REGEX 是包含匹配，不是整体匹配")
    void regexMatchesSubstring() {
        // 这是整个引擎最容易写错的地方：若用 matches() 而非 find()，这条规则永远不命中
        givenRules(rule("验证码短信", ".*", "REGEX", "验证码", COLLECT, 100));

        CollectRuleEngine.Decision decision = engine.decide("10690300", "您的验证码是123456，5分钟内有效");

        assertThat(decision.ignored()).isFalse();
        assertThat(decision.matchedRule()).isEqualTo("验证码短信");
    }

    @Test
    @DisplayName("LIKE 支持 % 通配，且字面量元字符被转义")
    void likeWildcard() {
        givenRules(rule("106号段", "106%", "LIKE", "验证码", COLLECT, 100));

        assertThat(engine.decide("10690300", "验证码是123456").ignored()).isFalse();
        assertThat(engine.decide("95555", "验证码是123456").matchedRule()).isNull();
    }

    @Test
    @DisplayName("LIKE 里的正则元字符按字面量处理")
    void likeEscapesRegexMetachars() {
        assertThat(CollectRuleEngine.likeToRegex("106%")).isEqualTo("106.*");
        assertThat(CollectRuleEngine.likeToRegex("a.b%")).isEqualTo("a\\.b.*");
        assertThat(CollectRuleEngine.likeToRegex("1_2")).isEqualTo("1.2");
    }

    @Test
    @DisplayName("关键词为空表示不约束正文，只看发送方")
    void blankKeywordOnlyConstrainsSender() {
        givenRules(rule("美团", "美团", "EXACT", "", COLLECT, 50));

        assertThat(engine.decide("美团", "随便什么内容").ignored()).isFalse();
    }

    @Test
    @DisplayName("发送方与关键词是 AND 关系，发送方命中但关键词不命中则规则不生效")
    void senderAndKeywordAreConjunctive() {
        givenRules(rule("验证码短信", "%", "LIKE", "验证码", COLLECT, 100));

        assertThat(engine.decide("10690300", "您的验证码是123456").ignored()).isFalse();

        // 正文不含「验证码」，规则不该命中
        CollectRuleEngine.Decision decision = engine.decide("10690300", "您的快递已发出");
        assertThat(decision.matchedRule()).isNull();
    }

    @Test
    @DisplayName("priority 降序，首条命中即定论：高优先级 collect 压过低优先级 ignore")
    void firstMatchWins() {
        givenRules(
                rule("验证码短信", "%", "LIKE", "验证码", COLLECT, 100),
                rule("营销类忽略", ".*", "REGEX", "营销|广告|退订", IGNORE, 10));

        // 正文同时含「验证码」和「营销」，高优先级的 collect 先命中
        assertThat(engine.decide("10690300", "验证码123456，营销活动进行中").ignored()).isFalse();
    }

    @Test
    @DisplayName("命中 ignore 规则时返回 ignored=true 并带上规则名")
    void ignoreRuleReported() {
        givenRules(rule("营销类忽略", ".*", "REGEX", "营销|广告|退订", IGNORE, 10));

        CollectRuleEngine.Decision decision = engine.decide("10690300", "退订回T");

        assertThat(decision.ignored()).isTrue();
        assertThat(decision.matchedRule()).isEqualTo("营销类忽略");
    }

    @Test
    @DisplayName("非法正则不抛异常，按不匹配处理（管理员可能手填坏正则，不能让接收接口 500）")
    void invalidRegexDoesNotThrow() {
        givenRules(rule("坏规则", ".*", "REGEX", "[", IGNORE, 100));

        assertThatCode(() -> engine.decide("10690300", "任意内容")).doesNotThrowAnyException();
        assertThat(engine.decide("10690300", "任意内容").ignored()).isFalse();
    }

    @Test
    @DisplayName("未知 matchType 不抛异常，按不匹配处理")
    void unknownMatchTypeDoesNotThrow() {
        givenRules(rule("怪规则", "x", "FUZZY", "y", IGNORE, 100));

        assertThat(engine.decide("x", "y").ignored()).isFalse();
    }

    @Test
    @DisplayName("无任何规则命中时默认采集（保底不丢验证码）")
    void defaultsToCollect() {
        when(collectRuleRepository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of());

        CollectRuleEngine.Decision decision = engine.decide("10690300", "您的验证码是123456");

        assertThat(decision.ignored()).isFalse();
        assertThat(decision.matchedRule()).isNull();
    }

    @Test
    @DisplayName("种子规则中的「验证码短信」在 LIKE 下用 % 表示任意发送方，能真正命中")
    void seededLikeAnySenderRuleWorks() {
        givenRules(rule("验证码短信", "%", "LIKE", "验证码", COLLECT, 100));

        assertThat(engine.decide("任何发送方", "您的验证码是123456").matchedRule()).isEqualTo("验证码短信");
    }

    @Test
    @DisplayName("发送方为空或 null 时不命中，不抛 NPE")
    void nullSenderHandled() {
        givenRules(rule("验证码短信", "%", "LIKE", "验证码", COLLECT, 100));

        assertThat(engine.decide(null, "验证码123456").ignored()).isFalse();
        assertThat(engine.decide("", "验证码123456").ignored()).isFalse();
    }
}
