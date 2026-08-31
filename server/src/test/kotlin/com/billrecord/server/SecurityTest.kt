package com.billrecord.server

import com.auth0.jwt.JWT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.UUID

class SecurityTest {
    private val config = AppConfig(
        port = 8080,
        databaseUrl = "jdbc:postgresql://localhost/test",
        databaseUser = "test",
        databasePassword = "database-password",
        jwtSecret = "a-test-jwt-secret-that-is-at-least-32-characters",
        jwtIssuer = "bill-record-test",
        attachmentPath = "/tmp/attachments",
    )

    @Test
    fun `argon2id hashes and clears caller secrets`() {
        val service = PasswordService()
        val source = "correct horse battery staple".toCharArray()
        val hash = service.hash(source)
        assertTrue(source.all { it == '\u0000' })
        assertTrue(service.verify(hash, "correct horse battery staple".toCharArray()))
        assertFalse(service.verify(hash, "wrong password".toCharArray()))
        assertTrue(hash.startsWith("${'$'}argon2id${'$'}"))
    }

    @Test
    fun `access token binds user device and issuer`() {
        val userId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val (encoded, expires) = TokenService(config).accessToken(userId, deviceId, sessionGeneration = 7)
        val token = JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(config.jwtSecret)).withIssuer(config.jwtIssuer).build().verify(encoded)
        assertEquals(userId.toString(), token.subject)
        assertEquals(deviceId.toString(), token.getClaim("deviceId").asString())
        assertEquals(7L, token.getClaim("sessionGeneration").asLong())
        assertTrue(expires.epochSecond > java.time.Instant.now().epochSecond)
    }

    @Test
    fun `environment config rejects missing secrets`() {
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnvironment(emptyMap()) }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(mapOf("DATABASE_PASSWORD" to "db", "JWT_SECRET" to "short"))
        }
    }
}
