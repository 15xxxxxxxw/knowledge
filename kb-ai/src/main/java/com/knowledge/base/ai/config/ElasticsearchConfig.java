package com.knowledge.base.ai.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

import java.time.Duration;

/**
 * kb-ai模块的Elasticsearch配置
 *
 * <p>为RAG向量搜索提供独立的ES客户端配置（与kb-search模块的ES配置独立）。
 * 用于 kb_chunk 索引的 dense_vector 存储和 kNN 搜索。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true", matchIfMissing = true)
@EnableElasticsearchRepositories(basePackages = "com.knowledge.base.ai.rag.repository")
public class ElasticsearchConfig {

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String elasticsearchUris;

    @Value("${spring.elasticsearch.username:}")
    private String username;

    @Value("${spring.elasticsearch.password:}")
    private String password;

    @Value("${spring.elasticsearch.connection-timeout:10s}")
    private Duration connectionTimeout;

    @Value("${spring.elasticsearch.socket-timeout:60s}")
    private Duration socketTimeout;

    @Bean
    public RestClient restClient() {
        String uri = elasticsearchUris.replace("http://", "").replace("https://", "");
        String[] parts = uri.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9200;
        String scheme = elasticsearchUris.startsWith("https") ? "https" : "http";

        var builder = RestClient.builder(new HttpHost(host, port, scheme));

        // 显式设置超时：本类自定义了 RestClient Bean，Spring Boot 的自动配置会退避，
        // 因此上面 @Value 注入的超时参数必须在这里真正应用，
        // 否则用的是 ES 客户端默认值（连接 1s / 读取 30s），跨公网访问远端 ES 时容易超时。
        builder.setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                .setConnectTimeout(Math.toIntExact(connectionTimeout.toMillis()))
                .setSocketTimeout(Math.toIntExact(socketTimeout.toMillis()))
                .setConnectionRequestTimeout(Math.toIntExact(connectionTimeout.toMillis())));

        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
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
