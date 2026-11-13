package com.knowledge.base.common.annotation;

/**
 * 限流维度
 *
 * @author 苏三
 * @since 1.0.0
 */
public enum RateLimitType {

    /**
     * 自动识别：已登录按用户维度，未登录按 IP 维度（默认，最实用）
     */
    AUTO,

    /**
     * 按客户端 IP 限流（适合登录、注册、验证码等未登录接口）
     */
    IP,

    /**
     * 按登录用户限流（适合 AI 对话、上传等已登录接口）
     */
    USER,

    /**
     * 全局共享一个配额（适合保护下游昂贵资源，如大模型调用）
     */
    GLOBAL
}
