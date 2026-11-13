package com.knowledge.base.common.ratelimit;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis + Lua 的滑动窗口限流器
 *
 * <p>为什么用滑动窗口而不是固定窗口：固定窗口（INCR + EXPIRE）在窗口边界会出现
 * "两倍突发"（例如 60 秒内限制 100 次，第 59 秒打 100 次、第 61 秒再打 100 次，
 * 相当于 2 秒内 200 次）。滑动窗口按"最近 N 秒"精确计数，没有边界突刺。</p>
 *
 * <p>整段计数逻辑放在 Lua 脚本里执行，保证「清理过期 + 计数 + 累加」三步的原子性，
 * 避免并发下的计数偏高/偏低。</p>
 *
 * <p><b>命名注意：</b>类名刻意不叫 {@code RedisRateLimiter}——Spring Cloud Gateway
 * 内置了一个同名类（{@code org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter}），
 * 若本类与之同名，网关扫描 kb-common 时会注册出同名 bean {@code redisRateLimiter}
 * 并导致启动失败（{@code BeanDefinitionOverrideException}）。</p>
 *
 * <p>本限流器服务于 Servlet 环境下的 {@code @RateLimit} 注解切面；Gateway 是 WebFlux 应用，
 * 使用自己的 {@code GatewayRateLimitFilter} + reactive Redis，因此这里通过
 * {@link ConditionalOnWebApplication} 限定只在 Servlet 应用注册。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Component("kbSlidingWindowRateLimiter")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SlidingWindowRateLimiter implements RateLimiter {

    /**
     * KEYS[1] 限流key
     * ARGV[1] 当前时间戳(ms)
     * ARGV[2] 窗口大小(ms)
     * ARGV[3] 窗口内最大次数
     * ARGV[4] 本次请求的唯一标识（ZSET member）
     *
     * 返回 {是否放行, 剩余次数, 当前次数}
     */
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]

            -- 移除窗口外的历史记录
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)

            if count < limit then
                redis.call('ZADD', key, now, member)
                redis.call('PEXPIRE', key, window)
                return {1, limit - count - 1, count + 1}
            end

            -- 已超限：仅续期，不再写入，避免无效数据撑大 ZSET
            redis.call('PEXPIRE', key, window)
            return {0, 0, count}
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            new DefaultRedisScript<>(LUA_SCRIPT, List.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) {
            return RateLimitResult.allowed(Long.MAX_VALUE, 0, windowSeconds);
        }

        long now = System.currentTimeMillis();
        long windowMillis = TimeUnit.SECONDS.toMillis(windowSeconds);
        // ZSET member 需要唯一，否则同毫秒内的多个请求会互相覆盖导致漏计
        String member = now + "-" + UUID.randomUUID();

        List<?> result = stringRedisTemplate.execute(
                SCRIPT,
                List.of(key),
                String.valueOf(now),
                String.valueOf(windowMillis),
                String.valueOf(limit),
                member);

        if (result == null || result.size() < 3) {
            // 脚本返回异常时按放行处理，避免限流组件本身拖垮业务（失败开放策略由调用方决定）
            log.warn("限流脚本返回异常，默认放行：key={}, result={}", key, result);
            return RateLimitResult.allowed(limit, 0, windowSeconds);
        }

        boolean allowed = toLong(result.get(0)) == 1L;
        long remaining = toLong(result.get(1));
        long current = toLong(result.get(2));

        if (allowed) {
            return RateLimitResult.allowed(remaining, current, windowSeconds);
        }
        return RateLimitResult.rejected(current, windowSeconds);
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
