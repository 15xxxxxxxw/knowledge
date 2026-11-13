package com.knowledge.base.common.acl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 文档级权限服务（检索侧统一入口）
 *
 * <p>解决的问题：搜索 / RAG / KAG 三条检索链路原先都只过滤了"已发布"，
 * 没有校验文档归属，导致任何登录用户都能通过检索拿到别人私有文档的内容。</p>
 *
 * <p>设计要点：</p>
 * <ol>
 *   <li><b>单一事实来源</b>：判定规则只写在文档服务里，检索侧只消费"不可见ID集合"，
 *       避免规则在各服务里各写一份、日后漂移。</li>
 *   <li><b>反向集合</b>：私有文档通常远少于公开文档，返回"不可见集合"比返回
 *       "可见集合"小得多，作为检索排除条件开销更低。</li>
 *   <li><b>Redis 缓存</b>：按用户缓存 60 秒，避免每次检索都跨服务调用；
 *       短暂的服务抖动也能靠缓存扛过去。</li>
 *   <li><b>fail-closed</b>：权限解析失败时返回 {@link DocumentAclResult#unresolved()}，
 *       调用方必须返回空结果。权限不可用时宁可查不到，也不能泄露。</li>
 * </ol>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentAclService {

    private static final String CACHE_KEY_PREFIX = "acl:doc:inaccessible:";

    /** 空集合的哨兵值：区分"缓存了空集合"与"没有缓存" */
    private static final String EMPTY_SENTINEL = "__EMPTY__";

    @Resource
    private ObjectProvider<DocumentAclProvider> providerHolder;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 缓存时长（秒），可按需调整 */
    @Value("${kb.document-acl.cache-seconds:60}")
    private long cacheSeconds;

    /** 是否启用文档级权限（应急开关，默认开启） */
    @Value("${kb.document-acl.enabled:true}")
    private boolean enabled;

    /**
     * 获取当前用户不可见的文档ID集合
     *
     * @param userId 当前用户ID（可为 null，表示匿名）
     * @return 判定结果；resolved=false 时调用方必须拒绝返回任何文档
     */
    public DocumentAclResult getInaccessible(Long userId) {
        if (!enabled) {
            return DocumentAclResult.of(Collections.emptySet());
        }

        DocumentAclProvider provider = providerHolder.getIfAvailable();
        if (provider == null) {
            // 该服务没有接权限源（例如内部任务服务），按"无限制"处理
            return DocumentAclResult.of(Collections.emptySet());
        }

        String cacheKey = CACHE_KEY_PREFIX + (userId == null ? "anonymous" : userId);

        String cached = safeGet(cacheKey);
        if (cached != null) {
            return DocumentAclResult.of(parseCached(cached));
        }

        try {
            List<Long> ids = provider.fetchInaccessibleDocumentIds(userId);
            Set<Long> idSet = ids == null ? new LinkedHashSet<>()
                    : ids.stream().filter(java.util.Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            safePut(cacheKey, idSet.isEmpty() ? EMPTY_SENTINEL
                    : idSet.stream().map(String::valueOf).collect(Collectors.joining(",")));

            log.debug("文档权限解析成功：user={}, 不可见文档数={}", userId, idSet.size());
            return DocumentAclResult.of(idSet);
        } catch (Exception e) {
            // fail-closed：权限拿不到就不返回任何内容，避免退化成"全公开"
            log.error("文档权限解析失败，本次检索按 fail-closed 处理（返回空结果）：user={}, error={}",
                    userId, e.getMessage(), e);
            return DocumentAclResult.unresolved();
        }
    }

    /**
     * 便捷方法：把不可见文档包装成 ES 的 terms 排除条件；集合为空或无权限源时返回 null
     */
    public Set<Long> getInaccessibleIdsOrNull(Long userId) {
        DocumentAclResult result = getInaccessible(userId);
        return result.isResolved() ? result.getInaccessibleIds() : null;
    }

    private String safeGet(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读取文档权限缓存失败：key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    private void safePut(String key, String value) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, cacheSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入文档权限缓存失败：key={}, error={}", key, e.getMessage());
        }
    }

    private Set<Long> parseCached(String cached) {
        if (!StringUtils.hasText(cached) || EMPTY_SENTINEL.equals(cached)) {
            return Collections.emptySet();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : cached.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    ids.add(Long.parseLong(trimmed));
                } catch (NumberFormatException ignored) {
                    // 缓存里出现脏数据时忽略该条
                }
            }
        }
        return ids;
    }
}
