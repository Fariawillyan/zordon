import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    `java-library`
    jacoco
    checkstyle
    id("com.diffplug.spotless")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        // Declarado aqui e não herdado do PATH: o build não depende do JDK da máquina.
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-parameters"))
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj-core").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Ordem aleatória por construção: teste que depende de ordem é proibido
    // (docs/testing/strategy.md §11).
    systemProperty("junit.jupiter.testmethod.order.default",
        "org.junit.jupiter.api.MethodOrderer\$Random")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Cobertura por módulo. O relatório XML é o que o agregador do raiz consome.
tasks.withType<JacocoReport>().configureEach {
    reports {
        xml.required = true
        html.required = true
    }
}

/**
 * Os tetos rígidos de complexidade do [code-standards §5].
 *
 * <p>O portão existe porque a Definition of Done afirma que "Clean Code validado"
 * tem sinal objetivo. Antes disto o sinal era a opinião de quem revisava, e por
 * isso três classes passaram do limite sem ninguém notar (ADR-0040).
 *
 * <p>Só o teto que a tabela marca como bloqueante entra aqui. O limite menor
 * dispara revisão humana e continua sendo julgamento, não reprovação.
 */
checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    // Segurança e defesa leem 30% mais apertado: código de segurança ilegível
    // não é código de segurança (code-standards §5).
    val strict = project.name in setOf("zordon-security", "zordon-defense")
    configFile = rootProject.file(
        if (strict) "config/checkstyle/checkstyle-strict.xml" else "config/checkstyle/checkstyle.xml"
    )
    // Um aviso que não reprova é um limite que não existe.
    maxWarnings = 0
}

spotless {
    java {
        target("src/**/*.java")
        licenseHeaderFile(rootProject.file("gradle/license-header.txt"))
        removeUnusedImports()
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}
