package com.knowledge.base.common.aspect;

import com.knowledge.base.common.annotation.RateLimit;
import com.knowledge.base.common.annotation.RateLimitType;
import com.knowledge.base.common.exception.RateLimitException;
import com.knowledge.base.common.ratelimit.RateLimitResult;
import com.knowledge.base.common.ratelimit.RateLimiter;
import com.knowledge.base.common.utils.UserContextUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 限流 AOP 切面
 *
 * <p>拦截 {@link RateLimit}（方法级优先于类级），按注解声明的维度调用
 * {@link RateLimiter} 判定；超限时抛出 {@link RateLimitException}（业务码 429），
 * 由 {@code GlobalExceptionHandler} 统一转成 {@code Result} 返回前端。</p>
 *
 * <p>维度取值：</p>
 * <ul>
 *   <li>{@code AUTO}：已登录按 userId，未登录按 IP（默认，最实用）</li>
 *   <li>{@code IP}：按客户端 IP（登录、注册、验证码等未登录接口）</li>
 *   <li>{@code USER}：按登录用户（AI 对话、上传等）</li>
 *   <li>{@code GLOBAL}：全实例共享一个配额（保护昂贵下游资源）</li>
 * </ul>
 *
 * <p>仅在 Servlet Web 环境下生效，WebFlux（Gateway）由网关自身的限流过滤器负责。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Aspect
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RateLimitAspect {

    /** Redis 限流键前缀 */
    private static final String KEY_PREFIX = "rate:limit:";

    /** 触发限流时写入响应头的重试等待秒数 */
    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    @Resource
    private RateLimiter rateLimiter;

    @Resource
    private HttpServletRequest request;

    @Resource
    private HttpServletResponse response;

    /**
     * 限流总开关，线上可按需关闭（kb.rate-limit.enabled=false）
     */
    @Value("${kb.rate-limit.enabled:true}")
    private boolean enabled;

    /**
     * Redis 不可用时的策略：true=放行（保可用性，默认），false=拒绝（保安全）
     */
    @Value("${kb.rate-limit.fail-open:true}")
    private boolean failOpen;

    @Around("@annotation(com.knowledge.base.common.annotation.RateLimit)"
            + " || @within(com.knowledge.base.common.annotation.RateLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        RateLimit rateLimit = resolveAnnotation(joinPoint);
        if (!enabled || rateLimit == null) {
            return joinPoint.proceed();
        }

        String businessKey = buildBusinessKey(joinPoint, rateLimit);
        String dimension = resolveDimension(rateLimit.type());
        String limitKey = KEY_PREFIX + businessKey + ":" + dimension;

        RateLimitResult result;
        try {
            result = rateLimiter.tryAcquire(limitKey, rateLimit.count(), rateLimit.time());
        } catch (Exception e) {
            // 限流组件自身故障：按策略决定放行还是拒绝，绝不影响业务方法的正常执行路径
            if (failOpen) {
                log.error("限流组件异常，按 fail-open 策略放行：key={}, error={}", limitKey, e.getMessage(), e);
                return joinPoint.proceed();
            }
            log.error("限流组件异常，按 fail-close 策略拒绝：key={}, error={}", limitKey, e.getMessage(), e);
            throw new RateLimitException(rateLimit.message(), limitKey);
        }

        writeHeaders(result, rateLimit);

        if (!result.isAllowed()) {
            log.warn("触发限流：uri={}, method={}, key={}, 当前={}/{}次每{}秒",
                    request.getRequestURI(), request.getMethod(), limitKey,
                    result.getCurrentCount(), rateLimit.count(), rateLimit.time());
            throw new RateLimitException(rateLimit.message(), limitKey);
        }

        return joinPoint.proceed();
    }

    /**
     * 解析注解：方法级优先，其次类级
     */
    private RateLimit resolveAnnotation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RateLimit rateLimit = AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class);
        if (rateLimit != null) {
            return rateLimit;
        }
        return AnnotatedElementUtils.findMergedAnnotation(AopUtils.getTargetClass(joinPoint.getTarget()),
                RateLimit.class);
    }

    /**
     * 业务 key：注解 key 优先，否则用「类名.方法名」
     */
    private String buildBusinessKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        if (rateLimit.key() != null && !rateLimit.key().isBlank()) {
            return rateLimit.key();
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return AopUtils.getTargetClass(joinPoint.getTarget()).getSimpleName()
                + "." + signature.getName();
    }

    /**
     * 解析限流维度
     */
    private String resolveDimension(RateLimitType type) {
        Long userId = resolveUserId();
        String ip = resolveClientIp();

        return switch (type) {
            case GLOBAL -> "global";
            // 明确要求按用户限流但取不到用户时，退回 IP，避免退化成"不限流"
            case USER -> userId != null ? "user:" + userId : "ip:" + ip;
            case IP -> "ip:" + ip;
            // AUTO：登录用户按用户维度，未登录按 IP 维度
            case AUTO -> userId != null ? "user:" + userId : "ip:" + ip;
        };
    }

    /**
     * 获取当前登录用户：
     * 1) 优先读 JWT 过滤器写入的 ThreadLocal（kb-document / kb-user-auth / kb-foundation）
     * 2) 其次读网关透传的 X-User-Id 请求头（kb-ai / kb-file / kb-search 等）
     */
    private Long resolveUserId() {
        Long userId = UserContextUtil.getUserId();
        if (userId != null) {
            return userId;
        }
        String headerUserId = request.getHeader("X-User-Id");
        if (headerUserId != null && !headerUserId.isBlank()) {
            try {
                return Long.parseLong(headerUserId.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveClientIp() {
        try {
            // 只信任"最近一跳代理写入"的地址，即 X-Forwarded-For 的最后一段。
            // 若取第一段，客户端自带一个伪造的 X-Forwarded-For 就能无限轮换 IP 绕过限流；
            // 网关在转发时会把真实客户端地址追加到末尾，因此末段才是可信的。
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                String last = parts[parts.length - 1].trim();
                if (!last.isEmpty() && !"unknown".equalsIgnoreCase(last)) {
                    return last;
                }
            }
            String remote = request.getRemoteAddr();
            return remote != null && !remote.isBlank() ? remote : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 回写限流响应头，方便前端/网关感知配额情况
     */
    private void writeHeaders(RateLimitResult result, RateLimit rateLimit) {
        try {
            if (response == null || response.isCommitted()) {
                return;
            }
            response.setHeader(HEADER_LIMIT, String.valueOf(rateLimit.count()));
            response.setHeader(HEADER_REMAINING, String.valueOf(result.getRemaining()));
            if (!result.isAllowed()) {
                response.setHeader(HEADER_RETRY_AFTER, String.valueOf(result.getWindowSeconds()));
            }
        } catch (Exception e) {
            // 响应头写入失败不影响主流程
            log.debug("写入限流响应头失败：{}", e.getMessage());
        }
    }
}
