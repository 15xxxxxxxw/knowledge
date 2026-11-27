package com.knowledge.base.document.client;

import cn.hutool.core.util.StrUtil;
import com.knowledge.base.document.config.MineruProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * MinerU 文档解析客户端
 *
 * <p>把 PDF 交给独立的 MinerU 服务转成 Markdown。支持两种服务形态：</p>
 * <ol>
 *   <li><b>同步返回</b>：响应体里直接带 {@code markdown} / {@code content} 字段；</li>
 *   <li><b>异步任务</b>：响应体返回 {@code taskId}，客户端轮询
 *       {@code {taskPath}/{taskId}} 直到完成或超时。</li>
 * </ol>
 *
 * <p><b>失败一律返回 null</b>，由调用方回退到 Java 原生解析 ——
 * 导入流程不应该因为解析服务抖动而整体失败。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Component
public class MineruClient {

    /** 响应体里可能承载 Markdown 的字段名（兼容不同封装） */
    private static final String[] MARKDOWN_FIELDS = {"markdown", "md", "content", "text", "result"};
    /** 响应体里可能承载任务 ID 的字段名 */
    private static final String[] TASK_ID_FIELDS = {"taskId", "task_id", "id"};
    /** 任务状态字段名 */
    private static final String[] STATUS_FIELDS = {"status", "state"};

    private final MineruProperties properties;
    private final RestTemplate restTemplate;
    private final Semaphore permits;

    public MineruClient(MineruProperties properties,
                        @Qualifier("mineruRestTemplate") RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.permits = new Semaphore(Math.max(1, properties.getMaxConcurrency()));
    }

    /**
     * MinerU 是否可用（开关打开且配置了服务地址）
     */
    public boolean isEnabled() {
        return properties.isEnabled() && StrUtil.isNotBlank(properties.getBaseUrl());
    }

    /**
     * 文件大小是否在 MinerU 的处理范围内
     */
    public boolean supportsSize(long sizeBytes) {
        return sizeBytes > 0 && sizeBytes <= (long) properties.getMaxFileSizeMb() * 1024 * 1024;
    }

    /**
     * 解析为 Markdown
     *
     * @param bytes    文件字节
     * @param filename 原始文件名（带扩展名）
     * @return Markdown；未启用、并发已满、超时或失败时返回 {@code null}
     */
    public String parseToMarkdown(byte[] bytes, String filename) {
        if (!isEnabled()) {
            return null;
        }
        if (!supportsSize(bytes.length)) {
            log.info("文件超出 MinerU 处理上限（{}MB），跳过：name={}, size={}KB",
                    properties.getMaxFileSizeMb(), filename, bytes.length / 1024);
            return null;
        }
        if (!permits.tryAcquire()) {
            log.warn("MinerU 并发已满（max={}），本次跳过：name={}", properties.getMaxConcurrency(), filename);
            return null;
        }

        long start = System.currentTimeMillis();
        try {
            Map<?, ?> response = submit(bytes, filename);
            if (response == null) {
                return null;
            }

            // 情况一：同步返回，响应体里直接有 Markdown
            String markdown = pickString(response, MARKDOWN_FIELDS);
            if (StrUtil.isNotBlank(markdown)) {
                log.info("MinerU 解析成功（同步）：name={}, chars={}, elapsed={}ms",
                        filename, markdown.length(), System.currentTimeMillis() - start);
                return markdown;
            }

            // 情况二：异步任务，轮询结果
            String taskId = pickString(response, TASK_ID_FIELDS);
            if (StrUtil.isBlank(taskId)) {
                log.warn("MinerU 响应既没有 Markdown 也没有 taskId：name={}, response={}",
                        filename, response);
                return null;
            }
            return pollTask(taskId, filename, start);
        } catch (Exception e) {
            log.warn("MinerU 解析失败，将回退 Java 原生解析：name={}, error={}", filename, e.getMessage());
            return null;
        } finally {
            permits.release();
        }
    }

    /**
     * 提交解析请求
     */
    private Map<?, ?> submit(byte[] bytes, String filename) {
        String url = StrUtil.removeSuffix(properties.getBaseUrl(), "/") + properties.getParsePath();

        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        return response.getBody();
    }

    /**
     * 轮询异步任务直到完成或超时
     */
    private String pollTask(String taskId, String filename, long start) {
        String url = StrUtil.removeSuffix(properties.getBaseUrl(), "/")
                + properties.getTaskPath() + "/" + taskId;
        long deadline = start + (long) properties.getTimeoutSeconds() * 1000;

        while (System.currentTimeMillis() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(properties.getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            Map<?, ?> task;
            try {
                ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, null, Map.class);
                task = response.getBody();
            } catch (Exception e) {
                log.warn("查询 MinerU 任务失败，继续重试：taskId={}, error={}", taskId, e.getMessage());
                continue;
            }
            if (task == null) {
                continue;
            }

            String markdown = pickString(task, MARKDOWN_FIELDS);
            if (StrUtil.isNotBlank(markdown)) {
                log.info("MinerU 解析成功（异步）：name={}, taskId={}, chars={}, elapsed={}ms",
                        filename, taskId, markdown.length(), System.currentTimeMillis() - start);
                return markdown;
            }

            String status = pickString(task, STATUS_FIELDS);
            if (status != null && isFailureStatus(status)) {
                log.warn("MinerU 任务失败：taskId={}, status={}, response={}", taskId, status, task);
                return null;
            }
        }

        log.warn("MinerU 任务超时（{}s）：name={}, taskId={}",
                properties.getTimeoutSeconds(), filename, taskId);
        return null;
    }

    private boolean isFailureStatus(String status) {
        String s = status.toLowerCase();
        return s.contains("fail") || s.contains("error") || s.contains("cancel");
    }

    /**
     * 从 Map 中按候选字段名取第一个非空字符串
     */
    private String pickString(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String && StrUtil.isNotBlank((String) value)) {
                return (String) value;
            }
            // 兼容 {data: {markdown: "..."}} 这类包装
            if (value instanceof Map) {
                String nested = pickString((Map<?, ?>) value, keys);
                if (StrUtil.isNotBlank(nested)) {
                    return nested;
                }
            }
        }
        return null;
    }
}
