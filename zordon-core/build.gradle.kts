plugins {
    id("zordon.java-conventions")
    application
}

dependencies {
    implementation(project(":zordon-api"))
    implementation(project(":zordon-zwp"))
    implementation(project(":zordon-ai"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.java.websocket)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.archunit.junit5)
    // Somente para o teste arquitetural: as regras precisam enxergar todos os
    // módulos, inclusive os que o núcleo não conhece em produção.
    testImplementation(project(":zordon-desktop"))
}

application {
    mainClass = "zordon.core.ZordonCore"
    applicationName = "zordon-core"
    // sd_notify precisa de chamada nativa; a permissão é declarada aqui em vez de
    // ser concedida a todo o classpath em silêncio.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
