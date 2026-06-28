package com.chatbot.ai

import reactor.core.publisher.Flux

/**
 * AI 응답 생성 추상화. 구현체 교체(OpenAI/그 외)를 위해 인터페이스로 분리한다.
 */
interface AiProvider {
    /** 비스트리밍: 전체 답변 문자열을 반환한다. */
    fun complete(messages: List<AiMessage>, model: String): String

    /** 스트리밍: 토큰(델타) 청크 스트림을 반환한다. */
    fun stream(messages: List<AiMessage>, model: String): Flux<String>
}
