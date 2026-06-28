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
 *
 * 타임아웃 계층:
 *   - connect    : 5s   — TCP 핸드셰이크 상한 (CONNECT_TIMEOUT_MILLIS)
 *   - response   : 60s  — 첫 응답 헤더 수신 상한 (responseTimeout). 비스트리밍 응답 전체가
 *                         이 안에 도착해야 하므로 30s 에서 상향.
 *   - read       : 120s — 데이터 청크 간 유휴 상한 (ReadTimeoutHandler). 스트리밍 토큰 간격 허용.
 *   - 전체(mono) : 120s — OpenAiClient.complete()/.stream()의 Reactor .timeout(). 재시도 포함 상한.
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
            .responseTimeout(Duration.ofSeconds(60))
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
