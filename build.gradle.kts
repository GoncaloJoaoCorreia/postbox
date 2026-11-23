// Root build. The only module is :backend; `./gradlew run` from the repo root
// delegates to :backend:run so the server starts with a single command.
tasks.register("run") {
    group = "application"
    description = "Starts the Ktor backend server (delegates to :backend:run)."
    dependsOn(":backend:run")
}
