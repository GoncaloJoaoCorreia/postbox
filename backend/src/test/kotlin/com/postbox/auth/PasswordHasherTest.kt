package com.postbox.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordHasherTest {

    private val hasher = PasswordHasher()

    @Test
    fun `hash uses the argon2id encoded format and verifies the right password only`() {
        val hash = hasher.hash("password-123")
        assertTrue(hash.startsWith("\$argon2id$"))
        assertTrue(hasher.verify(hash, "password-123"))
        assertFalse(hasher.verify(hash, "password-124"))
    }

    @Test
    fun `verify returns false for a malformed stored hash`() {
        assertFalse(hasher.verify("not-a-hash", "password-123"))
    }
}
