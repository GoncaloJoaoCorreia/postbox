plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.postbox.ApplicationKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.di)
    implementation(libs.java.jwt)
    implementation(libs.argon2.jvm)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.typesafe.config)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.processResources {
    from(rootProject.file("docs/openapi.yaml")) {
        into("static")
    }
    from(rootProject.file("frontend/dist")) {
        into("web")
    }
}

tasks.processTestResources {
    from(rootProject.file("docs/carriers/fixtures")) {
        include("ctt-*.json")
        include("linkdo-*")
        into("fixtures")
    }
    from(rootProject.file("docs/carriers/paack/fixtures")) {
        include("*.json")
        into("fixtures/paack")
    }
    from(rootProject.file("docs/carriers/postnl/fixtures")) {
        include("*.json")
        into("fixtures/postnl")
    }
}
