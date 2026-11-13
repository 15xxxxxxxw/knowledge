package com.knowledge.base.common.feign;

import cn.hutool.core.util.StrUtil;
import com.knowledge.base.common.log.TraceIdConstants;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

/**
 * Feign请求拦截器
 *
 * <p>为服务间调用添加INNER-REQUEST头，标识为内部调用</p>
 * <p>参考susan-mall-cloud的Feign配置，实现服务间调用的特殊处理</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Configuration
@ConditionalOnClass(RequestInterceptor.class)
public class FeignInterceptorConfig {

    public FeignInterceptorConfig() {
        // 配置类，用于条件化注册Bean
    }

    /**
     * Feign请求拦截器Bean
     */
    @org.springframework.context.annotation.Bean
    public RequestInterceptor feignInterceptor() {
        return new FeignInterceptor();
    }

    /**
     * Feign请求拦截器实现类
     */
    public static class FeignInterceptor implements RequestInterceptor {

        private static final String INNER_REQUEST_HEADER = "INNER-REQUEST";

        @Override
        public void apply(RequestTemplate template) {
            // 添加内部调用标识
            template.header(INNER_REQUEST_HEADER, "true");

            // 透传链路追踪 ID：把当前线程 MDC 里的 traceId 带到下游服务，
            // 使 A -> B -> C 的调用链在日志里共用同一个 traceId。
            String traceId = MDC.get(TraceIdConstants.MDC_KEY);
            if (StrUtil.isNotBlank(traceId)) {
                template.header(TraceIdConstants.HEADER, traceId);
            }
        }
    }
}
