package com.postbox.api

import com.postbox.AppConfig
import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
import com.postbox.module
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RefreshTokenApiTest {

    private fun testConfig() = AppConfig(
        host = "127.0.0.1",
        port = 0, // testApplication runs in-process and does not bind a real port
        jwtSecret = "test-secret",
        sqlitePath = "build/tmp/test/postbox.db",
        dataDir = "build/tmp/test-data",
    )

    private fun withApp(name: String, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            val db = freshTestDatabase(name)
            try {
                application { module(testConfig(), db) }
                block()
            } finally {
                closeDatabase(db)
            }
        }

    private suspend fun ApplicationTestBuilder.postJson(
        path: String,
        body: String,
        token: String? = null,
    ): HttpResponse = client.post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        if (token != null) bearerAuth(token)
    }

    private suspend fun ApplicationTestBuilder.patchJson(
        path: String,
        body: String,
        token: String,
    ): HttpResponse = client.patch(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        bearerAuth(token)
    }

    private suspend fun ApplicationTestBuilder.getJson(path: String, token: String): HttpResponse =
        client.get(path) { bearerAuth(token) }

    private suspend fun ApplicationTestBuilder.deleteJson(path: String, token: String): HttpResponse =
        client.delete(path) { bearerAuth(token) }

    private suspend fun ApplicationTestBuilder.setupAdmin(): HttpResponse =
        postJson("/api/v1/setup", """{"email":"admin@example.com","password":"admin-password-1"}""")

    private suspend fun ApplicationTestBuilder.login(
        email: String,
        password: String,
        deviceName: String? = null,
    ): HttpResponse {
        val device = if (deviceName != null) ""","deviceName":"$deviceName"""" else ""
        return postJson("/api/v1/auth/login", """{"email":"$email","password":"$password"$device}""")
    }

    private suspend fun ApplicationTestBuilder.refresh(refreshToken: String): HttpResponse =
        postJson("/api/v1/auth/refresh", """{"refreshToken":"$refreshToken"}""")

    private data class LoginTokens(val token: String, val refreshToken: String, val sessionId: String)

    private suspend fun ApplicationTestBuilder.loginTokens(
        email: String,
        password: String,
        deviceName: String? = null,
    ): LoginTokens {
        val response = login(email, password, deviceName)
        assertEquals(HttpStatusCode.OK, response.status)
        val body = bodyJson(response)
        return LoginTokens(
            token = body.getValue("token").jsonPrimitive.content,
            refreshToken = body.getValue("refreshToken").jsonPrimitive.content,
            sessionId = body.getValue("sessionId").jsonPrimitive.content,
        )
    }

    private suspend fun ApplicationTestBuilder.adminToken(): String {
        val setup = setupAdmin()
        assertEquals(HttpStatusCode.Created, setup.status)
        return loginTokens("admin@example.com", "admin-password-1").token
    }

    private suspend fun ApplicationTestBuilder.createUser(
        adminToken: String,
        email: String,
        password: String,
    ): String {
        val created = postJson(
            "/api/v1/admin/users",
            """{"email":"$email","password":"$password"}""",
            token = adminToken,
        )
        assertEquals(HttpStatusCode.Created, created.status)
        return bodyJson(created).getValue("id").jsonPrimitive.content
    }

    private suspend fun bodyJson(response: HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject

    private suspend fun errorCode(response: HttpResponse): String =
        bodyJson(response).getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    @Test
    fun `login returns access token, refresh token and session id, and lists the device session`() = withApp("login-session") {
        assertEquals(HttpStatusCode.Created, setupAdmin().status)

        val named = loginTokens("admin@example.com", "admin-password-1", deviceName = "Pixel 9")
        assertNotNull(named.token)
        assertNotNull(named.refreshToken)
        assertNotNull(named.sessionId)

        val defaultNamed = loginTokens("admin@example.com", "admin-password-1")
        val devices = getJson("/api/v1/me/devices", named.token)
        assertEquals(HttpStatusCode.OK, devices.status)
        val sessions = Json.parseToJsonElement(devices.bodyAsText()).jsonArray
        assertEquals(2, sessions.size)
        assertEquals(named.sessionId, sessions[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("Pixel 9", sessions[0].jsonObject.getValue("deviceName").jsonPrimitive.content)
        assertEquals(defaultNamed.sessionId, sessions[1].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("Unknown device", sessions[1].jsonObject.getValue("deviceName").jsonPrimitive.content)
        assertEquals(JsonNull, sessions[0].jsonObject.getValue("revokedAt"))
    }

    @Test
    fun `refresh rotates the token, the new pair works and the old refresh token is rejected`() = withApp("refresh-rotation") {
        assertEquals(HttpStatusCode.Created, setupAdmin().status)
        val first = loginTokens("admin@example.com", "admin-password-1", deviceName = "Pixel 9")

        val rotated = refresh(first.refreshToken)
        assertEquals(HttpStatusCode.OK, rotated.status)
        val rotatedBody = bodyJson(rotated)
        val newToken = rotatedBody.getValue("token").jsonPrimitive.content
        val newRefresh = rotatedBody.getValue("refreshToken").jsonPrimitive.content
        assertEquals(first.sessionId, rotatedBody.getValue("sessionId").jsonPrimitive.content)

        val me = getJson("/api/v1/me", newToken)
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("admin@example.com", bodyJson(me).getValue("email").jsonPrimitive.content)

        val secondRotation = refresh(newRefresh)
        assertEquals(HttpStatusCode.OK, secondRotation.status)
        assertEquals(first.sessionId, bodyJson(secondRotation).getValue("sessionId").jsonPrimitive.content)

        val replayed = refresh(first.refreshToken)
        assertEquals(HttpStatusCode.Unauthorized, replayed.status)
        assertEquals("invalid-refresh-token", errorCode(replayed))
    }

    @Test
    fun `replaying an already-rotated token revokes the whole session`() = withApp("reuse-detection") {
        assertEquals(HttpStatusCode.Created, setupAdmin().status)
        val first = loginTokens("admin@example.com", "admin-password-1")

        val rotated = refresh(first.refreshToken)
        assertEquals(HttpStatusCode.OK, rotated.status)
        val newRefresh = bodyJson(rotated).getValue("refreshToken").jsonPrimitive.content

        val reuse = refresh(first.refreshToken)
        assertEquals(HttpStatusCode.Unauthorized, reuse.status)
        assertEquals("invalid-refresh-token", errorCode(reuse))

        val afterReuse = refresh(newRefresh)
        assertEquals(HttpStatusCode.Unauthorized, afterReuse.status)
        assertEquals("invalid-refresh-token", errorCode(afterReuse))

        val devices = getJson("/api/v1/me/devices", first.token)
        val sessions = Json.parseToJsonElement(devices.bodyAsText()).jsonArray
        assertEquals(1, sessions.size)
        assertNotNull(sessions[0].jsonObject.getValue("revokedAt").jsonPrimitive.content)
    }

    @Test
    fun `deleting a device session revokes it and other users ids answer 404`() = withApp("device-revoke") {
        val adminToken = adminToken()
        createUser(adminToken, "bob@example.com", "bob-password-1")
        val adminLogin = loginTokens("admin@example.com", "admin-password-1", deviceName = "Admin phone")
        val bobLogin = loginTokens("bob@example.com", "bob-password-1", deviceName = "Bob phone")

        val foreign = deleteJson("/api/v1/me/devices/${adminLogin.sessionId}", bobLogin.token)
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals("device-not-found", errorCode(foreign))

        val deleted = deleteJson("/api/v1/me/devices/${bobLogin.sessionId}", bobLogin.token)
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        val revokedRefresh = refresh(bobLogin.refreshToken)
        assertEquals(HttpStatusCode.Unauthorized, revokedRefresh.status)
        assertEquals("invalid-refresh-token", errorCode(revokedRefresh))

        val devices = getJson("/api/v1/me/devices", bobLogin.token)
        val sessions = Json.parseToJsonElement(devices.bodyAsText()).jsonArray
        assertEquals(1, sessions.size)
        assertNotNull(sessions[0].jsonObject.getValue("revokedAt").jsonPrimitive.content)

        val adminStillValid = refresh(adminLogin.refreshToken)
        assertEquals(HttpStatusCode.OK, adminStillValid.status)

        val unknown = deleteJson("/api/v1/me/devices/no-such-id", bobLogin.token)
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("device-not-found", errorCode(unknown))
    }

    @Test
    fun `changing the password revokes all of the caller's sessions`() = withApp("password-change-revokes") {
        assertEquals(HttpStatusCode.Created, setupAdmin().status)
        val first = loginTokens("admin@example.com", "admin-password-1", deviceName = "Phone")
        val second = loginTokens("admin@example.com", "admin-password-1", deviceName = "Tablet")

        val changed = patchJson("/api/v1/me", """{"newPassword":"admin-new-password-1"}""", first.token)
        assertEquals(HttpStatusCode.NoContent, changed.status)

        assertEquals(HttpStatusCode.Unauthorized, refresh(first.refreshToken).status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(second.refreshToken).status)

        val relogin = login("admin@example.com", "admin-new-password-1")
        assertEquals(HttpStatusCode.OK, relogin.status)
        val reloginTokens = bodyJson(relogin)
        assertNotNull(reloginTokens.getValue("refreshToken").jsonPrimitive.content)
    }

    @Test
    fun `admin deactivation revokes the target user's sessions`() = withApp("deactivation-revokes") {
        val adminToken = adminToken()
        val bobId = createUser(adminToken, "bob@example.com", "bob-password-1")
        val bobLogin = loginTokens("bob@example.com", "bob-password-1", deviceName = "Bob phone")

        val deactivated = patchJson("/api/v1/admin/users/$bobId", """{"isActive":false}""", adminToken)
        assertEquals(HttpStatusCode.OK, deactivated.status)

        val revokedRefresh = refresh(bobLogin.refreshToken)
        assertEquals(HttpStatusCode.Unauthorized, revokedRefresh.status)
        assertEquals("invalid-refresh-token", errorCode(revokedRefresh))
    }

    @Test
    fun `an unknown refresh token gets the 401 envelope`() = withApp("unknown-refresh") {
        assertEquals(HttpStatusCode.Created, setupAdmin().status)
        val response = refresh("not-a-real-token")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error = bodyJson(response).getValue("error").jsonObject
        assertEquals("invalid-refresh-token", error.getValue("code").jsonPrimitive.content)
        assertTrue(error.getValue("message").jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun `login rejects an invalid device name with the error envelope`() = withApp("device-name-validation") {
        assertEquals(HttpStatusCode.Created, setupAdmin().status)
        val blank = login("admin@example.com", "admin-password-1", deviceName = "   ")
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals("invalid-device-name", errorCode(blank))
        val tooLong = login("admin@example.com", "admin-password-1", deviceName = "x".repeat(121))
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertEquals("invalid-device-name", errorCode(tooLong))
    }
}
