package com.knowledge.base.document.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * MinerU 客户端与批量导入线程池配置
 *
 * @author 苏三
 * @since 1.0.0
 */
@Configuration
public class MineruConfig {

    /**
     * MinerU 专用 RestTemplate
     *
     * <p>不能用 {@code RestTemplateConfig} 里那个 30 秒超时的实例：
     * 一份扫描件解析可能需要几分钟。</p>
     */
    @Bean("mineruRestTemplate")
    public RestTemplate mineruRestTemplate(MineruProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(Math.max(30, properties.getTimeoutSeconds()) * 1000);
        return new RestTemplate(factory);
    }

    /**
     * 批量导入专用线程池
     *
     * <p>与 {@code asyncTaskExecutor} 分开：批量导入是长任务，
     * 不能占用其他异步任务（如索引进度、自动保存快照）的线程。</p>
     */
    @Bean("batchImportExecutor")
    public ThreadPoolTaskExecutor batchImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("batch-import-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
