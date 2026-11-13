package com.knowledge.base.document.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.base.common.exception.BusinessException;
import com.knowledge.base.common.utils.UserContextUtil;
import com.knowledge.base.document.dto.BatchImportOptions;
import com.knowledge.base.document.entity.Document;
import com.knowledge.base.document.mapper.DocumentMapper;
import com.knowledge.base.document.service.BatchImportService;
import com.knowledge.base.document.service.DocumentService;
import com.knowledge.base.document.utils.DiskMultipartFile;
import com.knowledge.base.document.vo.BatchImportItemVO;
import com.knowledge.base.document.vo.BatchImportProgressVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 批量导入服务实现
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Service
public class BatchImportServiceImpl implements BatchImportService {

    /** 可解析的扩展名（与 FileParserServiceImpl 保持一致） */
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("pdf", "docx", "xlsx", "pptx", "txt", "md");

    /** 旧版二进制 Office 格式：解析器与 MinerU 都不支持，明确提示用户转换 */
    private static final Set<String> LEGACY_EXTENSIONS = Set.of("doc", "xls", "ppt");

    private static final String PROGRESS_KEY_PREFIX = "kb:document:batch-import:";
    private static final long PROGRESS_TTL_HOURS = 24;

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String ITEM_SUCCESS = "SUCCESS";
    private static final String ITEM_FAILED = "FAILED";
    private static final String ITEM_SKIPPED = "SKIPPED";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 运行中的任务（内存为主，Redis 为备份：查询优先内存，跨实例/重启后回落到 Redis）
     */
    private final Map<String, BatchImportProgressVO> runningTasks = new ConcurrentHashMap<>();

    @Resource
    private DocumentService documentService;

    @Resource
    private DocumentMapper documentMapper;

    @Resource(name = "batchImportExecutor")
    private ThreadPoolTaskExecutor batchImportExecutor;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public BatchImportProgressVO submit(List<MultipartFile> files, BatchImportOptions options) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException("请选择要导入的文件");
        }
        BatchImportOptions opts = options == null ? new BatchImportOptions() : options;

        // 异步线程取不到 ThreadLocal，这里先在请求线程抓取用户上下文
        Long userId = UserContextUtil.getUserId();
        String username = UserContextUtil.getUsername();
        String token = UserContextUtil.getToken();

        String taskId = IdUtil.fastSimpleUUID();
        BatchImportProgressVO progress = new BatchImportProgressVO();
        progress.setTaskId(taskId);
        progress.setStatus(STATUS_RUNNING);
        progress.setStartedAt(LocalDateTime.now().format(TIME_FORMAT));

        Path workDir = Paths.get(System.getProperty("java.io.tmpdir"), "kb-batch-import", taskId);
        List<Path> stagedFiles = new ArrayList<>();
        try {
            Files.createDirectories(workDir);
            for (MultipartFile file : files) {
                stageFile(file, workDir, opts.isExpandZip(), stagedFiles, progress);
            }
        } catch (IOException e) {
            log.error("批量导入准备文件失败：taskId={}, error={}", taskId, e.getMessage(), e);
            deleteQuietly(workDir);
            throw new BusinessException("准备导入文件失败: " + e.getMessage());
        }

        if (stagedFiles.isEmpty()) {
            deleteQuietly(workDir);
            throw new BusinessException("没有可导入的文件（支持 pdf / docx / xlsx / pptx / txt / md，可上传 zip 压缩包）");
        }

        stagedFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
        progress.setTotal(stagedFiles.size());
        progress.setSkipped(progress.getItems().size());
        progress.setProcessed(progress.getSkipped());
        saveProgress(progress);

        log.info("批量导入任务已提交：taskId={}, 文件数={}, 跳过={}, autoPublish={}, user={}",
                taskId, stagedFiles.size(), progress.getSkipped(), opts.isAutoPublish(), username);

        final List<Path> filesToProcess = stagedFiles;
        batchImportExecutor.execute(() ->
                runImport(taskId, workDir, filesToProcess, opts, userId, username, token));

        return progress;
    }

    @Override
    public BatchImportProgressVO getProgress(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            throw new BusinessException("任务ID不能为空");
        }
        BatchImportProgressVO progress = runningTasks.get(taskId);
        if (progress != null) {
            return progress;
        }
        progress = loadFromRedis(taskId);
        if (progress == null) {
            throw new BusinessException("导入任务不存在或已过期（进度保留 24 小时）");
        }
        return progress;
    }

    // ==================== 异步执行 ====================

    private void runImport(String taskId, Path workDir, List<Path> files,
                           BatchImportOptions options, Long userId, String username, String token) {
        // 恢复用户上下文，让文档的 authorId / createBy 等字段正确
        UserContextUtil.setUserId(userId);
        UserContextUtil.setUsername(username);
        UserContextUtil.setToken(token);

        BatchImportProgressVO progress = runningTasks.get(taskId);
        if (progress == null) {
            progress = loadFromRedis(taskId);
        }
        if (progress == null) {
            log.error("批量导入任务进度丢失，终止：taskId={}", taskId);
            deleteQuietly(workDir);
            return;
        }

        try {
            for (Path path : files) {
                String fileName = path.getFileName().toString();
                progress.setCurrentFile(fileName);

                BatchImportItemVO item = new BatchImportItemVO();
                item.setFileName(fileName);
                long start = System.currentTimeMillis();

                try {
                    // 复用单文件链路：解析（含 MinerU 策略）→ 上传 → 建档
                    Map<String, Object> created = documentService.uploadAndCreateDocument(new DiskMultipartFile(path));
                    Long documentId = toLong(created.get("documentId"));
                    item.setDocumentId(documentId);
                    item.setContentLength(toInt(created.get("contentLength")));

                    applyMetadata(documentId, options);

                    // 发布动作会触发 RAG / KAG / ES 索引，导入完成后即可被检索
                    if (options.isAutoPublish()) {
                        documentService.publishDirectly(documentId);
                    }

                    item.setStatus(ITEM_SUCCESS);
                    progress.setCompleted(progress.getCompleted() + 1);
                    log.info("批量导入单文件成功：taskId={}, file={}, documentId={}",
                            taskId, fileName, documentId);
                } catch (Exception e) {
                    item.setStatus(ITEM_FAILED);
                    item.setMessage(StrUtil.maxLength(e.getMessage(), 500));
                    progress.setFailed(progress.getFailed() + 1);
                    log.warn("批量导入单文件失败：taskId={}, file={}, error={}", taskId, fileName, e.getMessage());
                }

                item.setElapsedMs(System.currentTimeMillis() - start);
                progress.getItems().add(item);
                progress.setProcessed(progress.getCompleted() + progress.getFailed() + progress.getSkipped());
                saveProgress(progress);
            }

            progress.setStatus(progress.getFailed() == 0 ? STATUS_COMPLETED : STATUS_PARTIAL_FAILED);
        } catch (Exception e) {
            progress.setStatus(STATUS_FAILED);
            progress.setMessage(StrUtil.maxLength(e.getMessage(), 500));
            log.error("批量导入任务异常终止：taskId={}, error={}", taskId, e.getMessage(), e);
        } finally {
            progress.setCurrentFile(null);
            progress.setFinishedAt(LocalDateTime.now().format(TIME_FORMAT));
            saveProgress(progress);
            UserContextUtil.clear();
            deleteQuietly(workDir);
            log.info("批量导入任务结束：taskId={}, status={}, 成功={}, 失败={}, 跳过={}",
                    taskId, progress.getStatus(), progress.getCompleted(), progress.getFailed(), progress.getSkipped());
        }
    }

    /**
     * 补充分类 / 团队 / 标签 / 可见性
     */
    private void applyMetadata(Long documentId, BatchImportOptions options) {
        Document patch = new Document();
        patch.setId(documentId);
        boolean needUpdate = false;

        if (options.getCategoryId() != null) {
            patch.setCategoryId(options.getCategoryId());
            needUpdate = true;
        }
        if (options.getTeamId() != null) {
            patch.setTeamId(options.getTeamId());
            needUpdate = true;
        }
        if (StrUtil.isNotBlank(options.getTags())) {
            patch.setTags(options.getTags());
            needUpdate = true;
        }
        if (options.getIsPublic() != null) {
            patch.setIsPublic(options.getIsPublic());
            needUpdate = true;
        }

        if (needUpdate) {
            documentMapper.updateById(patch);
        }
    }

    // ==================== 文件暂存 / 解压 ====================

    /**
     * 落盘暂存（HTTP 请求结束后 MultipartFile 会被容器清理）
     */
    private void stageFile(MultipartFile file, Path workDir, boolean expandZip,
                           List<Path> staged, BatchImportProgressVO progress) throws IOException {
        String originalName = sanitizeName(file.getOriginalFilename());
        if (StrUtil.isBlank(originalName)) {
            return;
        }
        String ext = extensionOf(originalName);

        // zip：展开成多个文件
        if ("zip".equals(ext)) {
            if (!expandZip) {
                addSkipped(progress, originalName, "已关闭 zip 自动展开，请解压后上传");
                return;
            }
            Path zipPath = uniquePath(workDir, originalName);
            file.transferTo(zipPath.toFile());
            expandZip(zipPath, workDir, staged, progress);
            return;
        }

        if (LEGACY_EXTENSIONS.contains(ext)) {
            addSkipped(progress, originalName, "旧版 ." + ext + " 格式不支持，请另存为 " + ext + "x 后重试");
            return;
        }
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            addSkipped(progress, originalName, "不支持的格式（支持 pdf/docx/xlsx/pptx/txt/md 或 zip 包）");
            return;
        }

        Path target = uniquePath(workDir, originalName);
        file.transferTo(target.toFile());
        staged.add(target);
    }

    /**
     * 展开 zip（只处理一层，跳过目录/隐藏文件/__MACOSX）
     */
    private void expandZip(Path zipPath, Path workDir, List<Path> staged,
                           BatchImportProgressVO progress) {
        try (InputStream in = Files.newInputStream(zipPath);
             ZipInputStream zis = new ZipInputStream(in)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = sanitizeName(entry.getName());
                if (StrUtil.isBlank(entryName) || entryName.startsWith(".") || entryName.startsWith("__MACOSX")) {
                    continue;
                }
                String ext = extensionOf(entryName);
                if (LEGACY_EXTENSIONS.contains(ext)) {
                    addSkipped(progress, entryName, "旧版 ." + ext + " 格式不支持，请另存为 " + ext + "x 后重试");
                    continue;
                }
                if (!SUPPORTED_EXTENSIONS.contains(ext)) {
                    addSkipped(progress, entryName, "不支持的格式");
                    continue;
                }
                Path target = uniquePath(workDir, entryName);
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                staged.add(target);
            }
        } catch (IOException e) {
            log.warn("展开 zip 失败：file={}, error={}", zipPath.getFileName(), e.getMessage());
            addSkipped(progress, zipPath.getFileName().toString(), "zip 解析失败: " + e.getMessage());
        } finally {
            deleteQuietlyFile(zipPath);
        }
    }

    private void addSkipped(BatchImportProgressVO progress, String fileName, String message) {
        BatchImportItemVO item = new BatchImportItemVO();
        item.setFileName(fileName);
        item.setStatus(ITEM_SKIPPED);
        item.setMessage(message);
        progress.getItems().add(item);
    }

    /**
     * 去掉路径信息，防止 ../ 目录穿越；中文名等保持原样
     */
    private String sanitizeName(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        String fixed = name.replace('\\', '/');
        int idx = fixed.lastIndexOf('/');
        return idx >= 0 ? fixed.substring(idx + 1) : fixed;
    }

    private String extensionOf(String fileName) {
        if (StrUtil.isBlank(fileName) || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 同名文件追加序号，避免 zip 内重名互相覆盖
     */
    private Path uniquePath(Path dir, String fileName) {
        Path target = dir.resolve(fileName);
        if (!Files.exists(target)) {
            return target;
        }
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        int i = 1;
        while (Files.exists(target)) {
            target = dir.resolve(base + "_" + i + ext);
            i++;
        }
        return target;
    }

    // ==================== 进度存取 ====================

    private void saveProgress(BatchImportProgressVO progress) {
        runningTasks.put(progress.getTaskId(), progress);
        try {
            String json = objectMapper.writeValueAsString(progress);
            stringRedisTemplate.opsForValue().set(
                    PROGRESS_KEY_PREFIX + progress.getTaskId(), json, PROGRESS_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("批量导入进度写入 Redis 失败（不影响任务执行）：taskId={}, error={}",
                    progress.getTaskId(), e.getMessage());
        }
    }

    private BatchImportProgressVO loadFromRedis(String taskId) {
        try {
            String json = stringRedisTemplate.opsForValue().get(PROGRESS_KEY_PREFIX + taskId);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return objectMapper.readValue(json, BatchImportProgressVO.class);
        } catch (Exception e) {
            log.warn("读取批量导入进度失败：taskId={}, error={}", taskId, e.getMessage());
            return null;
        }
    }

    // ==================== 清理 ====================

    private void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietlyFile);
        } catch (IOException e) {
            log.warn("清理批量导入临时目录失败：dir={}, error={}", dir, e.getMessage());
        }
    }

    private void deleteQuietlyFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 忽略清理失败
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.valueOf(value.toString());
    }

    private Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.valueOf(value.toString());
    }
}
