package com.knowledge.base.gateway.filter;

import com.knowledge.base.gateway.config.GatewayRateLimitProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 网关层全局限流过滤器
 *
 * <p>职责定位（与各业务服务里的 {@code @RateLimit} 注解互补）：</p>
 * <ul>
 *   <li>服务内的注解限流：按"业务语义"限流（如某用户每分钟最多 30 次 AI 对话），
 *       粒度细，但只能覆盖已声明的接口</li>
 *   <li>本过滤器：在流量入口按"客户端 IP"做粗粒度兜底，能挡住
 *       未登录刷接口、恶意扫描、单机压测这类请求，保护整个集群</li>
 * </ul>
 *
 * <p>算法与业务服务保持一致：Redis + Lua 滑动窗口（无固定窗口的边界突刺）。
 * 使用 reactive Redis，避免阻塞 Gateway 的事件循环线程。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Component
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

    private static final String KEY_PREFIX = "rate:limit:gw:";

    /**
     * KEYS[1] 限流key
     * ARGV[1] 当前时间戳(ms)、ARGV[2] 窗口(ms)、ARGV[3] 上限、ARGV[4] 唯一 member
     *
     * 返回值约定（标量，便于 reactive 侧解析）：
     *   > 0  放行，值为当前窗口内计数
     *   <= 0 拒绝，值为当前窗口内计数的相反数
     */
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]

            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)

            if count < limit then
                redis.call('ZADD', key, now, member)
                redis.call('PEXPIRE', key, window)
                return count + 1
            end

            redis.call('PEXPIRE', key, window)
            return -count
            """;

    private static final RedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Resource
    private GatewayRateLimitProperties properties;

    @Resource
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        GatewayRateLimitProperties.Rule rule = matchRule(path);
        if (rule == null) {
            return chain.filter(exchange);
        }

        String clientIp = resolveClientIp(request);
        String ruleKey = StringUtils.hasText(rule.getKey()) ? rule.getKey() : path;
        String limitKey = KEY_PREFIX + ruleKey + ":ip:" + clientIp;

        long now = System.currentTimeMillis();
        List<String> args = List.of(
                String.valueOf(now),
                String.valueOf(TimeUnit.SECONDS.toMillis(rule.getTime())),
                String.valueOf(rule.getCount()),
                now + "-" + UUID.randomUUID());

        return reactiveStringRedisTemplate.execute(SCRIPT, List.of(limitKey), args)
                .next()
                .flatMap(value -> {
                    if (value > 0) {
                        if (log.isDebugEnabled()) {
                            log.debug("网关限流放行：path={}, ip={}, count={}/{}", path, clientIp,
                                    value, rule.getCount());
                        }
                        return chain.filter(exchange);
                    }
                    long current = -value;
                    log.warn("网关触发限流：path={}, ip={}, 当前={}/{}次每{}秒",
                            path, clientIp, current, rule.getCount(), rule.getTime());
                    return writeLimitResponse(exchange, rule);
                })
                .switchIfEmpty(chain.filter(exchange))
                .onErrorResume(e -> {
                    if (properties.isFailOpen()) {
                        log.error("网关限流组件异常，按 fail-open 放行：path={}, error={}", path, e.getMessage(), e);
                        return chain.filter(exchange);
                    }
                    log.error("网关限流组件异常，按 fail-close 拒绝：path={}, error={}", path, e.getMessage(), e);
                    return writeLimitResponse(exchange, rule);
                });
    }

    /**
     * 命中第一条匹配的规则（顺序即优先级，配置里把严格规则放前面）
     */
    private GatewayRateLimitProperties.Rule matchRule(String path) {
        for (GatewayRateLimitProperties.Rule rule : properties.getRules()) {
            if (rule.getPaths() == null) {
                continue;
            }
            for (String pattern : rule.getPaths()) {
                if (PATH_MATCHER.match(pattern, path)) {
                    return rule;
                }
            }
        }
        if (properties.getDefaultCount() > 0) {
            GatewayRateLimitProperties.Rule fallback = new GatewayRateLimitProperties.Rule();
            fallback.setCount(properties.getDefaultCount());
            fallback.setTime(properties.getDefaultTime());
            fallback.setKey("default");
            return fallback;
        }
        return null;
    }

    /**
     * 写回统一格式的 429 响应（与业务服务的 Result 结构保持一致，前端可直接展示 message）
     */
    private Mono<Void> writeLimitResponse(ServerWebExchange exchange,
                                          GatewayRateLimitProperties.Rule rule) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", String.valueOf(rule.getTime()));

        String body = String.format(
                "{\"code\":429,\"message\":\"%s\",\"data\":null,\"timestamp\":%d}",
                escapeJson(rule.getMessage()), System.currentTimeMillis());
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 取限流用的客户端 IP
     *
     * <p>默认取 TCP 对端地址（网关即入口，不可伪造）；仅当显式开启
     * {@code kb.rate-limit.gateway.trust-forwarded-for=true}（前面还有可信的
     * Nginx/SLB）时才读取 X-Forwarded-For，并取<b>最后一段</b>——
     * 那是最近一跳可信代理写入的地址，取第一段会被客户端伪造绕过。</p>
     */
    private String resolveClientIp(ServerHttpRequest request) {
        if (properties.isTrustForwardedFor()) {
            String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
            if (StringUtils.hasText(forwarded) && !"unknown".equalsIgnoreCase(forwarded)) {
                String[] parts = forwarded.split(",");
                String last = parts[parts.length - 1].trim();
                if (StringUtils.hasText(last) && !"unknown".equalsIgnoreCase(last)) {
                    return last;
                }
            }
        }
        if (request.getRemoteAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public int getOrder() {
        // 早于路由转发执行，晚于 CORS 预检处理
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
