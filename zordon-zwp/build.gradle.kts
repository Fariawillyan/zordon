plugins {
    id("zordon.java-conventions")
}

dependencies {
    api(project(":zordon-api"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.java.websocket)
    implementation(libs.slf4j.api)
}
