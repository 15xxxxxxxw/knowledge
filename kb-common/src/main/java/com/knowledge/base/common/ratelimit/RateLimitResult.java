package com.knowledge.base.common.ratelimit;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 限流判定结果
 *
 * @author 苏三
 * @since 1.0.0
 */
@Data
@AllArgsConstructor
public class RateLimitResult {

    /** 是否放行 */
    private final boolean allowed;

    /** 当前窗口内剩余可请求次数 */
    private final long remaining;

    /** 当前窗口内已请求次数 */
    private final long currentCount;

    /** 窗口大小（秒） */
    private final int windowSeconds;

    /**
     * 触发限流时：构造拒绝结果
     */
    public static RateLimitResult rejected(long currentCount, int windowSeconds) {
        return new RateLimitResult(false, 0, currentCount, windowSeconds);
    }

    /**
     * 放行
     */
    public static RateLimitResult allowed(long remaining, long currentCount, int windowSeconds) {
        return new RateLimitResult(true, Math.max(remaining, 0), currentCount, windowSeconds);
    }
}
