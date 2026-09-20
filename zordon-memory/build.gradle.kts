/*
 * Copyright 2026 Willyan Faria
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
plugins {
    id("zordon.java-conventions")
}

// A memória (SPEC-021): nenhum SQL de memória fora deste módulo (Memória §2).
dependencies {
    api(project(":zordon-api"))
    implementation(libs.sqlite.jdbc)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    compileOnly(libs.checker.qual)
}

tasks.withType<Test>().configureEach {
    // O sqlite-jdbc carrega a biblioteca nativa do SQLite.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
