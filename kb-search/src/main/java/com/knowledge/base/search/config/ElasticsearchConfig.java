package com.knowledge.base.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

import java.time.Duration;

/**
 * Elasticsearch配置类
 *
 * <p><b>注意：</b>本类自定义了 {@code RestClient} Bean，会让 Spring Boot 的
 * {@code ElasticsearchRestClientAutoConfiguration} 自动退避，因此
 * {@code spring.elasticsearch.connection-timeout} / {@code socket-timeout}
 * <b>必须在这里显式设置</b>，否则用的是 ES 客户端默认值（连接 1s / 读取 30s），
 * 在跨公网访问远端 ES 时极易触发 {@code Operation timed out}。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Configuration
@EnableElasticsearchRepositories(basePackages = "com.knowledge.base.search.repository")
public class ElasticsearchConfig {

    @Value("${spring.elasticsearch.host:localhost}")
    private String host;

    @Value("${spring.elasticsearch.port:9200}")
    private int port;

    @Value("${spring.elasticsearch.username:}")
    private String username;

    @Value("${spring.elasticsearch.password:}")
    private String password;

    /** 建立 TCP 连接的超时时间（默认 5s，原先未设置，实际用的是客户端默认 1s） */
    @Value("${spring.elasticsearch.connection-timeout:5s}")
    private Duration connectionTimeout;

    /** 等待响应的读取超时时间（默认 60s） */
    @Value("${spring.elasticsearch.socket-timeout:60s}")
    private Duration socketTimeout;

    /** 从连接池获取连接的超时时间 */
    @Value("${spring.elasticsearch.connection-request-timeout:10s}")
    private Duration connectionRequestTimeout;

    @Bean
    public RestClient restClient() {
        var builder = RestClient.builder(new HttpHost(host, port, "http"));

        // 显式设置超时：远端 ES（127.0.0.1）链路存在抖动，
        // 默认 1s 连接超时过于激进，会导致关键词搜索偶发失败。
        builder.setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                .setConnectTimeout(Math.toIntExact(connectionTimeout.toMillis()))
                .setSocketTimeout(Math.toIntExact(socketTimeout.toMillis()))
                .setConnectionRequestTimeout(Math.toIntExact(connectionRequestTimeout.toMillis())));

        if (!username.isEmpty() && !password.isEmpty()) {
            var credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }

        return builder.build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
