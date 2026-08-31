package com.billrecord.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.billrecord.shared.AuthResponse
import de.mkammerer.argon2.Argon2Factory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

class PasswordService {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    fun hash(value: CharArray): String = try { argon2.hash(3, 65_536, 1, value) } finally { value.fill('\u0000') }
    fun verify(hash: String, value: CharArray): Boolean = try { argon2.verify(hash, value) } finally { value.fill('\u0000') }
}

class TokenService(private val config: AppConfig) {
    val verifier = JWT.require(Algorithm.HMAC256(config.jwtSecret)).withIssuer(config.jwtIssuer).build()

    fun accessToken(userId: UUID, deviceId: UUID, sessionGeneration: Long): Pair<String, Instant> {
        val expires = Instant.now().plus(15, ChronoUnit.MINUTES)
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withSubject(userId.toString())
            .withClaim("deviceId", deviceId.toString())
            .withClaim("sessionGeneration", sessionGeneration)
            .withIssuedAt(Instant.now())
            .withExpiresAt(expires)
            .sign(Algorithm.HMAC256(config.jwtSecret)) to expires
    }

    fun randomToken(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes))
    fun humanCode(length: Int = 10): String = buildString {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val random = SecureRandom()
        repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
