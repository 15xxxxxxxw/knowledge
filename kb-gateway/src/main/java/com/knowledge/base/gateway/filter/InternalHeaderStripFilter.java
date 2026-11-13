package com.knowledge.base.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 剥离外部请求伪造的内部调用标记
 *
 * <p>各服务通过 {@code INNER-REQUEST} 请求头识别"服务间内部调用"
 * （见 kb-common 的 FeignInterceptorConfig），并据此放宽部分校验
 * （例如文档级权限对内部任务放行、异常返回结构不同）。</p>
 *
 * <p>问题是网关此前不做处理，**外部客户端只要自己带上这个头就能冒充内部调用**。
 * 该过滤器在流量入口统一删除该头，内部调用的头只会由服务间 Feign 调用重新添加。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Component
public class InternalHeaderStripFilter implements GlobalFilter, Ordered {

    private static final String INNER_REQUEST_HEADER = "INNER-REQUEST";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (request.getHeaders().containsKey(INNER_REQUEST_HEADER)) {
            log.warn("拦截到外部请求伪造的内部调用标记，已剥离：uri={}, remoteIp={}",
                    request.getURI().getPath(),
                    request.getRemoteAddress() != null
                            ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown");
            ServerHttpRequest mutated = request.mutate()
                    .headers(headers -> headers.remove(INNER_REQUEST_HEADER))
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 仅次于 TraceIdGlobalFilter（HIGHEST_PRECEDENCE），
        // 保证剥离伪造头时 MDC 里已有 traceId、日志能带上链路信息
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
