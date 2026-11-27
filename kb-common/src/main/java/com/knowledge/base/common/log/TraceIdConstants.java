package com.knowledge.base.common.log;

/**
 * 链路追踪常量
 *
 * <p>约定：一个外部请求进入网关时分配（或沿用上游传来的）一个 traceId，
 * 该 traceId 会：</p>
 * <ul>
 *   <li>写入 MDC，让当前服务每行日志都带上它；</li>
 *   <li>通过请求头向下游服务透传（Feign / 网关转发）；</li>
 *   <li>回写到响应头，方便前端排查问题时记录。</li>
 * </ul>
 *
 * @author 苏三
 * @since 1.0.0
 */
public final class TraceIdConstants {

    /**
     * MDC 中的键名，日志 pattern 里的 {@code %X{tid}} 与此对应。
     * 采用 {@code tid} 是为了与 SkyWalking 官方约定保持一致
     * （官方 {@code TraceIdPatternLogbackLayout} 的 {@code %tid} 也输出该值）。
     */
    public static final String MDC_KEY = "tid";

    /**
     * 请求头 / 响应头名称
     */
    public static final String HEADER = "X-Request-Id";

    /**
     * traceId 最大长度（防止外部传入超长值污染日志 / 撑大响应头）
     */
    public static final int MAX_LENGTH = 64;

    private TraceIdConstants() {
    }
}
