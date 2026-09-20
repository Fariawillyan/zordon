plugins {
    jacoco
    alias(libs.plugins.cyclonedx)
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

/**
 * Cobertura somada de todos os módulos Java.
 *
 * <p>Um número por módulo não responde "quanto do projeto está coberto": o
 * agregador junta as execuções e as classes de todos eles num relatório só.
 */
val coverage = tasks.register<JacocoReport>("coverage") {
    group = "verification"
    description = "Relatório de cobertura somando todos os módulos."
    val javaProjects = subprojects.filter { it.plugins.hasPlugin("jacoco") }
    dependsOn(javaProjects.map { it.tasks.named("test") })
    executionData.setFrom(files(javaProjects.map {
        it.layout.buildDirectory.file("jacoco/test.exec")
    }).filter { it.exists() })
    sourceDirectories.setFrom(files(javaProjects.map { it.the<SourceSetContainer>()["main"].allSource.srcDirs }))
    classDirectories.setFrom(files(javaProjects.map { it.the<SourceSetContainer>()["main"].output }))
    reports {
        xml.required = true
        xml.outputLocation = layout.buildDirectory.file("reports/coverage/coverage.xml")
        html.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/coverage/html")
    }
}

/**
 * O SBOM que o [SECURITY.md] promete publicar em cada release.
 *
 * <p>O plugin é de build: ele não entra no binário nem no classpath do produto.
 * A lista completa, com transitivas, fica em `build/reports/cyclonedx/bom.json`;
 * `cyclonedx-direct` traz só as dependências declaradas.
 */
tasks.register("sbom") {
    group = "verification"
    description = "Gera o SBOM CycloneDX das dependências distribuídas."
    dependsOn("cyclonedxBom")
    val bom = layout.buildDirectory.file("reports/cyclonedx/bom.json")
    outputs.file(bom)
    doLast {
        logger.lifecycle("SBOM em {}", bom.get().asFile)
    }
}
