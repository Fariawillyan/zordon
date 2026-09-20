plugins {
    id("zordon.java-conventions")
    application
    alias(libs.plugins.javafx)
}

// Regra 3 de docs/architecture/components.md §4: o desktop não conhece o núcleo.
// Ele fala com ele apenas pelo protocolo.
dependencies {
    implementation(project(":zordon-api"))
    implementation(project(":zordon-zwp"))
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls")
}

application {
    mainClass = "zordon.desktop.ZordonDesktop"
    applicationName = "zordon-desktop"
    // O JavaFX carrega bibliotecas nativas; a permissão é declarada para o módulo
    // dele, e não concedida a todo o classpath.
    applicationDefaultJvmArgs = listOf("--enable-native-access=javafx.graphics")
}

// Nos testes o JavaFX está no classpath, e não como módulo.
tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ── Desktop no Windows (SPEC-008 v2) ────────────────────────────────────────
// O desktop é uma aplicação Windows (docs/architecture/components.md §5): é lá
// que existe saída de áudio. O plugin do JavaFX resolve os jars da máquina que
// compila (Linux); o Windows precisa dos seus, com o classificador "win".

val windowsJavafx: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    listOf("base", "graphics", "controls").forEach { module ->
        windowsJavafx("org.openjfx:javafx-$module:${libs.versions.javafx.get()}:win")
    }
}

val windowsDistDir = layout.buildDirectory.dir("windows-dist")

/**
 * Cópia executável no Windows. Os argfiles listam cada jar pelo nome: um jar de
 * versão anterior que tenha ficado na pasta instalada nunca entra no classpath,
 * e o instalador não precisa apagar nada (ADR-0015).
 */
val windowsDist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Monta o desktop para rodar no Java do Windows."
    val appJar = tasks.jar.flatMap { it.archiveFile }
    val libraries = configurations.runtimeClasspath.get().filter { !it.name.startsWith("javafx-") }
    val javafx = windowsJavafx
    into(windowsDistDir)
    from(appJar) { into("lib") }
    from(libraries) { into("lib") }
    from(javafx) { into("javafx") }
    doLast {
        val root = windowsDistDir.get().asFile
        // Barras normais e sem aspas: no argfile, "\" dentro de aspas é escape, e o
        // Java do Windows aceita "/" em caminhos.
        val app = "lib/" + appJar.get().asFile.name
        val lib = root.resolve("lib").listFiles().orEmpty().map { "lib/" + it.name }.sorted()
        val modules = root.resolve("javafx").listFiles().orEmpty().map { "javafx/" + it.name }.sorted()
        val classpath = (listOf(app) + (lib - app)).joinToString(";")
        root.resolve("zordon-desktop.args").writeText(
            """
            --module-path ${modules.joinToString(";")}
            --add-modules javafx.controls
            --enable-native-access=javafx.graphics
            -Dfile.encoding=UTF-8
            -cp $classpath
            zordon.desktop.ZordonDesktop
            """.trimIndent() + "\n",
        )
        root.resolve("zordon-audio-check.args").writeText(
            """
            -Dstdout.encoding=UTF-8
            -cp $classpath
            zordon.desktop.audio.PlaybackCheck
            """.trimIndent() + "\n",
        )
        // Lançador manual; o atalho do menu Iniciar chama o javaw diretamente.
        root.resolve("zordon-desktop.cmd").writeText(
            "@echo off\r\ncd /d \"%~dp0\"\r\nstart \"\" javaw @zordon-desktop.args\r\n",
        )
    }
}

/**
 * Toca os seis sinais (em silêncio) no Java do Windows e reprova se algum não
 * sair pela placa. Fora do verifyAll: a CI não tem Windows.
 *
 * Uso, no WSL: ./gradlew :zordon-desktop:verifyWindowsAudio
 *   -PwindowsJava="/mnt/c/Program Files/Java/jdk-25/bin/java.exe"
 */
tasks.register<Exec>("verifyWindowsAudio") {
    group = "verification"
    description = "Comprova, no Java do Windows, que os efeitos sonoros saem pela placa."
    dependsOn(windowsDist)
    val java = providers.gradleProperty("windowsJava").orElse("/mnt/c/Program Files/Java/jdk-25/bin/java.exe")
    workingDir(windowsDistDir)
    executable(java.get())
    args("@zordon-audio-check.args")
}

tasks.test {
    dependsOn(windowsDist)
    systemProperty("zordon.windowsDist", windowsDistDir.get().asFile.absolutePath)
}
