package com.postbox.api

import com.postbox.AppConfig
import com.postbox.db.ParcelRepository
import com.postbox.db.UserRepository
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParcelApiTest {

    private fun testConfig() = AppConfig(
        host = "127.0.0.1",
        port = 0, // testApplication runs in-process and does not bind a real port
        jwtSecret = "test-secret",
        sqlitePath = "build/tmp/test/postbox.db",
        dataDir = "build/tmp/test-data",
    )

    private fun withApp(name: String, block: suspend ApplicationTestBuilder.(Database) -> Unit) =
        testApplication {
            val db = freshTestDatabase(name)
            try {
                application { module(testConfig(), db) }
                block(db)
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

    private suspend fun ApplicationTestBuilder.adminToken(): String {
        assertEquals(HttpStatusCode.Created, postJson("/api/v1/setup", """{"email":"admin@example.com","password":"admin-password-1"}""").status)
        val login = postJson("/api/v1/auth/login", """{"email":"admin@example.com","password":"admin-password-1"}""")
        assertEquals(HttpStatusCode.OK, login.status)
        return bodyJson(login).getValue("token").jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.userToken(adminToken: String, email: String, password: String): String {
        val created = postJson(
            "/api/v1/admin/users",
            """{"email":"$email","password":"$password"}""",
            token = adminToken,
        )
        assertEquals(HttpStatusCode.Created, created.status)
        val login = postJson("/api/v1/auth/login", """{"email":"$email","password":"$password"}""")
        assertEquals(HttpStatusCode.OK, login.status)
        return bodyJson(login).getValue("token").jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.createParcel(token: String, body: String): HttpResponse =
        postJson("/api/v1/parcels", body, token)

    private suspend fun bodyJson(response: HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject

    private suspend fun bodyJsonArray(response: HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonArray

    private suspend fun errorCode(response: HttpResponse): String =
        bodyJson(response).getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.createParcelAndGetId(token: String, trackingNumber: String): String {
        val response = createParcel(token, """{"trackingNumber":"$trackingNumber"}""")
        assertEquals(HttpStatusCode.Created, response.status)
        return bodyJson(response).getValue("id").jsonPrimitive.content
    }

    @Test
    fun `parcel routes require a bearer token with the unauthorized envelope`() = withApp("parcels-401") {
        assertEquals(HttpStatusCode.Created, postJson("/api/v1/setup", """{"email":"a@example.com","password":"password-1"}""").status)

        val list = client.get("/api/v1/parcels")
        assertEquals(HttpStatusCode.Unauthorized, list.status)
        assertEquals("unauthorized", errorCode(list))

        val create = postJson("/api/v1/parcels", """{"trackingNumber":"1Z999AA10123456784"}""")
        assertEquals(HttpStatusCode.Unauthorized, create.status)

        val detect = postJson("/api/v1/parcels/detect", """{"trackingNumber":"1Z999AA10123456784"}""")
        assertEquals(HttpStatusCode.Unauthorized, detect.status)
    }

    @Test
    fun `create parcel trims and validates the tracking number`() = withApp("parcel-validation") { _ ->
        val token = adminToken()

        val badChars = createParcel(token, """{"trackingNumber":"bad number!"}""")
        assertEquals(HttpStatusCode.BadRequest, badChars.status)
        assertEquals("invalid-tracking-number", errorCode(badChars))

        val tooLong = createParcel(token, """{"trackingNumber":"${"A".repeat(65)}"}""")
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertEquals("invalid-tracking-number", errorCode(tooLong))

        val blank = createParcel(token, """{"trackingNumber":"   "}""")
        assertEquals(HttpStatusCode.BadRequest, blank.status)

        val padded = createParcel(token, """{"trackingNumber":"  1Z999AA10123456784  "}""")
        assertEquals(HttpStatusCode.Created, padded.status)
        assertEquals("1Z999AA10123456784", bodyJson(padded).getValue("trackingNumber").jsonPrimitive.content)
    }

    @Test
    fun `create parcel rejects unknown carrier and invalid display name`() = withApp("parcel-create-validation") { _ ->
        val token = adminToken()

        val badCarrier = createParcel(token, """{"trackingNumber":"1Z999AA10123456784","carrier":"ups"}""")
        assertEquals(HttpStatusCode.BadRequest, badCarrier.status)
        assertEquals("invalid-carrier", errorCode(badCarrier))

        val badName = createParcel(token, """{"trackingNumber":"1Z999AA10123456784","displayName":"  "}""")
        assertEquals(HttpStatusCode.BadRequest, badName.status)
        assertEquals("invalid-display-name", errorCode(badName))
    }

    @Test
    fun `create list and get parcel round-trip with defaults, carrier and metadata`() = withApp("parcel-roundtrip") { _ ->
        val token = adminToken()
        val created = createParcel(
            token,
            """{"trackingNumber":"1Z999AA10123456784","carrier":"dhl","metadata":{"service":"express"}}""",
        )
        assertEquals(HttpStatusCode.Created, created.status)
        val parcel = bodyJson(created)
        val id = parcel.getValue("id").jsonPrimitive.content
        assertEquals("UNKNOWN", parcel.getValue("status").jsonPrimitive.content)
        assertEquals("1Z999AA10123456784", parcel.getValue("displayName").jsonPrimitive.content)
        assertEquals("dhl", parcel.getValue("carrier").jsonPrimitive.content)
        assertEquals("express", parcel.getValue("metadata").jsonObject.getValue("service").jsonPrimitive.content)
        assertTrue(parcel["archivedAt"] == JsonNull)
        assertTrue(parcel["latestEvent"] == JsonNull)

        val list = getJson("/api/v1/parcels", token)
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(1, bodyJsonArray(list).size)
        assertEquals(id, bodyJsonArray(list)[0].jsonObject.getValue("id").jsonPrimitive.content)

        val fetched = getJson("/api/v1/parcels/$id", token)
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals(id, bodyJson(fetched).getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `list returns own parcels with newest events first`() = withApp("parcel-list-order") { db ->
        val token = adminToken()
        val parcels = ParcelRepository(db)
        val userId = UserRepository(db).findByEmail("admin@example.com")!!.id

        val older = createParcelAndGetId(token, "TRACK-OLDER")
        val newer = createParcelAndGetId(token, "TRACK-NEWER")
        val noEvents = createParcelAndGetId(token, "TRACK-NOEVENTS")
        parcels.addEventForUser(userId, older, "2026-11-25T10:00:00.000Z", "IN_TRANSIT", "scan old", null, "{}")
        parcels.addEventForUser(userId, newer, "2026-11-26T10:00:00.000Z", "OUT_FOR_DELIVERY", "scan new", null, "{}")

        val list = bodyJsonArray(getJson("/api/v1/parcels", token))
        assertEquals(3, list.size)
        assertEquals(newer, list[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(older, list[1].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(noEvents, list[2].jsonObject.getValue("id").jsonPrimitive.content)

        val latest = list[0].jsonObject.getValue("latestEvent").jsonObject
        assertEquals("2026-11-26T10:00:00.000Z", latest.getValue("occurredAt").jsonPrimitive.content)
        assertEquals("scan new", latest.getValue("description").jsonPrimitive.content)
        assertTrue(list[2].jsonObject["latestEvent"] == JsonNull)
    }

    @Test
    fun `patch renames archives and changes the carrier of an own parcel`() = withApp("parcel-patch") { _ ->
        val token = adminToken()
        val id = createParcelAndGetId(token, "1Z999AA10123456784")

        val renamed = patchJson("/api/v1/parcels/$id", """{"displayName":"Shoes"}""", token)
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals("Shoes", bodyJson(renamed).getValue("displayName").jsonPrimitive.content)

        val archived = patchJson("/api/v1/parcels/$id", """{"archived":true}""", token)
        assertEquals(HttpStatusCode.OK, archived.status)
        assertNotNull(bodyJson(archived)["archivedAt"])

        val unarchived = patchJson("/api/v1/parcels/$id", """{"archived":false}""", token)
        assertEquals(HttpStatusCode.OK, unarchived.status)
        assertTrue(bodyJson(unarchived)["archivedAt"] == JsonNull)

        val reCarrier = patchJson("/api/v1/parcels/$id", """{"carrier":"ctt","metadata":{"postcode":"1000-001"}}""", token)
        assertEquals(HttpStatusCode.OK, reCarrier.status)
        assertEquals("ctt", bodyJson(reCarrier).getValue("carrier").jsonPrimitive.content)
        assertEquals("1000-001", bodyJson(reCarrier).getValue("metadata").jsonObject.getValue("postcode").jsonPrimitive.content)

        val badCarrier = patchJson("/api/v1/parcels/$id", """{"carrier":"ups"}""", token)
        assertEquals(HttpStatusCode.BadRequest, badCarrier.status)
        assertEquals("invalid-carrier", errorCode(badCarrier))
    }

    @Test
    fun `delete removes the parcel and later reads return 404`() = withApp("parcel-delete") { _ ->
        val token = adminToken()
        val id = createParcelAndGetId(token, "1Z999AA10123456784")

        val deleted = deleteJson("/api/v1/parcels/$id", token)
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        val fetched = getJson("/api/v1/parcels/$id", token)
        assertEquals(HttpStatusCode.NotFound, fetched.status)
        assertEquals("parcel-not-found", errorCode(fetched))
    }

    @Test
    fun `events endpoint returns the full timeline newest first`() = withApp("parcel-events") { db ->
        val token = adminToken()
        val parcels = ParcelRepository(db)
        val userId = UserRepository(db).findByEmail("admin@example.com")!!.id
        val id = createParcelAndGetId(token, "1Z999AA10123456784")

        parcels.addEventForUser(userId, id, "2026-11-20T10:00:00.000Z", "PENDING", "registered", null, "{}")
        parcels.addEventForUser(userId, id, "2026-11-21T10:00:00.000Z", "IN_TRANSIT", "in transit", "Berlin", "{}")
        parcels.addEventForUser(userId, id, "2026-11-22T10:00:00.000Z", "DELIVERED", "delivered", "Lisbon", "{}")

        val events = bodyJsonArray(getJson("/api/v1/parcels/$id/events", token))
        assertEquals(3, events.size)
        assertEquals("delivered", events[0].jsonObject.getValue("description").jsonPrimitive.content)
        assertEquals("Lisbon", events[0].jsonObject.getValue("location").jsonPrimitive.content)
        assertEquals("in transit", events[1].jsonObject.getValue("description").jsonPrimitive.content)
        assertEquals("registered", events[2].jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `another user's parcel id returns 404 not 403 on every parcel route`() = withApp("parcel-scoping") { _ ->
        val adminToken = adminToken()
        val aliceToken = userToken(adminToken, "alice@example.com", "alice-password-1")
        val bobToken = userToken(adminToken, "bob@example.com", "bob-password-1")

        val id = createParcelAndGetId(aliceToken, "1Z999AA10123456784")

        for (response in listOf(
            getJson("/api/v1/parcels/$id", bobToken),
            patchJson("/api/v1/parcels/$id", """{"displayName":"hacked"}""", bobToken),
            deleteJson("/api/v1/parcels/$id", bobToken),
            getJson("/api/v1/parcels/$id/events", bobToken),
            postJson("/api/v1/parcels/$id/refresh", "", bobToken),
        )) {
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("parcel-not-found", errorCode(response))
        }

        assertEquals(0, bodyJsonArray(getJson("/api/v1/parcels", bobToken)).size)
        assertEquals(HttpStatusCode.OK, getJson("/api/v1/parcels/$id", aliceToken).status)
    }

    @Test
    fun `detect responds 501 not_implemented until the detection service lands`() = withApp("detect-501") { _ ->
        val token = adminToken()

        val response = postJson("/api/v1/parcels/detect", """{"trackingNumber":"1Z999AA10123456784"}""", token)
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        assertEquals("not_implemented", errorCode(response))

        val invalid = postJson("/api/v1/parcels/detect", """{"trackingNumber":"no!"}""", token)
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("invalid-tracking-number", errorCode(invalid))
    }

    @Test
    fun `refresh responds 501 for an own parcel and 404 for another user's parcel`() = withApp("refresh-501") { _ ->
        val adminToken = adminToken()
        val aliceToken = userToken(adminToken, "alice@example.com", "alice-password-1")
        val bobToken = userToken(adminToken, "bob@example.com", "bob-password-1")
        val id = createParcelAndGetId(aliceToken, "1Z999AA10123456784")

        val own = postJson("/api/v1/parcels/$id/refresh", "", aliceToken)
        assertEquals(HttpStatusCode.NotImplemented, own.status)
        assertEquals("not_implemented", errorCode(own))

        val foreign = postJson("/api/v1/parcels/$id/refresh", "", bobToken)
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals("parcel-not-found", errorCode(foreign))
    }
}
