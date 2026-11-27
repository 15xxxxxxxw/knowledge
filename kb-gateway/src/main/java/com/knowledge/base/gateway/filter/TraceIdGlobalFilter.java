package com.knowledge.base.gateway.filter;

import com.knowledge.base.common.log.TraceIdConstants;
import com.knowledge.base.common.log.TraceIdUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关链路追踪过滤器（WebFlux / Reactive）
 *
 * <p>这是整套链路追踪的<b>起点</b>，职责：</p>
 * <ol>
 *   <li>为进入网关的每个请求分配 traceId（若上游已带 {@code X-Request-Id} 则沿用）；</li>
 *   <li>把 traceId 写入<b>转发给下游服务</b>的请求头，下游 Servlet 服务的
 *       {@code TraceIdFilter} 会读取同一个值，从而 A→B→C 全链路共用一个 traceId；</li>
 *   <li>把 traceId 写入<b>响应头</b>，前端报错时可记录并反馈给后端定位。</li>
 * </ol>
 *
 * <p>说明：网关是 Netty 事件循环模型，MDC（ThreadLocal）无法可靠跨异步线程传递，
 * 因此这里主要通过「请求头透传 + 响应头回写」保证链路可靠；
 * MDC 仅做尽力写入，供网关自身同步执行的日志使用
 * （{@code GlobalLogFilter} 另有从请求头直接读取 traceId 的兜底）。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String traceId = resolveTraceId(request);

        // 1) 注入到转发给下游服务的请求头（下游据此关联同一链路）
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(TraceIdConstants.HEADER, traceId)
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

        // 2) 回写响应头，前端可读取（注意：此处不能覆盖业务已设置的同名头）
        ServerHttpResponse response = mutatedExchange.getResponse();
        if (!response.getHeaders().containsKey(TraceIdConstants.HEADER)) {
            response.getHeaders().set(TraceIdConstants.HEADER, traceId);
        }

        // 3) 尽力写入 MDC，供网关同步执行的日志使用
        MDC.put(TraceIdConstants.MDC_KEY, traceId);

        return chain.filter(mutatedExchange)
                .doFinally(signalType -> MDC.remove(TraceIdConstants.MDC_KEY));
    }

    /**
     * 解析 traceId：SkyWalking &gt; 上游请求头 &gt; 本地生成
     */
    private String resolveTraceId(ServerHttpRequest request) {
        String fromHeader = request.getHeaders().getFirst(TraceIdConstants.HEADER);
        return TraceIdUtil.resolveTraceId(fromHeader);
    }

    @Override
    public int getOrder() {
        // 必须是整条过滤器链的第一个：后面所有过滤器 / 日志都要用到 traceId
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
