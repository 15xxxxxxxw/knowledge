package com.knowledge.base.common.annotation;

import java.lang.annotation.*;

/**
 * 限流注解
 *
 * <p>可标注在方法或类上（类上对所有方法生效，方法上的配置优先）。
 * 由 {@code RateLimitAspect} 基于 Redis + Lua 滑动窗口实现，
 * 支持按 IP / 用户 / 全局三个维度限流。</p>
 *
 * <p>示例：</p>
 * <pre>
 * // 登录接口：同一 IP 每分钟最多 10 次
 * &#64;RateLimit(key = "auth:login", time = 60, count = 10, type = RateLimitType.IP,
 *            message = "登录过于频繁，请 1 分钟后再试")
 *
 * // AI 对话：同一用户每分钟最多 30 次
 * &#64;RateLimit(key = "ai:chat", time = 60, count = 30, type = RateLimitType.USER)
 * </pre>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 限流key（业务前缀）。留空时自动使用「类名.方法名」
     */
    String key() default "";

    /**
     * 时间窗口（秒），默认 60 秒
     */
    int time() default 60;

    /**
     * 时间窗口内最大请求次数，默认 100 次
     */
    int count() default 100;

    /**
     * 限流提示信息
     */
    String message() default "操作过于频繁，请稍后再试";

    /**
     * 限流维度，默认自动识别（登录用户按用户，未登录按 IP）
     */
    RateLimitType type() default RateLimitType.AUTO;
}
