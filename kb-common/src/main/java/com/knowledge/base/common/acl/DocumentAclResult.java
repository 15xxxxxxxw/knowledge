package com.knowledge.base.common.acl;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 文档级权限判定结果
 *
 * <p>刻意区分「解析成功但集合为空」和「解析失败」：
 * 前者表示该用户可见全部文档，后者必须按<b>拒绝</b>处理（fail-closed），
 * 否则权限服务不可用时会退化成"所有人都能看所有文档"。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Getter
public class DocumentAclResult {

    /** 是否成功解析出权限集合 */
    private final boolean resolved;

    /** 不可见文档ID集合 */
    private final Set<Long> inaccessibleIds;

    private DocumentAclResult(boolean resolved, Set<Long> inaccessibleIds) {
        this.resolved = resolved;
        this.inaccessibleIds = inaccessibleIds;
    }

    public static DocumentAclResult of(Set<Long> ids) {
        return new DocumentAclResult(true, ids == null ? Collections.emptySet() : ids);
    }

    /**
     * 解析失败：调用方应当直接返回空结果，而不是放行
     */
    public static DocumentAclResult unresolved() {
        return new DocumentAclResult(false, new LinkedHashSet<>());
    }
}
