package com.knowledge.base.document.utils;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 基于磁盘文件的 MultipartFile 实现
 *
 * <p>批量导入时，上传的文件在 HTTP 请求结束后会被容器清理，
 * 因此提交阶段先把文件落到临时目录，异步线程再通过本类按 MultipartFile
 * 复用现有的「解析 → 上传 → 建档」链路，避免改动既有服务签名。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
public class DiskMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final Path path;

    public DiskMultipartFile(Path path) {
        this(path, path.getFileName().toString());
    }

    public DiskMultipartFile(Path path, String originalFilename) {
        this(path, originalFilename, guessContentType(originalFilename));
    }

    public DiskMultipartFile(Path path, String originalFilename, String contentType) {
        this.name = "file";
        this.path = path;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        try {
            return Files.size(path) == 0;
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    public long getSize() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(path);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
        Files.copy(path, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 按扩展名粗略推断 Content-Type（仅为解析器判断使用）
     */
    private static String guessContentType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "application/octet-stream";
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        switch (ext) {
            case "pdf":
                return "application/pdf";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt":
                return "text/plain";
            case "md":
                return "text/markdown";
            default:
                return "application/octet-stream";
        }
    }
}
