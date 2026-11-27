package com.knowledge.base.common.log;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;

/**
 * TraceId 工具类（SkyWalking 感知）
 *
 * <p>统一入口：应用优先使用 SkyWalking Agent 生成的 traceId，
 * 这样日志里的 traceId 与 SkyWalking UI 上的链路 ID 完全一致，可以直接互相跳转排查。
 * 当 Agent 未挂载、或当前请求未被采样时，SkyWalking 会返回
 * {@code null} / 空串 / {@code "N/A"} / {@code "Ignored_Trace"}，
 * 此时回退为上游传入的 ID 或本地生成的 UUID，保证日志始终有可用的链路标识。</p>
 *
 * <p>为了让运维一眼判断"这个 traceId 能不能去 SkyWalking UI 查"，本地兜底生成的
 * traceId 会带 {@value #LOCAL_PREFIX} 前缀：</p>
 * <ul>
 *   <li>{@code [TID:c03554c8f38b4ee49578aff24cd41fe2]} —— SkyWalking 真实 traceId，UI 可查；</li>
 *   <li>{@code [TID:local-fe7f6282288c43e48971725e6ba17ab7]} —— 本地兜底生成，UI 查不到（属正常）；</li>
 *   <li>{@code [TID:1792145426666-1rpxeg7]} —— 前端/网关透传的 X-Request-Id，UI 查不到。</li>
 * </ul>
 *
 * <p>实测（SkyWalking Java Agent 9.0.0）：</p>
 * <ul>
 *   <li>未挂 Agent：{@code TraceContext.traceId()} 返回空串；</li>
 *   <li>挂了 Agent 但当前无追踪：返回 {@code "N/A"}；</li>
 *   <li>有追踪但未采样：返回 {@code "Ignored_Trace"}；</li>
 *   <li>正常被追踪：返回真实 traceId（形如 32 位十六进制串）。</li>
 * </ul>
 *
 * @author 苏三
 * @since 1.0.0
 */
public final class TraceIdUtil {

    /** SkyWalking 在"无追踪上下文"时返回的占位值 */
    private static final String NA = "N/A";
    /** SkyWalking 在"追踪未被采样"时返回的占位值 */
    private static final String IGNORED = "Ignored_Trace";
    /** 日志中 traceId 为空时的占位符 */
    private static final String EMPTY_PLACEHOLDER = "-";
    /**
     * 本地兜底 traceId 的前缀。
     *
     * <p>用途：区分"能被 SkyWalking UI 检索到的真实 traceId"与"仅在本地日志中有意义的兜底 ID"。
     * 看到 {@code local-} 开头的 TID 就不必去 UI 里白找。</p>
     */
    public static final String LOCAL_PREFIX = "local-";

    private TraceIdUtil() {
    }

    /**
     * 获取当前 SkyWalking traceId（可能是占位值，调用方应先用 {@link #isValid} 校验）
     */
    public static String currentTraceId() {
        try {
            return TraceContext.traceId();
        } catch (Throwable t) {
            // SkyWalking 工具包未就绪时不影响主流程
            return null;
        }
    }

    /**
     * 获取链路 ID：优先 SkyWalking，其次上游透传，最后本地生成
     *
     * @param upstreamTraceId 上游服务透传的 ID，可为空
     */
    public static String resolveTraceId(String upstreamTraceId) {
        String swTraceId = currentTraceId();
        if (isValid(swTraceId)) {
            // SkyWalking 真实 traceId：与 UI 上的链路 ID 一致，可直接检索
            return swTraceId;
        }
        if (StrUtil.isNotBlank(upstreamTraceId)) {
            // 上游（前端 / 网关）透传的请求 ID：保证跨服务日志能串起来
            return limit(upstreamTraceId);
        }
        // 本地兜底：加 local- 前缀，明确标识"该 ID 不在 SkyWalking 中"
        return LOCAL_PREFIX + generateTraceId();
    }

    /**
     * 生成一个新的 traceId
     */
    public static String generateTraceId() {
        return IdUtil.fastSimpleUUID();
    }

    /**
     * 判断是否为有效的 SkyWalking traceId
     */
    public static boolean isValid(String traceId) {
        return StrUtil.isNotBlank(traceId)
                && !NA.equals(traceId)
                && !IGNORED.equals(traceId);
    }

    /**
     * 超长 traceId 截断，避免外部传入值污染日志 / 撑大响应头
     */
    public static String limit(String traceId) {
        if (traceId == null) {
            return null;
        }
        return traceId.length() > TraceIdConstants.MAX_LENGTH
                ? traceId.substring(0, TraceIdConstants.MAX_LENGTH)
                : traceId;
    }

    /**
     * 日志展示用的 traceId，为空时给出占位符
     */
    public static String forLog() {
        String traceId = currentTraceId();
        return isValid(traceId) ? traceId : EMPTY_PLACEHOLDER;
    }
}
