package com.postbox.carrier

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * DPD adapter, per-country and PT-first (researched in G-71, `docs/carriers/dpd.md`):
 * DPD has no unified public tracking API, so the endpoint is selected per country via
 * the optional `country` parcel metadata key (default `PT`, declared in
 * [requiredMetadata] so it flows through the generic metadata mechanism):
 *
 * - `PT` — the server-rendered Drupal tracker at `tracking.dpd.pt`, no auth:
 *   `GET {ptBaseUrl}/pt?reference=<n>`. Not found is the `.current-status-notfound`
 *   notice; a known number with no scans yet is an empty history table (PENDING, not
 *   NotFound); a 404/redirect or a page without the tracker's reference label is
 *   indeterminate (bot check possible) and degrades to [CarrierError.CarrierUnavailable].
 * - `NL` (and any other DPDgroup BU) — the anonymous myDPD "guest" JSON API on the
 *   group host: `POST {groupBaseUrl}/concept/webservice/v10/parcels/details/<n>` with
 *   a Bearer guest token from the public Firebase installations → remote-config →
 *   client-credentials chain (all values public in the myDPD app bundle; the Firebase
 *   API key and Android cert SHA-1 are supplied via config, never hardcoded). The API
 *   is group-wide, so a number is claimed only when `status.countryCode` matches the
 *   adapter's country — otherwise [CarrierError.NotFound] to avoid double-claiming
 *   cross-border parcels. 404 or 400 `PARCEL_NOT_FOUND` is NotFound; 429 is
 *   [CarrierError.RateLimited]; 401 refreshes the guest token once; anything else
 *   non-2xx or a transport/parse failure degrades to [CarrierError.CarrierUnavailable]
 *   with a logged raw sample.
 *
 * Status normalization: the PT page's scan codes (`Código` column) and the guest API's
 * milestone `description` / scan `eventType` vocabularies map onto [TrackingStatus];
 * notice-style codes keep the stage of the previous scan, `COL` is acceptance only
 * when nothing moved the parcel before, and a `POD` after a return was ordered reads
 * as returned. Short PT/NL status labels get a best-effort English gloss appended while
 * the original wording stays verbatim in the event description. A 14-digit parcel
 * number with an optional MOD 37,36 check character is looked up by its 14 digits.
 */
class DpdAdapter(
    private val client: HttpClient,
    private val rateLimiter: CarrierRateLimiter,
    private val ptBaseUrl: String = DEFAULT_PT_BASE_URL,
    private val groupBaseUrl: String = DEFAULT_GROUP_BASE_URL,
    private val firebaseApiKey: String? = null,
    private val firebaseAndroidCert: String? = null,
) : CarrierAdapter {

    override val carrier: CarrierId = CarrierId.DPD

    override val requiredMetadata: Map<String, MetadataField> = mapOf(
        COUNTRY to MetadataField(
            name = COUNTRY,
            label = "Destination country",
            hint = "DPD subsidiary whose network the parcel is in: PT (default) or NL",
            validationRegex = COUNTRY_REGEX,
        )
    )

    override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
        val code = number.trim()
        val country = resolveCountry(metadata)
        val parcelNumber = if (DPD_PARCEL_NUMBER.matches(code)) code.take(14) else code
        return when (country) {
            "PT" -> trackPt(parcelNumber, code)
            else -> trackGroup(country, parcelNumber, code)
        }
    }

    private fun resolveCountry(metadata: Map<String, String>): String {
        val raw = metadata[COUNTRY]?.trim()?.uppercase().orEmpty()
        if (raw.isEmpty()) return DEFAULT_COUNTRY
        if (!COUNTRY_PATTERN.matches(raw)) {
            throw CarrierError.NeedsMetadata(listOf(requiredMetadata.getValue(COUNTRY)))
        }
        return raw
    }

    private suspend fun trackPt(parcelNumber: String, original: String): TrackingResult {
        rateLimiter.acquire()
        val response = try {
            client.get("$ptBaseUrl$PT_TRACK_PATH") {
                url.parameters.append("reference", parcelNumber)
                header(HttpHeaders.Accept, ContentType.Text.Html.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
        if (!response.status.isSuccess()) {
            throw unavailable(
                "PT tracker answered HTTP ${response.status.value} (indeterminate for the parcel)",
                IllegalStateException("DPD PT HTTP ${response.status.value}"),
                response.readSample(),
            )
        }
        return parsePtPage(original, parcelNumber, response.bodyAsTextCatching())
    }

    private fun parsePtPage(original: String, parcelNumber: String, body: String): TrackingResult {
        if (NOTFOUND_CLASS.containsMatchIn(body)) throw CarrierError.NotFound(original)
        val label = REFERENCE_LABEL.find(body)?.let { stripTags(it.groupValues[1]) }
            ?: throw unavailable("PT tracker page carried no reference label (bot check or indeterminate)", null, body)
        if (!label.contains(parcelNumber)) {
            throw unavailable("PT tracker page named reference '$label', not '$parcelNumber'", null, body)
        }
        var events = ptEvents(parsePtRows(body))
        val currentText = CURRENT_DESCRIPTION.find(body)?.let { stripTags(it.groupValues[1]) }
        val currentDate = CURRENT_DATE.find(body)?.let { stripTags(it.groupValues[1]) }
        if (events.isEmpty() && !currentText.isNullOrBlank()) {
            val at = currentDate?.let { parseWallClock(it, PT_ZONE) }
            if (at != null) {
                events = listOf(
                    TrackingEvent(
                        occurredAt = at,
                        status = ptWordingStatus(currentText) ?: TrackingStatus.UNKNOWN,
                        description = currentText,
                        location = null,
                    )
                )
            }
        }
        val status = currentText?.let(::ptWordingStatus)
            ?: events.maxByOrNull { it.occurredAt }?.status
            ?: TrackingStatus.PENDING
        return TrackingResult(status = status, eta = null, carrierService = null, events = events)
    }

    private fun parsePtRows(body: String): List<PtRow> {
        val tbody = TABLE_BODY.find(body)?.groupValues?.get(1) ?: return emptyList()
        return TABLE_ROW.findAll(tbody).mapNotNull { row ->
            val cells = TABLE_CELL.findAll(row.groupValues[1]).map { stripTags(it.groupValues[1]) }.toList()
            if (cells.size < 5) return@mapNotNull null
            val at = parseWallClock("${cells[1]} ${cells[2]}", PT_ZONE) ?: return@mapNotNull null
            PtRow(
                occurredAt = at,
                code = cells[0].uppercase(),
                text = cells[3].ifBlank { cells[0] },
                comment = cells[4],
            )
        }.toList()
    }

    private fun ptEvents(rows: List<PtRow>): List<TrackingEvent> {
        var stage = TrackingStatus.PENDING
        var returnOrdered = false
        return rows.sortedBy { it.occurredAt }.map { row ->
            val status = when {
                row.code in PT_NOTICE_CODES -> stage
                row.code == "COL" && stage != TrackingStatus.PENDING -> stage
                row.code == "POD" && returnOrdered -> TrackingStatus.EXCEPTION
                row.code in PT_RETURN_ORDERED_CODES -> {
                    returnOrdered = true
                    TrackingStatus.EXCEPTION
                }
                else -> PT_CODE_STATUS[row.code] ?: stage
            }
            stage = status
            TrackingEvent(
                occurredAt = row.occurredAt,
                status = status,
                description = row.toDescription(),
                location = null,
            )
        }
    }

    private fun PtRow.toDescription(): String {
        val gloss = PT_CODE_GLOSS[code]
        val base = if (gloss != null) "$text ($gloss)" else text
        return if (comment.isNotBlank()) "$base — $comment" else base
    }

    private fun ptWordingStatus(wording: String): TrackingStatus? {
        val w = wording.uppercase()
        return when {
            "ENTREGUE" in w -> TrackingStatus.DELIVERED
            "DISTRIBUI" in w -> TrackingStatus.OUT_FOR_DELIVERY
            "AUSENTE" in w -> TrackingStatus.EXCEPTION
            else -> null
        }
    }

    private suspend fun trackGroup(country: String, parcelNumber: String, original: String): TrackingResult {
        if (firebaseApiKey == null || firebaseAndroidCert == null) {
            throw CarrierError.CarrierUnavailable(
                IllegalStateException(
                    "DPD $country guest API needs DPD_FIREBASE_API_KEY and DPD_FIREBASE_ANDROID_CERT configured"
                )
            )
        }
        val businessUnit = BUSINESS_UNITS.getValue(country)
        var token = guestToken()
        var response = postParcelDetails(parcelNumber, businessUnit, token)
        if (response.status == HttpStatusCode.Unauthorized) {
            token = guestToken(forceRefresh = true)
            response = postParcelDetails(parcelNumber, businessUnit, token)
        }
        return when {
            response.status == HttpStatusCode.NotFound -> throw CarrierError.NotFound(original)
            response.status == HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds
                    ?: DEFAULT_RETRY_AFTER
                throw CarrierError.RateLimited(retryAfter)
            }
            response.status.value == 400 -> {
                val body = response.bodyAsTextCatching()
                val exceptionType = try {
                    dpdJson.decodeFromString<DpdErrorBody>(body).exceptionType
                } catch (e: Exception) {
                    null
                }
                if (exceptionType == "PARCEL_NOT_FOUND") throw CarrierError.NotFound(original)
                throw unavailable("guest API answered 400 $exceptionType (inconclusive)", null, body)
            }
            !response.status.isSuccess() -> throw unavailable(
                "guest API answered HTTP ${response.status.value}",
                IllegalStateException("DPD guest API HTTP ${response.status.value}"),
                response.readSample(),
            )
            else -> parseGroupPage(original, country, response.bodyAsTextCatching())
        }
    }

    private fun parseGroupPage(original: String, country: String, body: String): TrackingResult {
        val payload = try {
            dpdJson.decodeFromString<DpdParcelDetails>(body)
        } catch (e: Exception) {
            throw unavailable("unparseable guest API body", e, body)
        }
        val statusCountry = payload.status?.countryCode
        if (statusCountry == null || !statusCountry.equals(country, ignoreCase = true)) {
            throw CarrierError.NotFound(original)
        }
        val zone = ZoneId.of(COUNTRY_ZONE.getValue(country))
        val events = when {
            !payload.parcelEvents.isNullOrEmpty() -> payload.parcelEvents.toScanEvents(zone)
            else -> payload.parcelHistory.orEmpty().toHistoryEvents(zone)
        }
        val status = mapGroupDescription(payload.status?.description)
            ?: events.maxByOrNull { it.occurredAt }?.status
            ?: TrackingStatus.PENDING
        return TrackingResult(
            status = status,
            eta = null,
            carrierService = payload.status?.deliveryType?.takeIf { it.isNotBlank() },
            events = events,
        )
    }

    private fun List<DpdScanEvent>.toScanEvents(zone: ZoneId): List<TrackingEvent> {
        val parsed = mapNotNull { event -> event.occurredAt(zone)?.let { event to it } }
            .sortedBy { it.second }
        var stage = TrackingStatus.PENDING
        return parsed.map { (event, at) ->
            val status = NL_SCAN_STATUS[event.eventType?.uppercase()] ?: stage
            stage = status
            TrackingEvent(
                occurredAt = at,
                status = status,
                description = event.translation?.takeIf { it.isNotBlank() }
                    ?: event.eventTypeText?.takeIf { it.isNotBlank() }
                    ?: event.eventType?.takeIf { it.isNotBlank() }
                    ?: "DPD tracking event",
                location = event.location(),
            )
        }
    }

    private fun List<DpdHistoryEntry>.toHistoryEvents(zone: ZoneId): List<TrackingEvent> {
        val parsed = mapNotNull { entry -> entry.occurredAt(zone)?.let { entry to it } }
            .sortedBy { it.second }
        var stage = TrackingStatus.PENDING
        return parsed.map { (entry, at) ->
            val status = mapGroupDescription(entry.description) ?: stage
            stage = status
            TrackingEvent(
                occurredAt = at,
                status = status,
                description = entry.description?.takeIf { it.isNotBlank() } ?: "DPD tracking event",
                location = entry.location(),
            )
        }
    }

    private fun mapGroupDescription(description: String?): TrackingStatus? = when (description) {
        "ORDER_CREATED" -> TrackingStatus.PENDING
        "PARCEL_HANDED", "IN_TRANSIT", "AT_DELIVERY_CENTER" -> TrackingStatus.IN_TRANSIT
        "PARCEL_OUT_FOR_DELIVERY", "AVAILABLE_FOR_COLLECTION" -> TrackingStatus.OUT_FOR_DELIVERY
        "UNSUCCESSFUL_DELIVERY_ATTEMPT", "RETURN_TO_SENDER" -> TrackingStatus.EXCEPTION
        "DELIVERED" -> TrackingStatus.DELIVERED
        else -> null
    }

    private suspend fun postParcelDetails(parcelNumber: String, businessUnit: String, token: String): HttpResponse {
        rateLimiter.acquire()
        return try {
            client.post("$groupBaseUrl$PARCEL_DETAILS_PATH/$parcelNumber") {
                url.parameters.append("parcelType", "INCOMING")
                url.parameters.append("businessUnit", businessUnit)
                url.parameters.append("lang", "en")
                url.parameters.append("continueWithoutVerification", "true")
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.UserAgent, MYDPD_USER_AGENT)
                setBody(TextContent("", ContentType.Application.Json.withCharset(Charsets.UTF_8), null))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
    }

    private val tokenMutex = Mutex()
    private var cachedGuestToken: String? = null
    private var cachedGuestTokenExpiresAt: kotlin.time.Instant = kotlin.time.Instant.DISTANT_PAST

    private suspend fun guestToken(forceRefresh: Boolean = false): String = tokenMutex.withLock {
        if (!forceRefresh) {
            cachedGuestToken?.let { token ->
                if (Clock.System.now() < cachedGuestTokenExpiresAt) return token
            }
        }
        val fid = newFid()
        val firebaseHeaders = mapOf(
            "X-Goog-Api-Key" to firebaseApiKey.orEmpty(),
            "X-Android-Package" to MYDPD_PACKAGE,
            "X-Android-Cert" to firebaseAndroidCert.orEmpty(),
        )
        val installation = postJson(FIREBASE_INSTALLATIONS_URL, firebaseHeaders, buildJsonObject {
            put("fid", fid)
            put("appId", MYDPD_APP_ID)
            put("authVersion", "FIS_v2")
            put("sdkVersion", "a:18.0.0")
        }.toString()).jsonBody<FirebaseInstallation>()
        val installationToken = installation.authToken?.token
            ?: throw unavailable("firebase installations carried no authToken", null, "")
        val remoteConfig = postJson(
            FIREBASE_REMOTE_CONFIG_URL,
            firebaseHeaders + ("X-Goog-Firebase-Installations-Auth" to installationToken),
            buildJsonObject {
                put("appId", MYDPD_APP_ID)
                put("appInstanceId", fid)
                put("appInstanceIdToken", installationToken)
                put("languageCode", "en-US")
                put("countryCode", "NL")
                put("platformVersion", "36")
                put("appVersion", MYDPD_VERSION)
                put("packageName", MYDPD_PACKAGE)
                put("sdkVersion", "22.1.2")
                put("analyticsUserProperties", buildJsonObject { })
            }.toString(),
        ).jsonBody<FirebaseRemoteConfig>()
        val basicCredential = remoteConfig.entries?.get("basic_dpd_token")
            ?: throw unavailable("remote config carried no basic_dpd_token", null, "")
        val oauth = postJson(
            "$groupBaseUrl$OAUTH_TOKEN_PATH?grant_type=client_credentials",
            mapOf(
                HttpHeaders.Authorization to "Basic $basicCredential",
                HttpHeaders.UserAgent to MYDPD_USER_AGENT,
            ),
            "",
        ).jsonBody<DpdOAuthToken>()
        val accessToken = oauth.accessToken
            ?: throw unavailable("oauth token response carried no access_token", null, "")
        cachedGuestTokenExpiresAt = Clock.System.now() + ((oauth.expiresIn ?: OAUTH_DEFAULT_EXPIRES_IN) - 60).seconds
        cachedGuestToken = accessToken
        return accessToken
    }

    private suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse {
        rateLimiter.acquire()
        return try {
            client.post(url) {
                headers.forEach { (key, value) -> header(key, value) }
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(TextContent(body, ContentType.Application.Json.withCharset(Charsets.UTF_8), null))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
    }

    private suspend inline fun <reified T> HttpResponse.jsonBody(): T {
        if (!status.isSuccess()) {
            throw unavailable(
                "answered HTTP ${status.value}",
                IllegalStateException("DPD guest API HTTP ${status.value}"),
                readSample(),
            )
        }
        val body = bodyAsTextCatching()
        return try {
            dpdJson.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw unavailable("unparseable body", e, body)
        }
    }

    private fun newFid(): String {
        val bytes = ByteArray(17).also { SecureRandom().nextBytes(it) }
        bytes[0] = (0x70 or (bytes[0].toInt() and 0x0f)).toByte()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(22)
    }

    private fun parseWallClock(dateTime: String, zone: ZoneId): Instant? = try {
        LocalDateTime.parse(dateTime.trim(), PT_WALL_CLOCK_FORMAT).atZone(zone).toInstant()
    } catch (ignored: DateTimeParseException) {
        null
    }

    private fun parseZonedClock(dateTime: String, zoneId: String?, fallbackZone: ZoneId): Instant? = try {
        val zone = try {
            ZoneId.of(zoneId.orEmpty())
        } catch (ignored: Exception) {
            fallbackZone
        }
        LocalDateTime.parse(dateTime.trim()).atZone(zone).toInstant()
    } catch (ignored: DateTimeParseException) {
        null
    }

    private fun DpdScanEvent.occurredAt(zone: ZoneId): Instant? {
        if (date.isNullOrBlank() || time.isNullOrBlank()) return null
        return parseZonedClock("$date T$time".replace(" ", ""), null, zone)
    }

    private fun DpdHistoryEntry.occurredAt(zone: ZoneId): Instant? {
        val dateTime = eventDateAndTime ?: return null
        return parseZonedClock(dateTime, zoneId, zone)
    }

    private fun DpdScanEvent.location(): String? {
        val country = country?.takeIf { it.isNotBlank() && it != "UNDEFINED" }
        return listOfNotNull(city?.takeIf { it.isNotBlank() }, country)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }

    private fun DpdHistoryEntry.location(): String? {
        val country = countryCode?.takeIf { it.isNotBlank() }
        return listOfNotNull(city?.takeIf { it.isNotBlank() }, country)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }

    private suspend fun HttpResponse.readSample(): String = try {
        bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ""
    }

    private suspend fun HttpResponse.bodyAsTextCatching(): String = try {
        bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw CarrierError.CarrierUnavailable(e)
    }

    private fun unavailable(reason: String, cause: Throwable?, rawSample: String): CarrierError.CarrierUnavailable {
        log.warn("DPD adapter degrading to CarrierUnavailable ($reason); raw sample: {}", rawSample.take(RAW_SAMPLE_LENGTH))
        return CarrierError.CarrierUnavailable(cause ?: IllegalStateException("DPD $reason"))
    }

    companion object {
        const val DEFAULT_PT_BASE_URL = "https://tracking.dpd.pt"
        const val DEFAULT_GROUP_BASE_URL = "https://www.dpdgroup.com"
        const val COUNTRY = "country"
        const val COUNTRY_REGEX = "^(PT|NL)$"
        const val DEFAULT_COUNTRY = "PT"

        private const val PT_TRACK_PATH = "/pt"
        private const val PARCEL_DETAILS_PATH = "/concept/webservice/v10/parcels/details"
        private const val OAUTH_TOKEN_PATH = "/concept/webservice/oauth/token"
        private const val FIREBASE_INSTALLATIONS_URL =
            "https://firebaseinstallations.googleapis.com/v1/projects/consignee-portal/installations"
        private const val FIREBASE_REMOTE_CONFIG_URL =
            "https://firebaseremoteconfig.googleapis.com/v1/projects/959401347543/namespaces/firebase:fetch"
        private const val MYDPD_APP_ID = "1:959401347543:android:8d1a84133332291109e392"
        private const val MYDPD_PACKAGE = "com.dpdgroup.chatbot.lemny.prod"
        private const val MYDPD_VERSION = "3.79.14"
        private const val MYDPD_USER_AGENT = "myDPD/3.79.14 (Android)"
        private const val OAUTH_DEFAULT_EXPIRES_IN = 3600
        private const val RAW_SAMPLE_LENGTH = 1000
        private val DEFAULT_RETRY_AFTER: Duration = 60.seconds
        private val DPD_PARCEL_NUMBER = Regex("""^\d{14}([0-9A-Z])?$""")
        private val COUNTRY_PATTERN = Regex(COUNTRY_REGEX)
        private val BUSINESS_UNITS = mapOf("NL" to "DPD-NL")
        private val COUNTRY_ZONE = mapOf("PT" to "Europe/Lisbon", "NL" to "Europe/Amsterdam")
        private val PT_ZONE = ZoneId.of(COUNTRY_ZONE.getValue("PT"))
        private val PT_WALL_CLOCK_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
        private val NOTFOUND_CLASS = Regex("""class="[^"]*current-status-notfound""")
        private val REFERENCE_LABEL = Regex("""(?s)<div class="reference-label">\s*<span>(.*?)</span>""")
        private val CURRENT_DESCRIPTION = Regex("""(?s)<span class="current-description">(.*?)</span>""")
        private val CURRENT_DATE = Regex("""(?s)<span class="current-date">(.*?)</span>""")
        private val TABLE_BODY = Regex("""(?s)<tbody>(.*?)</tbody>""")
        private val TABLE_ROW = Regex("""(?s)<tr[^>]*>(.*?)</tr>""")
        private val TABLE_CELL = Regex("""(?s)<td[^>]*>(.*?)</td>""")
        private val TAG = Regex("<[^>]+>")
        private val PT_NOTICE_CODES = setOf("700", "706", "709", "714", "741", "743")
        private val PT_RETURN_ORDERED_CODES = setOf("008", "424")
        private val PT_CODE_STATUS = mapOf(
            "007" to TrackingStatus.IN_TRANSIT,
            "012" to TrackingStatus.IN_TRANSIT,
            "COL" to TrackingStatus.IN_TRANSIT,
            "INB" to TrackingStatus.IN_TRANSIT,
            "HUB" to TrackingStatus.IN_TRANSIT,
            "PEC" to TrackingStatus.IN_TRANSIT,
            "402" to TrackingStatus.IN_TRANSIT,
            "403" to TrackingStatus.IN_TRANSIT,
            "410" to TrackingStatus.IN_TRANSIT,
            "723" to TrackingStatus.IN_TRANSIT,
            "509" to TrackingStatus.IN_TRANSIT,
            "001" to TrackingStatus.IN_TRANSIT,
            "002" to TrackingStatus.IN_TRANSIT,
            "OFD" to TrackingStatus.OUT_FOR_DELIVERY,
            "500" to TrackingStatus.EXCEPTION,
            "501" to TrackingStatus.EXCEPTION,
            "506" to TrackingStatus.EXCEPTION,
            "508" to TrackingStatus.EXCEPTION,
            "510" to TrackingStatus.EXCEPTION,
            "521" to TrackingStatus.EXCEPTION,
            "008" to TrackingStatus.EXCEPTION,
            "424" to TrackingStatus.EXCEPTION,
            "611" to TrackingStatus.EXCEPTION,
            "POD" to TrackingStatus.DELIVERED,
        )
        private val PT_CODE_GLOSS = mapOf(
            "007" to "dropped off at pickup point",
            "012" to "handed to DPD at pickup point",
            "COL" to "collected by courier",
            "INB" to "depot inbound",
            "HUB" to "hub transit",
            "PEC" to "depot inbound",
            "402" to "back at depot",
            "403" to "back at depot after failed attempt",
            "410" to "held at depot",
            "723" to "address corrected after failed attempt",
            "509" to "delivery rescheduled",
            "001" to "at pickup point",
            "002" to "in locker",
            "OFD" to "out for delivery",
            "500" to "failed attempt: bad or unknown address",
            "501" to "failed attempt: incomplete address",
            "506" to "failed attempt: recipient absent",
            "508" to "failed attempt: closed for holidays",
            "510" to "failed attempt",
            "521" to "failed attempt: locker or point full",
            "008" to "not collected in time, return to sender ordered",
            "424" to "return to sender ordered",
            "611" to "returned to sender",
            "POD" to "delivered",
        )
        private val NL_SCAN_STATUS = mapOf(
            "CNP" to TrackingStatus.PENDING,
            "CNA" to TrackingStatus.PENDING,
            "CRY" to TrackingStatus.PENDING,
            "PKY" to TrackingStatus.IN_TRANSIT,
            "ORI" to TrackingStatus.IN_TRANSIT,
            "CCO" to TrackingStatus.IN_TRANSIT,
            "SPL" to TrackingStatus.IN_TRANSIT,
            "LHI" to TrackingStatus.IN_TRANSIT,
            "HUI" to TrackingStatus.IN_TRANSIT,
            "HUS" to TrackingStatus.IN_TRANSIT,
            "DLI" to TrackingStatus.IN_TRANSIT,
            "DLS" to TrackingStatus.IN_TRANSIT,
            "DLQ" to TrackingStatus.IN_TRANSIT,
            "DLO" to TrackingStatus.OUT_FOR_DELIVERY,
            "DOPKY" to TrackingStatus.OUT_FOR_DELIVERY,
            "DODEH" to TrackingStatus.OUT_FOR_DELIVERY,
            "DODEI" to TrackingStatus.OUT_FOR_DELIVERY,
            "DEY" to TrackingStatus.DELIVERED,
            "DEYY" to TrackingStatus.DELIVERED,
            "DODEY" to TrackingStatus.DELIVERED,
            "DEX" to TrackingStatus.EXCEPTION,
            "DLR" to TrackingStatus.EXCEPTION,
            "DLW" to TrackingStatus.EXCEPTION,
        )
        private val dpdJson = Json { ignoreUnknownKeys = true }
        private val log = LoggerFactory.getLogger(DpdAdapter::class.java)
    }
}

private data class PtRow(
    val occurredAt: Instant,
    val code: String,
    val text: String,
    val comment: String,
)

private fun stripTags(html: String): String = TAG_RE.replace(html, " ")
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&amp;", "&")
    .replace(Regex("\\s+"), " ")
    .trim()

private val TAG_RE = Regex("<[^>]+>")

@Serializable
private data class DpdParcelDetails(
    val status: DpdStatus? = null,
    val parcelHistory: List<DpdHistoryEntry>? = null,
    val parcelEvents: List<DpdScanEvent>? = null,
)

@Serializable
private data class DpdStatus(
    val status: Int? = null,
    val description: String? = null,
    val deliveryType: String? = null,
    @SerialName("eventDateAndTime") val eventDateAndTime: String? = null,
    @SerialName("eventDateAndTimeZoneId") val zoneId: String? = null,
    val city: String? = null,
    val countryCode: String? = null,
)

@Serializable
private data class DpdHistoryEntry(
    val description: String? = null,
    @SerialName("eventDateAndTime") val eventDateAndTime: String? = null,
    @SerialName("eventDateAndTimeZoneId") val zoneId: String? = null,
    val city: String? = null,
    val countryCode: String? = null,
)

@Serializable
private data class DpdScanEvent(
    val date: String? = null,
    val time: String? = null,
    val eventType: String? = null,
    val eventTypeText: String? = null,
    val translation: String? = null,
    val country: String? = null,
    val city: String? = null,
)

@Serializable
private data class DpdErrorBody(
    val exceptionType: String? = null,
    val message: String? = null,
)

@Serializable
private data class FirebaseInstallation(
    val authToken: FirebaseAuthToken? = null,
)

@Serializable
private data class FirebaseAuthToken(
    val token: String? = null,
    val expiresIn: String? = null,
)

@Serializable
private data class FirebaseRemoteConfig(
    val entries: Map<String, String>? = null,
)

@Serializable
private data class DpdOAuthToken(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
)
