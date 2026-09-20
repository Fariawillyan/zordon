plugins {
    id("zordon.java-conventions")
    application
}

// Regra 3 de docs/architecture/components.md §4: o host não conhece o núcleo.
// Ele fala com ele apenas pelo protocolo. Sem JavaFX: o host não tem janela.
dependencies {
    implementation(project(":zordon-api"))
    implementation(project(":zordon-zwp"))
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.archunit.junit5)
}

application {
    mainClass = "zordon.host.ZordonHost"
    applicationName = "zordon-host"
}

// ── Host no Windows (SPEC-007) ──────────────────────────────────────────────
// Os jars são os mesmos do Linux; o que muda é quem os executa: o javaw.exe do
// Windows, pela tarefa agendada "Zordon Host".

val windowsDistDir = layout.buildDirectory.dir("windows-dist")

/**
 * Cópia executável no Windows. O argfile lista cada jar pelo nome: um jar de
 * versão anterior que tenha ficado na pasta instalada nunca entra no classpath,
 * e o instalador não precisa apagar nada (ADR-0015).
 */
val windowsDist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Monta o host para rodar no Java do Windows."
    val appJar = tasks.jar.flatMap { it.archiveFile }
    into(windowsDistDir)
    from(appJar) { into("lib") }
    from(configurations.runtimeClasspath) { into("lib") }
    doLast {
        val root = windowsDistDir.get().asFile
        // Barras normais e sem aspas: no argfile, "\" dentro de aspas é escape.
        val app = "lib/" + appJar.get().asFile.name
        val lib = root.resolve("lib").listFiles().orEmpty().map { "lib/" + it.name }.sorted()
        val classpath = (listOf(app) + (lib - app)).joinToString(";")
        root.resolve("zordon-host.args").writeText(
            """
            -Dfile.encoding=UTF-8
            -cp $classpath
            zordon.host.ZordonHost
            """.trimIndent() + "\n",
        )
    }
}

tasks.test {
    // O teste de contrato lê os scripts do Windows no repositório.
    systemProperty("zordon.repoRoot", rootDir.absolutePath)
}
