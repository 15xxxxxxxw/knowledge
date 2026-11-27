package com.knowledge.base.document.service;

import com.knowledge.base.document.dto.BatchImportOptions;
import com.knowledge.base.document.vo.BatchImportProgressVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 批量导入服务
 *
 * <p>把手上的 Word / PDF 文档批量转成知识库文档：</p>
 * <ol>
 *   <li>文件先落临时目录（HTTP 请求结束后 MultipartFile 会被清理）；</li>
 *   <li>异步逐份执行「解析（MinerU / 原生）→ 上传 → 建档」；</li>
 *   <li>按选项直接发布，发布动作本身会触发 RAG / KAG / ES 索引，
 *       导入完成后即可被 AI 问答和关键词搜索检索到；</li>
 *   <li>实时更新进度，通过 {@link #getProgress(String)} 查询。</li>
 * </ol>
 *
 * @author 苏三
 * @since 1.0.0
 */
public interface BatchImportService {

    /**
     * 提交批量导入任务（立即返回，后台异步执行）
     *
     * @param files   上传的文件（支持多选，也支持 zip 压缩包）
     * @param options 导入选项
     * @return 初始进度（含 taskId）
     */
    BatchImportProgressVO submit(List<MultipartFile> files, BatchImportOptions options);

    /**
     * 查询任务进度
     *
     * @param taskId 任务 ID
     * @return 进度详情
     */
    BatchImportProgressVO getProgress(String taskId);
}
