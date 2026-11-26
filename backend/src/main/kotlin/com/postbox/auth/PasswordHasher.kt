package com.postbox.auth

import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.Argon2Factory.Argon2Types

/** Argon2id hashing with OWASP-recommended parameters (64 MiB, 3 iterations, 1 lane). */
class PasswordHasher {

    private val argon2 = Argon2Factory.create(Argon2Types.ARGON2id)

    fun hash(password: String): String =
        argon2.hash(ITERATIONS, MEMORY_KIB, PARALLELISM, password.toCharArray())

    fun verify(hash: String, password: String): Boolean =
        try {
            argon2.verify(hash, password.toCharArray())
        } catch (e: Exception) {
            false
        }

    private companion object {
        const val ITERATIONS = 3
        const val MEMORY_KIB = 65536
        const val PARALLELISM = 1
    }
}
