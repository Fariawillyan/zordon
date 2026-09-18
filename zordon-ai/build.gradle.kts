plugins {
    id("zordon.java-conventions")
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
