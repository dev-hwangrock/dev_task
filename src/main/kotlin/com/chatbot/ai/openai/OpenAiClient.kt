package com.chatbot.ai.openai

import com.chatbot.ai.AiMessage
import com.chatbot.ai.AiProvider
import com.chatbot.global.exception.CustomException
import com.chatbot.global.exception.ErrorCode
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration

/**
 * OpenAI Chat Completions API 호출 클라이언트.
 * 외부 호출은 성능을 위해 non-blocking WebClient(Reactor Netty)를 사용한다.
 */
@Component
class OpenAiClient(
    @Qualifier("openAiWebClient") private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
) : AiProvider {

    private val log = LoggerFactory.getLogger(OpenAiClient::class.java)

    override fun complete(messages: List<AiMessage>, model: String): String {
        val request = ChatRequest(model = model, messages = messages, stream = false)
        return try {
            webClient.post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { resp ->
                    resp.bodyToMono(String::class.java).defaultIfEmpty("").flatMap { body ->
                        log.error("OpenAI API error: status={}, body={}", resp.statusCode(), body)
                        Mono.error(CustomException(ErrorCode.AI_API_ERROR))
                    }
                }
                .bodyToMono(OpenAiResponse::class.java)
                .map { it.choices.firstOrNull()?.message?.content ?: "" }
                // 타임아웃 계층: connect 5s (WebClientConfig) / response 60s (WebClientConfig) / 전체 120s (여기)
                // 일시 네트워크 IO 오류만 재시도. CustomException(4xx/5xx→onStatus 변환)은 재시도 제외.
                .retryWhen(
                    Retry.backoff(2, Duration.ofSeconds(1))
                        .jitter(0.5)
                        .maxBackoff(Duration.ofSeconds(8))
                        .filter { it !is CustomException },
                )
                .timeout(Duration.ofSeconds(120))
                .block() ?: throw CustomException(ErrorCode.AI_API_ERROR)
        } catch (e: CustomException) {
            throw e
        } catch (e: Exception) {
            log.error("OpenAI complete failed", e)
            throw CustomException(ErrorCode.AI_API_ERROR)
        }
    }

    override fun stream(messages: List<AiMessage>, model: String): Flux<String> {
        val request = ChatRequest(model = model, messages = messages, stream = true)
        return webClient.post()
            .uri("/chat/completions")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                resp.bodyToMono(String::class.java).defaultIfEmpty("").flatMap { body ->
                    log.error("OpenAI stream error: status={}, body={}", resp.statusCode(), body)
                    Mono.error(CustomException(ErrorCode.AI_API_ERROR))
                }
            }
            .bodyToFlux(String::class.java)
            .flatMap { raw ->
                // WebClient SSE 디코더가 "data:" 를 제거해 payload 만 줄 수도, 아닐 수도 있어 방어적으로 처리
                val data = raw.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") {
                    Flux.empty()
                } else {
                    val token = parseToken(data)
                    if (token.isNullOrEmpty()) Flux.empty() else Flux.just(token)
                }
            }
            .retryWhen(
                Retry.backoff(2, Duration.ofSeconds(1))
                    .jitter(0.5)
                    .maxBackoff(Duration.ofSeconds(8))
                    .filter { it !is CustomException }, // 4xx/파싱오류는 재시도 안 함, 일시 네트워크 오류만
            )
            // 타임아웃 계층: connect 5s (WebClientConfig) / response 60s (WebClientConfig) / 전체 120s (여기)
            .timeout(Duration.ofSeconds(120))
            .onErrorMap({ it !is CustomException }) {
                log.error("OpenAI stream failed", it)
                CustomException(ErrorCode.AI_API_ERROR)
            }
    }

    private fun parseToken(json: String): String? =
        try {
            objectMapper.readValue(json, ChatChunk::class.java)
                .choices.firstOrNull()?.delta?.content
        } catch (e: Exception) {
            null
        }

    // ---- OpenAI 요청/응답 DTO ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class ChatRequest(
        val model: String,
        val messages: List<AiMessage>,
        val stream: Boolean = false,
        val temperature: Double = 0.7,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OpenAiResponse(val choices: List<Choice> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Choice(val message: ResponseMessage = ResponseMessage())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ResponseMessage(val role: String = "", val content: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ChatChunk(val choices: List<ChunkChoice> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ChunkChoice(
        val delta: Delta = Delta(),
        @JsonProperty("finish_reason") val finishReason: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Delta(val content: String? = null)
}
