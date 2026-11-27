package com.knowledge.base.graph.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 知识图谱查询缓存配置（进程内 Caffeine 缓存）
 *
 * <h3>为什么从 Redis 换成进程内缓存</h3>
 *
 * <p>图谱查询结果体积很大（实测单次 445KB，其中 117 个 {@code DocumentChunk}
 * 节点还带着完整正文），而 Redis 部署在远端（公网链路实测带宽仅 ~3Mbps）。实测对比：</p>
 *
 * <pre>
 *   不走缓存（直查本机 Neo4j） : 0.54s / 1.20s / 0.81s   平均 0.85s
 *   缓存命中（读远端 Redis）   : 1.34s / 1.48s / 1.44s   平均 1.42s
 * </pre>
 *
 * <p>也就是说，<b>缓存命中比不走缓存还慢约 60%</b>——缓存把大对象发到了慢链路上，
 * 属于典型的负优化。改成进程内 Caffeine 后：</p>
 *
 * <ul>
 *   <li>不需要任何序列化/反序列化（直接存对象），省掉 500KB JSON 的编解码</li>
 *   <li>没有任何网络往返，命中耗时约 0ms</li>
 *   <li>本机 Neo4j 查询本身只要 ~0.85s，缓存才真正起到加速作用</li>
 * </ul>
 *
 * <h3>取舍与注意</h3>
 *
 * <p>进程内缓存不跨实例共享：多实例部署时，A 实例重建图谱后，B 实例的缓存要等
 * TTL 过期才会更新。因此 TTL 默认设为 5 分钟（而不是原来的 30 分钟）以限制陈旧窗口；
 * 单实例部署无此问题。若后续 Redis 迁移到内网低延迟环境，也可以按需换回 Redis 缓存。</p>
 *
 * <h3>可配置项</h3>
 *
 * <pre>
 * kb:
 *   graph:
 *     cache:
 *       ttl-seconds: 300   # 缓存过期时间
 *       max-size: 64       # 单缓存区最大条目数
 * </pre>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 图谱各查询接口对应的缓存区名称（与 {@code @Cacheable} / {@code @CacheEvict} 保持一致）
     */
    private static final List<String> CACHE_NAMES = List.of(
            "graphNodes",
            "graphEdges",
            "graphNodeRelations",
            "graphSearch",
            "graphPath",
            "graphCommunity",
            "graphData"
    );

    /** 缓存过期时间（秒），默认 5 分钟 */
    @Value("${kb.graph.cache.ttl-seconds:300}")
    private long ttlSeconds;

    /** 单个缓存区最大条目数（图谱结果体积大，不宜过多） */
    @Value("${kb.graph.cache.max-size:64}")
    private long maxSize;

    @Bean
    public CacheManager graphCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCacheNames(CACHE_NAMES);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize)
                .recordStats());

        log.info("图谱缓存已启用（进程内 Caffeine）：caches={}, ttl={}s, maxSize={}",
                CACHE_NAMES, ttlSeconds, maxSize);
        return cacheManager;
    }
}
