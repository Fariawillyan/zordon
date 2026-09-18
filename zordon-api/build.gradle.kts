plugins {
    id("zordon.java-conventions")
}

// Sem nenhuma dependência de produção, por decisão arquitetural: este módulo é o
// contrato que Windows e WSL compartilham (docs/architecture/components.md §4,
// regra 1). Jackson aparece apenas nos testes de contrato, que verificam a
// serialização sem que o contrato dependa do serializador.
dependencies {
    testImplementation(platform(libs.jackson.bom))
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.jsr310)
}
