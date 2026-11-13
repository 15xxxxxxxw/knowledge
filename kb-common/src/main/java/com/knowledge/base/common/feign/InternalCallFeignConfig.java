package com.knowledge.base.common.feign;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * 内部调用认证配置（用于 {@code @FeignClient(configuration = ...)}）
 *
 * <p>下游服务（如 kb-document）的 JwtAuthenticationFilter 在缺少有效 Token 时，
 * 会退化为读取 {@code X-User-Id} 请求头完成认证；同时 {@code INNER-REQUEST}
 * 用于标记"服务间内部调用"（网关已剥离外部伪造的同名头）。</p>
 *
 * <p>缺少这两个头时，服务间调用会被下游判定为未登录并返回 401
 * （典型现象：kb-search 拉取文档级权限失败 → 检索结果被 fail-closed 清空）。</p>
 *
 * <p><b>注意：</b>本类刻意不加 {@code @Configuration}，只通过 {@code @FeignClient}
 * 的 {@code configuration} 属性引用，避免变成全局配置影响其它 Feign 客户端。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
public class InternalCallFeignConfig {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_INNER_REQUEST = "INNER-REQUEST";

    @Bean
    public RequestInterceptor internalCallInterceptor() {
        return template -> {
            template.header(HEADER_USER_ID, "0");
            template.header(HEADER_INNER_REQUEST, "true");
        };
    }
}
