package com.chatbot.chat

import com.chatbot.chat.dto.ChatCreateRequest
import com.chatbot.chat.dto.ThreadWithChatsResponse
import com.chatbot.global.common.PageResponse
import com.chatbot.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class ChatController(
    private val chatService: ChatService,
) {

    /**
     * POST /api/v1/chats
     *
     * isStreaming=false → 201 Created + ChatResponse (JSON)
     * isStreaming=true  → 200 OK + SseEmitter (text/event-stream)
     *
     * Any 반환으로 Spring MVC 핸들러가 런타임 타입에 맞는 처리기를 선택한다:
     * - SseEmitter → SseEmitterReturnValueHandler
     * - ResponseEntity → HttpEntityMethodProcessor
     */
    @PostMapping("/chats")
    fun createChat(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ChatCreateRequest,
    ): Any {
        return if (request.isStreaming) {
            val emitter = SseEmitter(0L)
            chatService.createChatStreaming(userDetails.id, request, emitter)
            emitter
        } else {
            val response = chatService.createChat(userDetails.id, request)
            ResponseEntity.status(HttpStatus.CREATED).body(response)
        }
    }

    /**
     * GET /api/v1/chats?sort=desc&page=0&size=20
     *
     * MEMBER: 본인 스레드만. ADMIN: 전체.
     * 응답: PageResponse<ThreadWithChatsResponse> (스레드 단위 그룹화)
     */
    @GetMapping("/chats")
    fun getChats(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "desc") sort: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<PageResponse<ThreadWithChatsResponse>> {
        return ResponseEntity.ok(chatService.getChats(userDetails, sort, page, size))
    }

    /**
     * DELETE /api/v1/threads/{threadId}
     *
     * 소유자 전용 (ADMIN도 타인 스레드 불가).
     * 성공 시 204 No Content.
     */
    @DeleteMapping("/threads/{threadId}")
    fun deleteThread(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable threadId: UUID,
    ): ResponseEntity<Void> {
        chatService.deleteThread(userDetails.id, threadId)
        return ResponseEntity.noContent().build()
    }
}
