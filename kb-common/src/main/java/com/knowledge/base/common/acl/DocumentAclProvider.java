package com.knowledge.base.common.acl;

import java.util.List;

/**
 * 文档级权限数据源
 *
 * <p>唯一实现方是文档服务（kb-document），其它服务通过 Feign 调用。
 * 之所以做成接口，是为了让 {@link DocumentAclService} 的缓存/降级逻辑
 * 与具体的调用方式（Feign / 直连）解耦。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
public interface DocumentAclProvider {

    /**
     * 拉取指定用户不可见的文档ID列表
     *
     * @param userId 用户ID；null 表示匿名
     * @return 不可见文档ID列表
     */
    List<Long> fetchInaccessibleDocumentIds(Long userId);
}
