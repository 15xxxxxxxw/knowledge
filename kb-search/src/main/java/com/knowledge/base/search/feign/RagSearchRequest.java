package com.knowledge.base.search.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG混合搜索请求
 *
 * @author 苏三
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchRequest {

    private String query;

    @Builder.Default
    private int topK = 10;

    @Builder.Default
    private boolean enableRerank = true;

    /**
     * 当前用户ID（文档级权限过滤用）
     *
     * <p>必须显式透传：kb-ai 侧在缺少该值时会退化为内置的占位用户 1，
     * 导致按"非作者"过滤，用户自己的私有文档在混合搜索里搜不到。</p>
     */
    private Long userId;
}
