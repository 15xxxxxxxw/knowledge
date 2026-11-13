package com.knowledge.base.document.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinerU 解析服务配置
 *
 * <p>MinerU 是独立的 Python 文档解析服务，负责把扫描件、复杂版面 PDF
 * 转换成结构化 Markdown（含 OCR、公式、表格）。Java 侧通过 HTTP 调用它。</p>
 *
 * <p>未启用（{@code mineru.enabled=false}）或调用失败时，
 * 解析会自动回退到 Java 原生的 PDFBox / POI 解析，保证导入流程不中断。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "mineru")
public class MineruProperties {

    /**
     * 是否启用 MinerU 解析（关闭时全部走 Java 原生解析）
     */
    private boolean enabled = false;

    /**
     * MinerU 服务地址，例如 http://127.0.0.1:8000
     */
    private String baseUrl = "";

    /**
     * 解析接口路径（multipart 上传文件）
     */
    private String parsePath = "/v1/parse";

    /**
     * 异步任务查询路径，完整地址为 {taskPath}/{taskId}
     */
    private String taskPath = "/v1/tasks";

    /**
     * HTTP 读取超时（秒）。扫描件解析很慢，默认 10 分钟
     */
    private int timeoutSeconds = 600;

    /**
     * 同时调用 MinerU 的最大并发数
     */
    private int maxConcurrency = 2;

    /**
     * 异步任务轮询间隔（毫秒）
     */
    private int pollIntervalMs = 3000;

    /**
     * 文本层探测：前 probePages 页平均有效字符数低于该值，认为需要 OCR
     */
    private int minTextCharsPerPage = 80;

    /**
     * 文本层探测页数
     */
    private int probePages = 3;

    /**
     * 单个文件超过该大小（MB）时不使用 MinerU，直接走原生解析
     */
    private int maxFileSizeMb = 100;

    /**
     * true = 所有 PDF 都交给 MinerU（不做文本层判断）
     */
    private boolean forceForPdf = false;
}
