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
