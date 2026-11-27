package com.knowledge.base.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关层限流配置
 *
 * <pre>
 * kb:
 *   rate-limit:
 *     gateway:
 *       enabled: true
 *       fail-open: true
 *       rules:
 *         - paths: [ /api/auth/login ]
 *           count: 20
 *           time: 60
 *           message: 登录尝试过于频繁，请稍后再试
 * </pre>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "kb.rate-limit.gateway")
public class GatewayRateLimitProperties {

    /** 网关限流总开关 */
    private boolean enabled = true;

    /** Redis 不可用时是否放行（true=保可用性） */
    private boolean failOpen = true;

    /**
     * 是否信任 X-Forwarded-For 头。
     *
     * <p>默认 false：网关自身就是流量入口，直接取 TCP 连接的对端地址，
     * 这样客户端无法通过伪造 X-Forwarded-For 轮换 IP 来绕过限流。
     * 仅当网关前面还有一层可控的负载均衡/Nginx 时才应开启。</p>
     */
    private boolean trustForwardedFor = false;

    /** 未命中任何规则时的兜底限额（0 表示不限制） */
    private int defaultCount = 0;

    /** 兜底限额的窗口（秒） */
    private int defaultTime = 60;

    /** 限流规则 */
    private List<Rule> rules = new ArrayList<>();

    @Data
    public static class Rule {

        /** 需要限流的路径（Ant 风格，可配置多条） */
        private List<String> paths = new ArrayList<>();

        /** 窗口内最大请求次数 */
        private int count = 100;

        /** 窗口大小（秒） */
        private int time = 60;

        /** 提示信息 */
        private String message = "请求过于频繁，请稍后再试";

        /** 规则名（用于拼 Redis key，留空则用路径） */
        private String key;
    }
}
