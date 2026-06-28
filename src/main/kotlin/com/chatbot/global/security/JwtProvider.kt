package com.chatbot.global.security

import com.chatbot.domain.user.Role
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import java.util.Date
import java.util.UUID

class JwtProvider(private val jwtProperties: JwtProperties) {

    private val signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secret))

    fun generateToken(userId: UUID, role: Role): String {
        val now = Date()
        val expiration = Date(now.time + jwtProperties.expirationMs)
        return Jwts.builder()
            .subject(userId.toString())
            .claim("role", role.name)
            .issuedAt(now)
            .expiration(expiration)
            .signWith(signingKey)
            .compact()
    }

    fun getUserId(token: String): UUID {
        return UUID.fromString(parseClaims(token).subject)
    }

    fun getRole(token: String): Role {
        return Role.valueOf(parseClaims(token).get("role", String::class.java))
    }

    fun validate(token: String): Boolean {
        return try {
            parseClaims(token)
            true
        } catch (e: JwtException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun parseClaims(token: String) =
        Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload
}
