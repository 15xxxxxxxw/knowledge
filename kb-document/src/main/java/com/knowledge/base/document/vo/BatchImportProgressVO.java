package com.knowledge.base.document.vo;

import lombok.Data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 批量导入进度
 *
 * @author 苏三
 * @since 1.0.0
 */
@Data
public class BatchImportProgressVO {

    /**
     * 任务 ID
     */
    private String taskId;

    /**
     * 任务状态：RUNNING / COMPLETED / PARTIAL_FAILED / FAILED
     */
    private String status;

    /**
     * 待处理文件总数
     */
    private int total;

    /**
     * 已处理数（成功 + 失败 + 跳过）
     */
    private int processed;

    /**
     * 成功数
     */
    private int completed;

    /**
     * 失败数
     */
    private int failed;

    /**
     * 跳过数（格式不支持等）
     */
    private int skipped;

    /**
     * 当前正在处理的文件名
     */
    private String currentFile;

    /**
     * 开始时间
     */
    private String startedAt;

    /**
     * 结束时间
     */
    private String finishedAt;

    /**
     * 附加说明（整体失败原因等）
     */
    private String message;

    /**
     * 明细列表
     *
     * <p>用 CopyOnWriteArrayList：异步任务在追加明细的同时，
     * 接口线程可能在序列化同一对象，普通 ArrayList 会抛 ConcurrentModificationException。</p>
     */
    private List<BatchImportItemVO> items = new CopyOnWriteArrayList<>();
}
