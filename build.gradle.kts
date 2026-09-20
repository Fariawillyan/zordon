plugins {
    id("zordon.docs-conventions")
}

/**
 * O motor de voz em Python (SPEC-011 CA-9, SPEC-013): moldura, fim de fala,
 * normalização e o fluxo contínuo só com biblioteca padrão; o detector com os
 * modelos, quando o venv do motor existe.
 */
val voiceUnitTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Testes Python do motor de voz (voice/tests)."
    inputs.dir("voice")
    outputs.upToDateWhen { false }
    // Com o venv do motor, rodam também os testes com modelos (SPEC-013); sem ele, eles pulam.
    val venv = file("${System.getProperty("user.home")}/.zordon/venv/bin/python")
    executable(if (venv.canExecute()) venv.path else "python3")
    args("-m", "unittest", "discover", "-s", "voice/tests")
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Portões automáticos da Definition of Done que rodam no build."
    dependsOn(
        subprojects.map { "${it.path}:check" } + listOf("validateDocs", "traceability", voiceUnitTest.name)
    )
}
