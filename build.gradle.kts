plugins {
    id("zordon.docs-conventions")
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Portões automáticos da Definition of Done que rodam no build."
    dependsOn(
        subprojects.map { "${it.path}:check" } + listOf("validateDocs", "traceability")
    )
}
