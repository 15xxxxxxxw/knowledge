package com.knowledge.base.document.dto;

import lombok.Data;

/**
 * 批量导入选项
 *
 * @author 苏三
 * @since 1.0.0
 */
@Data
public class BatchImportOptions {

    /**
     * 目标分类 ID（可选）
     */
    private Long categoryId;

    /**
     * 目标团队空间 ID（可选）
     */
    private Long teamId;

    /**
     * 标签，逗号分隔（可选）
     */
    private String tags;

    /**
     * 是否公开：0-私有 1-公开，默认 1
     */
    private Integer isPublic = 1;

    /**
     * 导入完成后是否直接发布（发布才会触发 RAG/KAG/ES 索引），默认 true
     */
    private boolean autoPublish = true;

    /**
     * 是否自动展开 zip 压缩包，默认 true
     */
    private boolean expandZip = true;
}
