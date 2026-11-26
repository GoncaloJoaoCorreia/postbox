package com.postbox.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserRepositoryTest {

    @Test
    fun `create defaults to an active USER and round-trips all columns`() {
        val db = freshTestDatabase("user-defaults")
        val users = UserRepository(db)

        val created = users.create("alice@example.com", "hash-123", displayName = "Alice")
        assertEquals(UserRole.USER, created.role)
        assertTrue(created.isActive)
        assertEquals(created, users.findById(created.id))
        assertEquals(created, users.findByEmail("alice@example.com"))
        assertNull(users.findByEmail("nobody@example.com"))
    }

    @Test
    fun `admin role is persisted as TEXT and readable`() {
        val db = freshTestDatabase("user-admin-role")
        val users = UserRepository(db)

        val admin = users.create("admin@example.com", "hash", displayName = "Root", role = UserRole.ADMIN)
        assertEquals(UserRole.ADMIN, admin.role)
        assertEquals(UserRole.ADMIN, users.findById(admin.id)?.role)

        val roleText = dbQuery(db) {
            Users.selectAll().where { Users.id eq admin.id }.first()[Users.role]
        }
        assertEquals("ADMIN", roleText)
    }

    @Test
    fun `setActive toggles is_active and reports affected rows`() {
        val db = freshTestDatabase("user-set-active")
        val users = UserRepository(db)
        val user = users.create("bob@example.com", "hash", displayName = "Bob")

        assertEquals(1, users.setActive(user.id, false))
        assertFalse(users.findById(user.id)!!.isActive)
        assertEquals(1, users.setActive(user.id, true))
        assertTrue(users.findById(user.id)!!.isActive)
        assertEquals(0, users.setActive("no-such-id", false))
    }

    @Test
    fun `createFirstAdminIfNoUsers creates exactly one admin and then always returns null`() {
        val db = freshTestDatabase("user-first-admin")
        val users = UserRepository(db)
        assertEquals(0L, users.countAll())

        val first = users.createFirstAdminIfNoUsers("admin@example.com", "hash-1", "Root")
        assertNotNull(first)
        assertEquals(UserRole.ADMIN, first.role)
        assertEquals(1L, users.countAll())

        assertNull(users.createFirstAdminIfNoUsers("other@example.com", "hash-2", "Other"))
        assertEquals(1L, users.countAll())
    }

    @Test
    fun `findCredentialsByEmail returns the stored hash and updatePasswordHash replaces it`() {
        val db = freshTestDatabase("user-credentials")
        val users = UserRepository(db)
        val user = users.create("carol@example.com", "hash-old", displayName = "Carol")

        assertNull(users.findCredentialsByEmail("nobody@example.com"))
        val credentials = users.findCredentialsByEmail("carol@example.com")!!
        assertEquals(user, credentials.user)
        assertEquals("hash-old", credentials.passwordHash)

        assertEquals(1, users.updatePasswordHash(user.id, "hash-new"))
        assertEquals("hash-new", users.findCredentialsByEmail("carol@example.com")!!.passwordHash)
        assertEquals(0, users.updatePasswordHash("no-such-id", "hash-x"))
    }

    @Test
    fun `users table applies schema defaults for role and is_active`() {
        val db = freshTestDatabase("user-schema-defaults")
        dbQuery(db) {
            exec(
                "INSERT INTO users (id, email, password_hash, display_name, created_at) " +
                    "VALUES ('u1', 'x@example.com', 'h', 'X', '2026-11-23T00:00:00.000Z')"
            )
            val row = exec("SELECT role, is_active FROM users WHERE id = 'u1'") { rs ->
                if (rs.next()) rs.getString(1) to rs.getInt(2) else null
            }
            assertEquals("USER" to 1, row)
        }
    }
}
