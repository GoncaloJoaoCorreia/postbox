import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.openapi.generator)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.sqldelight.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
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

android {
    namespace = "com.postbox.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
}

sqldelight {
    databases {
        create("PostboxCache") {
            packageName.set("com.postbox.shared.cache.db")
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
            "hideGenerationTimestamp" to "true"
        )
    )
    generateApiTests.set(false)
    generateModelTests.set(false)
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/openapi/src/commonMain/kotlin"))
}

tasks.configureEach {
    if (name.startsWith("compile")) {
        dependsOn("openApiGenerate")
    }
}
