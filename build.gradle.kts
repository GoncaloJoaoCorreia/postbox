plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.openapi.generator) apply false
    alias(libs.plugins.shadow) apply false
}

// Root build. Modules: :backend (server), :shared + :composeApp (mobile);
// `./gradlew run` from the repo root delegates to :backend:run so the server
// starts with a single command.
tasks.register("run") {
    group = "application"
    description = "Starts the Ktor backend server (delegates to :backend:run)."
    dependsOn(":backend:run")
}
