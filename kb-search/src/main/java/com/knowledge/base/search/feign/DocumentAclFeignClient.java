package com.knowledge.base.search.feign;

import org.springframework.cloud.openfeign.FeignClient;
import com.knowledge.base.common.feign.InternalCallFeignConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * kb-document 文档级权限 Feign 客户端
 *
 * <p>搜索服务在返回结果前调用它获取「当前用户不可见的文档ID」，
 * 用于排除私有文档，避免越权检索。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@FeignClient(
        name = "kb-document",
        url = "${kb-document.url:#{null}}",
        contextId = "documentAclFeignClient",
        // 内部调用需要携带 X-User-Id / INNER-REQUEST，否则 kb-document 会返回 401
        configuration = InternalCallFeignConfig.class
)
public interface DocumentAclFeignClient {

    /**
     * 查询指定用户不可见的文档ID列表
     *
     * @param userId 用户ID；不传表示匿名
     * @return 统一 Result 结构，data 为 Long 列表
     */
    @GetMapping("/documents/inaccessible-ids")
    Map<String, Object> getInaccessibleDocumentIds(@RequestParam(value = "userId", required = false) Long userId);
}
