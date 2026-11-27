package com.knowledge.base.document.controller;

import com.knowledge.base.common.annotation.RateLimit;
import com.knowledge.base.common.annotation.RateLimitType;
import com.knowledge.base.common.result.Result;
import com.knowledge.base.document.dto.BatchImportOptions;
import com.knowledge.base.document.service.BatchImportService;
import com.knowledge.base.document.vo.BatchImportProgressVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档批量导入Controller
 *
 * <p>把手上的 Word / PDF 文档批量导入知识库：服务端调用 MinerU 解析复杂版面与扫描件，
 * 建档后按选项直接发布，发布动作会触发 RAG / KAG / ES 索引，
 * 导入完成后即可被 AI 问答与关键词搜索检索到。</p>
 *
 * <p>网关访问路径：{@code POST /api/document/documents/batch-import}</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/documents/batch-import")
@Tag(name = "文档批量导入", description = "Word/PDF 批量导入知识库（MinerU 解析）")
public class BatchImportController {

    @Resource
    private BatchImportService batchImportService;

    /**
     * 批量导入文档
     *
     * <p>支持一次多选文件，也支持上传 zip 压缩包（自动展开一层）。
     * 接口立即返回 taskId，实际处理在后台异步执行。</p>
     */
    @PostMapping
    @Operation(summary = "批量导入文档",
            description = "上传多个 Word/PDF 文件或 zip 压缩包，异步解析并导入知识库；返回 taskId 用于查询进度")
    @PreAuthorize("hasAnyAuthority("
            + "T(com.knowledge.base.document.constants.DocumentPermissionConstants).DOCUMENT_CREATE, "
            + "T(com.knowledge.base.document.constants.DocumentPermissionConstants).DOCUMENT_EDIT)")
    @RateLimit(key = "document:batch-import", time = 3600, count = 50, type = RateLimitType.USER,
            message = "批量导入过于频繁，请稍后再试")
    public Result<BatchImportProgressVO> batchImport(
            @Parameter(description = "文件列表（支持多选，或上传 zip 包）", required = true)
            @RequestParam("files") List<MultipartFile> files,
            @Parameter(description = "目标分类ID")
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @Parameter(description = "目标团队空间ID")
            @RequestParam(value = "teamId", required = false) Long teamId,
            @Parameter(description = "标签，逗号分隔")
            @RequestParam(value = "tags", required = false) String tags,
            @Parameter(description = "是否公开：0-私有 1-公开")
            @RequestParam(value = "isPublic", required = false, defaultValue = "1") Integer isPublic,
            @Parameter(description = "是否导入后直接发布（发布才会触发RAG/KAG/ES索引）")
            @RequestParam(value = "autoPublish", required = false, defaultValue = "true") Boolean autoPublish,
            @Parameter(description = "是否自动展开zip压缩包")
            @RequestParam(value = "expandZip", required = false, defaultValue = "true") Boolean expandZip) {

        log.info("批量导入文档请求：文件数={}, categoryId={}, autoPublish={}, expandZip={}",
                files == null ? 0 : files.size(), categoryId, autoPublish, expandZip);

        BatchImportOptions options = new BatchImportOptions();
        options.setCategoryId(categoryId);
        options.setTeamId(teamId);
        options.setTags(tags);
        options.setIsPublic(isPublic);
        options.setAutoPublish(autoPublish == null || autoPublish);
        options.setExpandZip(expandZip == null || expandZip);

        BatchImportProgressVO progress = batchImportService.submit(files, options);
        return Result.success("批量导入任务已提交", progress);
    }

    /**
     * 查询批量导入进度
     */
    @GetMapping("/progress/{taskId}")
    @Operation(summary = "查询批量导入进度", description = "按 taskId 查询批量导入的处理进度与逐文件明细")
    public Result<BatchImportProgressVO> progress(
            @Parameter(description = "任务ID", required = true)
            @PathVariable("taskId") String taskId) {

        log.info("批量导进步伐查询：taskId={}", taskId);
        return Result.success(batchImportService.getProgress(taskId));
    }
}
