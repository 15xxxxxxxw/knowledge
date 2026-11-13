package com.knowledge.base.gateway.filter;

import com.knowledge.base.common.log.TraceIdConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全局日志过滤器
 *
 * <p>按照阿里巴巴Java开发规范设计，记录所有请求和响应信息</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Component
public class GlobalLogFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // traceId 由 TraceIdGlobalFilter（顺序更靠前）注入请求头；
        // 这里直接从请求头读取，避免依赖 Netty 事件循环下不可靠的 MDC。
        String traceId = request.getHeaders().getFirst(TraceIdConstants.HEADER);

        // 记录请求信息
        log.info("[{}] 请求 => Method: {}, URI: {}, RemoteAddress: {}",
            traceId,
            request.getMethod(),
            request.getURI(),
            request.getRemoteAddress());

        long startTime = System.currentTimeMillis();

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long endTime = System.currentTimeMillis();
            log.info("[{}] 响应 => StatusCode: {}, Time: {}ms",
                traceId,
                exchange.getResponse().getStatusCode(),
                endTime - startTime);
        }));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
