package com.chatbot.global.config

import com.chatbot.ai.openai.OpenAiProperties
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * OpenAI 호출용 WebClient. 외부 API I/O 대기가 길고 스트리밍을 사용하므로
 * non-blocking Reactor Netty + 커넥션 풀/타임아웃 튜닝으로 처리량을 확보한다.
 */
@Configuration
@EnableConfigurationProperties(OpenAiProperties::class)
class WebClientConfig {

    @Bean
    fun openAiWebClient(props: OpenAiProperties): WebClient {
        val connectionProvider = ConnectionProvider.builder("openai-pool")
            .maxConnections(200)
            .pendingAcquireMaxCount(500)
            .pendingAcquireTimeout(Duration.ofSeconds(10))
            .maxIdleTime(Duration.ofSeconds(30))
            .maxLifeTime(Duration.ofSeconds(300))
            .evictInBackground(Duration.ofSeconds(60))
            .lifo()
            .build()

        val httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            .responseTimeout(Duration.ofSeconds(30))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(120, TimeUnit.SECONDS))
            }
            .compress(true)

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .baseUrl(props.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${props.apiKey}")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .codecs { it.defaultCodecs().maxInMemorySize(2 * 1024 * 1024) }
            .build()
    }
}
