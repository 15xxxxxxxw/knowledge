package com.knowledge.base.document.vo;

import lombok.Data;

/**
 * 批量导入明细（单个文件的结果）
 *
 * @author 苏三
 * @since 1.0.0
 */
@Data
public class BatchImportItemVO {

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 创建成功的文档 ID（失败/跳过时为 null）
     */
    private Long documentId;

    /**
     * 处理结果：SUCCESS / FAILED / SKIPPED
     */
    private String status;

    /**
     * 失败原因或跳过说明
     */
    private String message;

    /**
     * 解析出的正文字符数
     */
    private Integer contentLength;

    /**
     * 耗时（毫秒）
     */
    private Long elapsedMs;
}
