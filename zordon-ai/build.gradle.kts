plugins {
    id("zordon.java-conventions")
}

// O AiSettingsTest lê o config.toml.example em tempo de execução para provar que o
// exemplo instalado funciona. Sem declará-lo como entrada, o Gradle considera o
// teste up-to-date depois de editar o exemplo — e o guarda contra "documentação
// falsa" fica dormindo justamente quando é preciso.
tasks.test {
    inputs.file(rootProject.file("packaging/wsl/config.toml.example"))
        .withPropertyName("configExample")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":zordon-api"))
    implementation(libs.anthropic.java)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.tomlj)
    compileOnly(libs.checker.qual)
    implementation(libs.slf4j.api)
}
