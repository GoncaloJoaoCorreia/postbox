package com.postbox.api

import com.postbox.AppConfig
import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
import com.postbox.module
import io.ktor.client.request.bearerAuth
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthApiTest {

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

    private suspend fun ApplicationTestBuilder.setupAdmin(
        email: String = "admin@example.com",
        password: String = "admin-password-1",
    ): HttpResponse = postJson("/api/v1/setup", """{"email":"$email","password":"$password"}""")

    private suspend fun ApplicationTestBuilder.login(email: String, password: String): HttpResponse =
        postJson("/api/v1/auth/login", """{"email":"$email","password":"$password"}""")

    private suspend fun ApplicationTestBuilder.loginToken(email: String, password: String): String {
        val response = login(email, password)
        assertEquals(HttpStatusCode.OK, response.status)
        return tokenOf(response)
    }

    private suspend fun ApplicationTestBuilder.adminToken(): String {
        val setup = setupAdmin()
        assertEquals(HttpStatusCode.Created, setup.status)
        return loginToken("admin@example.com", "admin-password-1")
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

    private suspend fun tokenOf(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content

    private suspend fun bodyJson(response: HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject

    private suspend fun errorCode(response: HttpResponse): String =
        bodyJson(response).getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    @Test
    fun `fresh database reports setup pending, first setup creates the admin, then setup refuses`() = withApp("setup-flow") {
        val status = client.get("/api/v1/setup/status")
        assertEquals(HttpStatusCode.OK, status.status)
        assertTrue(bodyJson(status).getValue("setupPending").jsonPrimitive.boolean)

        val created = setupAdmin()
        assertEquals(HttpStatusCode.Created, created.status)
        val admin = bodyJson(created)
        assertEquals("admin@example.com", admin.getValue("email").jsonPrimitive.content)
        assertEquals("ADMIN", admin.getValue("role").jsonPrimitive.content)
        assertTrue(admin.getValue("isActive").jsonPrimitive.boolean)
        assertFalse(admin.containsKey("passwordHash"))

        val statusAfter = client.get("/api/v1/setup/status")
        assertEquals(HttpStatusCode.OK, statusAfter.status)
        assertFalse(bodyJson(statusAfter).getValue("setupPending").jsonPrimitive.boolean)

        val again = setupAdmin(email = "second@example.com", password = "second-password-1")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("setup-already-completed", errorCode(again))
    }

    @Test
    fun `setup rejects malformed email and too-short password without creating an account`() = withApp("setup-validation") {
        val badEmail = setupAdmin(email = "not-an-email")
        assertEquals(HttpStatusCode.BadRequest, badEmail.status)
        assertEquals("invalid-email", errorCode(badEmail))
        val shortPassword = setupAdmin(password = "short")
        assertEquals(HttpStatusCode.BadRequest, shortPassword.status)
        assertEquals("password-too-short", errorCode(shortPassword))

        val status = client.get("/api/v1/setup/status")
        assertTrue(bodyJson(status).getValue("setupPending").jsonPrimitive.boolean)
    }

    @Test
    fun `login returns a JWT and the 401 envelope never reveals whether the email exists`() = withApp("login") {
        assertEquals(HttpStatusCode.Created, setupAdmin().status)

        val ok = login("admin@example.com", "admin-password-1")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertNotNull(tokenOf(ok))

        val wrongPassword = login("admin@example.com", "wrong-password-1")
        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
        assertEquals("invalid-credentials", errorCode(wrongPassword))
        val unknownEmail = login("ghost@example.com", "whatever-123")
        assertEquals(HttpStatusCode.Unauthorized, unknownEmail.status)
        assertEquals(wrongPassword.bodyAsText(), unknownEmail.bodyAsText())
    }

    @Test
    fun `protected routes return the unauthorized envelope without a valid bearer token`() = withApp("protected-401") {
        assertEquals(HttpStatusCode.Created, setupAdmin().status)

        val meNoToken = client.patch("/api/v1/me") {
            contentType(ContentType.Application.Json)
            setBody("""{"newPassword":"new-password-1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, meNoToken.status)
        assertEquals("unauthorized", errorCode(meNoToken))

        val parcelsNoToken = client.get("/api/v1/parcels")
        assertEquals(HttpStatusCode.Unauthorized, parcelsNoToken.status)
        assertEquals("unauthorized", errorCode(parcelsNoToken))

        val adminNoToken = postJson("/api/v1/admin/users", """{"email":"x@example.com","password":"password-123"}""")
        assertEquals(HttpStatusCode.Unauthorized, adminNoToken.status)

        val garbageToken = postJson(
            "/api/v1/admin/users",
            """{"email":"x@example.com","password":"password-123"}""",
            token = "not-a-jwt",
        )
        assertEquals(HttpStatusCode.Unauthorized, garbageToken.status)
        assertEquals("unauthorized", errorCode(garbageToken))
    }

    @Test
    fun `a non-admin token gets 403 admin-required on every admin route`() = withApp("admin-403") {
        val adminToken = adminToken()
        val bobId = createUser(adminToken, "bob@example.com", "bob-password-1")
        val bobToken = loginToken("bob@example.com", "bob-password-1")

        val forbiddenList = getJson("/api/v1/admin/users", bobToken)
        assertEquals(HttpStatusCode.Forbidden, forbiddenList.status)
        assertEquals("admin-required", errorCode(forbiddenList))
        val forbiddenCreate = postJson(
            "/api/v1/admin/users",
            """{"email":"eve@example.com","password":"eve-password-1"}""",
            token = bobToken,
        )
        assertEquals(HttpStatusCode.Forbidden, forbiddenCreate.status)
        assertEquals("admin-required", errorCode(forbiddenCreate))
        val forbiddenPatch = patchJson("/api/v1/admin/users/$bobId", """{"isActive":false}""", bobToken)
        assertEquals(HttpStatusCode.Forbidden, forbiddenPatch.status)
        assertEquals("admin-required", errorCode(forbiddenPatch))
    }

    @Test
    fun `admin lists all accounts and reads the caller via GET me`() = withApp("admin-list-me") {
        val adminToken = adminToken()
        createUser(adminToken, "bob@example.com", "bob-password-1")

        val list = getJson("/api/v1/admin/users", adminToken)
        assertEquals(HttpStatusCode.OK, list.status)
        val users = Json.parseToJsonElement(list.bodyAsText()).jsonArray
        assertEquals(2, users.size)
        assertEquals("admin@example.com", users[0].jsonObject.getValue("email").jsonPrimitive.content)
        assertEquals("bob@example.com", users[1].jsonObject.getValue("email").jsonPrimitive.content)

        val me = getJson("/api/v1/me", adminToken)
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("admin@example.com", bodyJson(me).getValue("email").jsonPrimitive.content)
        assertEquals("ADMIN", bodyJson(me).getValue("role").jsonPrimitive.content)
    }

    @Test
    fun `admin-created user logs in with the initial password and changes it via PATCH me`() = withApp("me-change-password") {
        val adminToken = adminToken()
        createUser(adminToken, "bob@example.com", "bob-password-1")

        val bobToken = loginToken("bob@example.com", "bob-password-1")
        val changed = patchJson("/api/v1/me", """{"newPassword":"bob-new-password-1"}""", token = bobToken)
        assertEquals(HttpStatusCode.NoContent, changed.status)

        val oldLogin = login("bob@example.com", "bob-password-1")
        assertEquals(HttpStatusCode.Unauthorized, oldLogin.status)
        val newLogin = login("bob@example.com", "bob-new-password-1")
        assertEquals(HttpStatusCode.OK, newLogin.status)
    }

    @Test
    fun `deactivated user cannot log in or use a token, and admin reactivates and resets via PATCH`() = withApp("deactivate-reset") {
        val adminToken = adminToken()
        val bobId = createUser(adminToken, "bob@example.com", "bob-password-1")
        val bobToken = loginToken("bob@example.com", "bob-password-1")

        val deactivated = patchJson("/api/v1/admin/users/$bobId", """{"isActive":false}""", adminToken)
        assertEquals(HttpStatusCode.OK, deactivated.status)
        assertFalse(bodyJson(deactivated).getValue("isActive").jsonPrimitive.boolean)

        val deactivatedLogin = login("bob@example.com", "bob-password-1")
        assertEquals(HttpStatusCode.Unauthorized, deactivatedLogin.status)
        val deactivatedMe = patchJson("/api/v1/me", """{"newPassword":"bob-new-password-1"}""", token = bobToken)
        assertEquals(HttpStatusCode.Unauthorized, deactivatedMe.status)

        val reactivated = patchJson("/api/v1/admin/users/$bobId", """{"isActive":true}""", adminToken)
        assertEquals(HttpStatusCode.OK, reactivated.status)
        assertTrue(bodyJson(reactivated).getValue("isActive").jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.OK, login("bob@example.com", "bob-password-1").status)

        val reset = patchJson(
            "/api/v1/admin/users/$bobId",
            """{"newPassword":"bob-reset-password-1"}""",
            adminToken,
        )
        assertEquals(HttpStatusCode.OK, reset.status)
        assertEquals(HttpStatusCode.Unauthorized, login("bob@example.com", "bob-password-1").status)
        assertEquals(HttpStatusCode.OK, login("bob@example.com", "bob-reset-password-1").status)

        val emptyPatch = patchJson("/api/v1/admin/users/$bobId", """{}""", adminToken)
        assertEquals(HttpStatusCode.BadRequest, emptyPatch.status)
        val shortPassword = patchJson("/api/v1/admin/users/$bobId", """{"newPassword":"short"}""", adminToken)
        assertEquals(HttpStatusCode.BadRequest, shortPassword.status)
        assertEquals("password-too-short", errorCode(shortPassword))

        val unknownPatch = patchJson("/api/v1/admin/users/no-such-id", """{"isActive":false}""", adminToken)
        assertEquals(HttpStatusCode.NotFound, unknownPatch.status)
        assertEquals("user-not-found", errorCode(unknownPatch))
    }

    @Test
    fun `admin cannot create a second account with an already-used email`() = withApp("duplicate-email") {
        val adminToken = adminToken()
        val duplicate = postJson(
            "/api/v1/admin/users",
            """{"email":"admin@example.com","password":"another-password-1"}""",
            token = adminToken,
        )
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("email-already-used", errorCode(duplicate))
    }
}
