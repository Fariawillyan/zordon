plugins {
    id("zordon.java-conventions")
}

// O núcleo de confiança (ADR-0024): classifica, valida e audita; não executa nada
// e não conhece o zordon-core (docs/architecture/components.md §3).
dependencies {
    api(project(":zordon-api"))
    implementation(libs.sqlite.jdbc)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    implementation(libs.tomlj)
    compileOnly(libs.checker.qual)
}

tasks.withType<Test>().configureEach {
    // O sqlite-jdbc carrega a biblioteca nativa do SQLite.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
