package com.cobalt.rag.config;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    /**
     * Override the default RestClient.Builder to use Apache HttpClient 5.
     *
     * The JDK default (HttpURLConnection) throws HttpRetryException when the
     * servlet is in SSE streaming mode, because it cannot buffer the outbound
     * request body for auth-retry while the response channel is already open.
     * Apache HttpClient 5 does not have this limitation.
     *
     * Spring AI's OpenAI auto-configuration injects this builder to construct
     * its internal RestClient, so this swap covers both the embedding call
     * (VectorSearchService) and the chat/stream call (RagService).
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(
                        HttpClients.createDefault()));
    }
}