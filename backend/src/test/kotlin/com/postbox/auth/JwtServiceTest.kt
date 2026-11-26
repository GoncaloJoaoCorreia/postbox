package com.postbox.auth

import com.postbox.db.UserRecord
import com.postbox.db.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JwtServiceTest {

    private val service = JwtService("test-secret")

    private val admin = UserRecord(
        id = "u1",
        email = "admin@example.com",
        displayName = "Admin",
        role = UserRole.ADMIN,
        isActive = true,
        createdAt = "2026-01-01T00:00:00.000Z",
    )

    @Test
    fun `issued token verifies and carries sub, email and role claims`() {
        val token = service.issue(admin)

        val decoded = service.verify(token)!!
        assertEquals("u1", decoded.subject)
        assertEquals("admin@example.com", decoded.getClaim("email").asString())
        assertEquals("ADMIN", decoded.getClaim("role").asString())
        assertNotNull(decoded.expiresAt)
        assertNotNull(decoded.issuedAt)
    }

    @Test
    fun `token signed with another secret does not verify`() {
        val foreign = JwtService("other-secret").issue(admin)
        assertNull(service.verify(foreign))
    }

    @Test
    fun `tampered token does not verify`() {
        val token = service.issue(admin)
        val tampered = token.dropLast(2) + "xx"
        assertNull(service.verify(tampered))
        assertNull(service.verify("not-a-jwt"))
    }
}
