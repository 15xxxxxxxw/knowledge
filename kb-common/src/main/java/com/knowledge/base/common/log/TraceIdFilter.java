package com.knowledge.base.common.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 链路追踪过滤器（Servlet 服务通用）
 *
 * <p>作用：为每个 HTTP 请求分配一个 traceId 并放入 MDC，
 * 使得该请求期间打出的所有日志都带同一个 [traceId]，
 * 从而能把一次请求里散落在各个类、各个线程的日志串成一条线。</p>
 *
 * <p>traceId 来源优先级：</p>
 * <ol>
 *   <li>SkyWalking Agent 生成的 traceId（挂了 Agent 且请求被追踪时，与 SkyWalking UI 上的链路 ID 一致）；</li>
 *   <li>请求头 {@code X-Request-Id}（网关 / 上游服务已分配，直接沿用，实现跨服务关联）；</li>
 *   <li>没有则本地生成一个（直连服务、定时任务等场景）。</li>
 * </ol>
 *
 * <p>同时把 traceId 回写到响应头 {@code X-Request-Id}，
 * 方便前端在报错时记录并反馈给后端定位。</p>
 *
 * <p>注意：本类通过 {@code @ConditionalOnWebApplication(SERVLET)} 限定，
 * 网关是 WebFlux（Reactive）应用，不会加载该过滤器，
 * 网关侧对应的是 {@code TraceIdGlobalFilter}。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);

        // 写入 MDC：本请求线程内所有日志都会带上该 traceId
        MDC.put(TraceIdConstants.MDC_KEY, traceId);
        // 回写响应头：前端可读取并记录
        response.setHeader(TraceIdConstants.HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程复用（Tomcat 线程池）必须清理，否则 traceId 会串到下一个请求
            MDC.remove(TraceIdConstants.MDC_KEY);
        }
    }

    /**
     * 解析 traceId：SkyWalking &gt; 上游请求头 &gt; 本地生成
     */
    private String resolveTraceId(HttpServletRequest request) {
        String fromHeader = request.getHeader(TraceIdConstants.HEADER);
        return TraceIdUtil.resolveTraceId(fromHeader);
    }
}
