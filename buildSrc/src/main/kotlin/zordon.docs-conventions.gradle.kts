import zordon.build.TraceabilityTask
import zordon.build.ValidateDocsTask

tasks.register<ValidateDocsTask>("validateDocs") {
    group = "verification"
    description = "Valida front-matter, seções de SPEC e links internos da documentação."
    docsDir = layout.projectDirectory.dir("docs")
    rootDocs.from(layout.projectDirectory.asFileTree.matching { include("*.md") })
}

tasks.register<TraceabilityTask>("traceability") {
    group = "verification"
    description = "Constrói o índice SPEC ↔ código ↔ teste e reprova referência órfã."
    docsDir = layout.projectDirectory.dir("docs")
    sources.from(layout.projectDirectory.asFileTree.matching {
        include("*/src/**/*.java")
        include("buildSrc/src/**/*.kt")
        include("voice/**/*.py")
    })
    index = layout.buildDirectory.file("traceability/index.json")
}
