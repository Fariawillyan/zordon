plugins {
    `kotlin-dsl`
}

/** Converte uma entrada [plugins] do catálogo no coordenada do marker artifact. */
fun plugin(dependency: Provider<PluginDependency>) =
    dependency.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    implementation(plugin(libs.plugins.spotless))
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
