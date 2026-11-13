package com.knowledge.base.ai.client;

import com.knowledge.base.ai.config.InternalFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * kb-document 服务 Feign 客户端
 *
 * <p>用于 kb-ai 调用 kb-document 获取文档内容和元数据，
 * 支持文档索引和重建索引场景。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@FeignClient(name = "kb-document", url = "${kb-document.url:#{null}}",
        configuration = InternalFeignConfig.class)
public interface DocumentFeignClient {

    /**
     * 获取文档详情（含MongoDB内容）
     */
    @GetMapping("/documents/{documentId}")
    Map<String, Object> getDocument(@PathVariable("documentId") Long documentId);

    /**
     * 分页获取已发布文档列表
     */
    @GetMapping("/documents/page")
    Map<String, Object> pageDocuments(@RequestParam("current") Long current,
                                       @RequestParam("size") Long size,
                                       @RequestParam(value = "status", required = false) Integer status);

    /**
     * 查询指定用户不可见的文档ID列表（文档级权限）
     *
     * <p>kb-ai 的搜索/RAG/KAG 检索在返回结果前用它做排除过滤，
     * 避免私有文档内容通过问答被越权获取。</p>
     *
     * @param userId 用户ID；不传表示匿名
     * @return 统一 Result 结构，data 为 Long 列表
     */
    @GetMapping("/documents/inaccessible-ids")
    Map<String, Object> getInaccessibleDocumentIds(@RequestParam(value = "userId", required = false) Long userId);
}
