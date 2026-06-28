package com.chatbot.analytics

import com.chatbot.analytics.dto.ActivitySummaryResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController(
    private val analyticsService: AnalyticsService
) {

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/activity")
    fun getActivitySummary(): ResponseEntity<ActivitySummaryResponse> {
        return ResponseEntity.ok(analyticsService.getActivitySummary())
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/report")
    fun downloadReport(response: HttpServletResponse) {
        analyticsService.generateReport(response)
    }
}
