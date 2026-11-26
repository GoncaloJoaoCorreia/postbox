package com.postbox

import com.postbox.auth.JwtService
import com.postbox.auth.PasswordHasher
import com.postbox.carrier.CarrierAdapter
import com.postbox.carrier.CarrierClientSettings
import com.postbox.carrier.CarrierHttpClients
import com.postbox.carrier.CarrierId
import com.postbox.carrier.CarrierRateLimiter
import com.postbox.carrier.CarrierRegistry
import com.postbox.db.ParcelRepository
import com.postbox.db.UserRepository
import com.postbox.service.DefaultRefreshService
import com.postbox.service.DetectionService
import com.postbox.service.RefreshService
import com.postbox.service.RuleBasedDetectionService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.DependencyKey
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.time.Duration.Companion.milliseconds

internal fun CarrierSettings.toClientSettings() = CarrierClientSettings(
    connectTimeoutMs = connectTimeoutMs,
    socketTimeoutMs = socketTimeoutMs,
    requestTimeoutMs = requestTimeoutMs,
    userAgent = userAgent,
)

internal fun CarrierSettings.toRateLimiter() =
    CarrierRateLimiter(minInterval = minRequestIntervalMs.milliseconds)

fun Application.appDependencies(config: AppConfig, db: Database) {
    dependencies {
        provide { config }
        provide { db }
        provide { UserRepository(resolve()) }
        provide { ParcelRepository(resolve()) }
        provide { PasswordHasher() }
        provide { JwtService(resolve<AppConfig>().jwtSecret) }
        provide<DetectionService> {
            RuleBasedDetectionService(resolve<CarrierRegistry>(), resolve<Map<CarrierId, CarrierRateLimiter>>())
        }
        provide<RefreshService> {
            val appConfig = resolve<AppConfig>()
            DefaultRefreshService(
                parcels = resolve(),
                registry = resolve<CarrierRegistry>(),
                rateLimiters = resolve<Map<CarrierId, CarrierRateLimiter>>(),
                settings = appConfig.refresh,
                probeTimeouts = appConfig.carriers.mapValues { (_, s) -> s.requestTimeoutMs.milliseconds },
            )
        }
        provide<CarrierHttpClients> {
            CarrierHttpClients(
                resolve<AppConfig>().carriers.mapValues { (_, settings) -> settings.toClientSettings() }
            )
        }
        provide<Map<CarrierId, CarrierRateLimiter>> {
            resolve<AppConfig>().carriers.mapValues { (_, settings) -> settings.toRateLimiter() }
        }
        provide<CarrierRegistry> {
            val adapters = CarrierId.entries.mapNotNull { carrier ->
                val adapterKey = DependencyKey<CarrierAdapter>(carrier.id)
                if (contains(adapterKey)) carrier to get<CarrierAdapter>(adapterKey) else null
            }
            CarrierRegistry(adapters.toMap())
        }
    }
}
