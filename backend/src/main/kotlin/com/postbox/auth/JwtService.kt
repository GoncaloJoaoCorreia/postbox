package com.postbox.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.postbox.db.UserRecord
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Issues and verifies HS256-signed JWTs carrying `sub` (user id), `email` and `role`. */
class JwtService(secret: String, private val tokenTtl: Duration = 24.hours) {

    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier: JWTVerifier = JWT.require(algorithm).build()

    fun issue(user: UserRecord): String {
        val now = Instant.now()
        return JWT.create()
            .withSubject(user.id)
            .withClaim("email", user.email)
            .withClaim("role", user.role.dbValue)
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(tokenTtl.inWholeSeconds))
            .sign(algorithm)
    }

    fun verify(token: String): DecodedJWT? =
        try {
            verifier.verify(token)
        } catch (e: JWTVerificationException) {
            null
        }
}
