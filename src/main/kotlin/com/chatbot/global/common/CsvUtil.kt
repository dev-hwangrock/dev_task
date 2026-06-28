package com.chatbot.global.common

import jakarta.servlet.http.HttpServletResponse
import java.time.LocalDate

object CsvUtil {

    private const val BOM = "﻿"

    fun writeCsvToResponse(
        rows: List<List<String>>,
        headers: List<String>,
        response: HttpServletResponse
    ) {
        response.contentType = "text/csv; charset=UTF-8"
        response.setHeader(
            "Content-Disposition",
            "attachment; filename=\"report-${LocalDate.now()}.csv\""
        )
        val writer = response.writer
        writer.print(BOM)
        writer.println(headers.joinToString(",") { escapeCsv(it) })
        rows.forEach { row ->
            writer.println(row.joinToString(",") { escapeCsv(it) })
        }
        writer.flush()
    }

    fun writeCsvStream(
        headers: List<String>,
        rows: Sequence<List<String>>,
        response: HttpServletResponse
    ) {
        response.contentType = "text/csv; charset=UTF-8"
        response.setHeader(
            "Content-Disposition",
            "attachment; filename=\"report-${LocalDate.now()}.csv\""
        )
        val writer = response.writer
        writer.print(BOM)
        writer.println(headers.joinToString(",") { escapeCsv(it) })
        var count = 0
        rows.forEach { row ->
            writer.println(row.joinToString(",") { escapeCsv(it) })
            count++
            if (count % 100 == 0) writer.flush()
        }
        writer.flush()
    }

    fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
