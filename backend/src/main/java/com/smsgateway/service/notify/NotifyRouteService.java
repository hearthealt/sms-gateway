package com.smsgateway.service.notify;

import com.smsgateway.model.dto.NotifyRouteRequest;
import com.smsgateway.model.dto.NotifyRouteView;
import com.smsgateway.model.entity.NotifyChannel;
import com.smsgateway.model.entity.NotifyRoute;
import com.smsgateway.repository.NotifyChannelRepository;
import com.smsgateway.repository.NotifyRouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import com.smsgateway.service.AdminEventBroadcaster;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyRouteService {

    private final NotifyRouteRepository routeRepository;
    private final AdminEventBroadcaster adminEvents;

    /**
     * 转发规则页的「该刷新了」信号。
     *
     * <p>这一页**没有服务端自变源**（变化只来自管理员自己，而本地操作已是即时更新的），
     * 所以推它只覆盖「另一个管理员改了」。说清楚这一点，别把它当成实时性承诺。
     *
     * <p>例外是渠道被删时那条连带路径：那时规则会被**自动停用**，
     * 那不是任何人的操作，见 {@code NotifyChannelService.delete}。
     */
    private void notifyChanged() {
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_ROUTES, Map.of());
    }
    private final NotifyChannelRepository channelRepository;

    public List<NotifyRouteView> list() {
        Map<Long, NotifyChannel> channels = channelMap();
        return routeRepository.findAll().stream()
                .map(route -> toView(route, channels))
                .toList();
    }

    @Transactional
    public NotifyRouteView create(NotifyRouteRequest request) {
        NotifyRoute route = new NotifyRoute();
        applyFields(route, request, true);
        routeRepository.save(route);
        log.info("新建转发规则：{} → {} 个渠道", route.getRouteName(), route.getChannelIds().size());
        notifyChanged();
        return toView(route, channelMap());
    }

    @Transactional
    public NotifyRouteView update(Long id, NotifyRouteRequest request) {
        NotifyRoute route = require(id);
        applyFields(route, request, false);
        routeRepository.save(route);
        notifyChanged();
        return toView(route, channelMap());
    }

    @Transactional
    public void delete(Long id) {
        routeRepository.delete(require(id));
        notifyChanged();
    }

    @Transactional
    public NotifyRouteView setEnabled(Long id, boolean enabled) {
        NotifyRoute route = require(id);

        // 一条渠道都没有的规则**不允许启用**。
        //
        // 删渠道时会把因此变成「无目标」的规则自动停用（见 NotifyChannelService.delete），
        // 但那个状态是可以被手动改回来的 —— 点一下开关就行。改回来之后这条规则会
        // 「开着」，匹配得上短信，却没有任何投递目标：它在列表上看起来在工作，
        // 实际一条都不发。这正是自动停用要避免的那种误导。
        if (enabled && route.getChannelIds().isEmpty()) {
            throw new IllegalArgumentException(
                    "这条规则没有任何投递目标，无法启用。请先在「转发渠道」页确认渠道还在，"
                            + "然后编辑这条规则重新选择目标。");
        }

        route.setEnabled(enabled);
        routeRepository.save(route);
        notifyChanged();
        return toView(route, channelMap());
    }

    private void applyFields(NotifyRoute route, NotifyRouteRequest request, boolean isCreate) {
        if (request.getRouteName() != null && !request.getRouteName().isBlank()) {
            route.setRouteName(request.getRouteName().trim());
        } else if (isCreate) {
            throw new IllegalArgumentException("规则名不能为空");
        }

        if (request.getSenderPattern() != null) {
            route.setSenderPattern(blankToNull(request.getSenderPattern()));
        }
        if (request.getKeywordPattern() != null) {
            route.setKeywordPattern(blankToNull(request.getKeywordPattern()));
        }
        if (request.getPhonePattern() != null) {
            route.setPhonePattern(blankToNull(request.getPhonePattern()));
        }
        if (request.getDeviceId() != null) {
            route.setDeviceId(blankToNull(request.getDeviceId()));
        }

        if (request.getMatchType() != null && !request.getMatchType().isBlank()) {
            route.setMatchType(request.getMatchType().trim().toUpperCase());
        } else if (isCreate) {
            route.setMatchType("LIKE");
        }

        if (request.getChannelIds() != null) {
            // 去重：同一个渠道提交两次会在 uk 上撞唯一约束
            LinkedHashSet<Long> ids = new LinkedHashSet<>(request.getChannelIds());
            if (ids.isEmpty()) {
                throw new IllegalArgumentException(
                        "一条规则至少要配一个渠道 —— 没有目标的规则永远不会触发，"
                                + "而它在列表上看起来和正常规则一模一样。");
            }
            // 渠道得真实存在，否则规则建好了却永远发不出去，而现场要翻到投递记录才看得出来
            for (Long channelId : ids) {
                if (!channelRepository.existsById(channelId)) {
                    throw new IllegalArgumentException("渠道不存在: " + channelId);
                }
            }
            route.setChannelIds(ids);
        } else if (isCreate) {
            throw new IllegalArgumentException("一条规则至少要配一个渠道");
        }

        if (request.getEnabled() != null) {
            route.setEnabled(request.getEnabled());
        }
    }

    private NotifyRoute require(Long id) {
        return routeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("转发规则不存在: " + id));
    }

    private Map<Long, NotifyChannel> channelMap() {
        return channelRepository.findAll().stream()
                .collect(Collectors.toMap(NotifyChannel::getId, Function.identity(), (a, b) -> a));
    }

    private NotifyRouteView toView(NotifyRoute route, Map<Long, NotifyChannel> channels) {
        List<Long> ids = new ArrayList<>(route.getChannelIds());
        // 与 ids 一一对应：查不到的渠道（已被删）给一个占位名，而不是跳过 ——
        // 跳过会让前端按下标对不上，显示成「运维群」的其实是另一个渠道
        List<String> names = ids.stream()
                .map(id -> {
                    NotifyChannel channel = channels.get(id);
                    if (channel == null) {
                        return "（渠道已删除）";
                    }
                    // **停用的渠道也标出来**：这条规则配着它，但它实际不会投递。
                    // 不标的话规则页看起来一切正常，而短信就是发不出去 —— 现场
                    // 要翻到投递记录页才看得出是渠道被停了。
                    return channel.isEnabled() ? channel.getName() : channel.getName() + "（已停用）";
                })
                .toList();

        NotifyRouteView view = new NotifyRouteView();
        view.setId(route.getId());
        view.setRouteName(route.getRouteName());
        view.setSenderPattern(route.getSenderPattern());
        view.setKeywordPattern(route.getKeywordPattern());
        view.setMatchType(route.getMatchType());
        view.setDeviceId(route.getDeviceId());
        view.setPhonePattern(route.getPhonePattern());
        view.setEnabled(route.isEnabled());
        view.setChannelIds(ids);
        view.setTargetChannelNames(names);
        view.setCreatedAt(route.getCreatedAt());
        view.setUpdatedAt(route.getUpdatedAt());
        return view;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
