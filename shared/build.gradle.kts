import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.openapi.generator)
}

kotlin {
    android {
        namespace = "com.postbox.shared"
        compileSdk = 36
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest { }
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.sqldelight.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.androidx.security.crypto)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqlite.jdbc)
        }
    }
}

sqldelight {
    databases {
        create("PostboxCache") {
            packageName.set("com.postbox.shared.cache.db")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:${libs.versions.sqldelight.get()}")
        }
    }
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(rootProject.file("docs/openapi.yaml").absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    library.set("multiplatform")
    packageName.set("com.postbox.shared.api")
    apiPackage.set("com.postbox.shared.api.endpoints")
    modelPackage.set("com.postbox.shared.api.model")
    invokerPackage.set("com.postbox.shared.api.client")
    additionalProperties.set(
        mapOf(
            "dateLibrary" to "kotlinx-datetime",
            "hideGenerationTimestamp" to "true",
            "enumUnknownDefaultCase" to "true"
        )
    )
    generateApiTests.set(false)
    generateModelTests.set(false)
}

tasks.named("openApiGenerate") {
    doLast {
        val models = layout.buildDirectory.dir("generated/openapi/src/commonMain/kotlin/com/postbox/shared/api/model").get().asFile
        models.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            val enumName = Regex("enum class (\\w+)\\(").find(text)?.groupValues?.get(1) ?: return@forEach
            if (!text.contains("unknown_default_open_api") || text.contains("UnknownFallbackSerializer")) return@forEach
            val serializer = """
                |object ${enumName}UnknownFallbackSerializer : KSerializer<$enumName> {
                |    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("$enumName", PrimitiveKind.STRING)
                |    override fun deserialize(decoder: Decoder): $enumName {
                |        val wireValue = decoder.decodeString()
                |        return $enumName.entries.firstOrNull { it.value == wireValue } ?: $enumName.unknown_default_open_api
                |    }
                |    override fun serialize(encoder: Encoder, value: $enumName) {
                |        encoder.encodeString(value.value)
                |    }
                |}
                """.trimMargin()
            file.writeText(
                text.replaceFirst(
                    "import kotlinx.serialization.*",
                    "import kotlinx.serialization.*\nimport kotlinx.serialization.descriptors.PrimitiveKind\nimport kotlinx.serialization.descriptors.PrimitiveSerialDescriptor\nimport kotlinx.serialization.descriptors.SerialDescriptor\nimport kotlinx.serialization.encoding.Decoder\nimport kotlinx.serialization.encoding.Encoder"
                ).replaceFirst(
                    "@Serializable\nenum class $enumName(",
                    "@Serializable(with = ${enumName}UnknownFallbackSerializer::class)\nenum class $enumName("
                ) + "\n\n" + serializer + "\n"
            )
        }
    }
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/openapi/src/commonMain/kotlin"))
}

tasks.withType<AbstractKotlinCompile<*>>().configureEach {
    dependsOn("openApiGenerate")
}
