package com.knowledge.base.common.ratelimit;

/**
 * 限流器抽象
 *
 * @author 苏三
 * @since 1.0.0
 */
public interface RateLimiter {

    /**
     * 尝试获取一次配额
     *
     * @param key           限流键（含业务前缀与维度）
     * @param limit         窗口内最大次数
     * @param windowSeconds 窗口大小（秒）
     * @return 判定结果
     */
    RateLimitResult tryAcquire(String key, int limit, int windowSeconds);
}
