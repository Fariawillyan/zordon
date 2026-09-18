---
document: adr-0002
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,gradle,como,build]
specId: null
---

# ADR-0002 — Gradle com Kotlin DSL como sistema de build

**Status:** Aceito · 2026-09-17

## Contexto

Projeto multi-módulo que precisa produzir, do mesmo repositório:

- bibliotecas Java puras;
- uma aplicação JavaFX empacotada para **Windows** com `jpackage` (MSI, ícone,
  JRE embutido, tarefas agendadas);
- uma aplicação headless Windows;
- um tarball **Linux** com unit systemd e instalador;
- um ambiente Python (venv + lockfile) para o sidecar de voz;
- download verificado de modelos ONNX que não podem ir para o Git.

O usuário tem Maven instalado e experiência com o ecossistema Spring/Maven.
Gradle não está instalado (seria via wrapper).

## Alternativas

**A. Maven.** Declarativo, previsível, resolução de dependência simples de
raciocinar, familiar. Mas: tudo que não é "compilar e empacotar jar" vira
`exec-maven-plugin` ou `maven-antrun-plugin`. Os cinco últimos itens da lista
acima seriam todos assim. Perfis por plataforma funcionam, mas ficam verbosos.
Sem cache de build incremental de tarefa customizada.

**B. Gradle Kotlin DSL.** Cada necessidade vira uma task tipada, incremental e
cacheável. `org.openjfx.javafxplugin` cuida dos artefatos nativos do JavaFX por
plataforma. Version catalog centraliza versões. Builds compostos e
`configuration cache` mantêm o tempo baixo. Mas: curva de aprendizado maior,
build é código (pode virar bagunça), e resolução de dependência tem mais
mecanismos para entender.

**C. Maven para o Java + Make/scripts para o resto.** Mantém o Java simples, mas
cria dois sistemas de build com duas fontes de verdade sobre versões e artefatos.
A pior opção para manutenção a longo prazo, que é justamente o critério pedido.

## Decisão

**Alternativa B**, Gradle com Kotlin DSL, wrapper versionado no repositório,
`gradle/libs.versions.toml` como única fonte de versões.

O critério do briefing era "melhor manutenção para o projeto". Para um projeto
Java homogêneo, Maven venceria com folga. Aqui, mais da metade do trabalho de
build não é compilar Java — é empacotar para duas plataformas, provisionar
Python e gerenciar artefatos binários. É nesse terreno que Gradle é
qualitativamente melhor, não apenas diferente.

## Consequências

**Positivas.** Empacotamento cross-platform como cidadão de primeira classe.
Tasks customizadas tipadas e incrementais. Build cache reduz ciclo. Kotlin DSL dá
completação e checagem de tipo no IDE.

**Negativas.** Aprendizado para quem vem de Maven. Build como código exige
disciplina: convenções ficam em `buildSrc` ou em plugins de convenção, nunca
copiadas entre módulos. Depurar resolução de dependência é mais difícil.

**Mitigações.** `build.gradle.kts` de cada módulo deve ser trivial (aplicar
plugin de convenção + declarar dependências); toda lógica vive em plugins de
convenção. Nenhum número de versão fora do catálogo. CI roda `--no-build-cache`
periodicamente para garantir que o build funciona do zero.

**Se esta decisão se mostrar errada**, o caminho de volta existe: o código Java
não depende do build. Migrar para Maven custaria os POMs mais a reescrita das
tasks de empacotamento — desagradável, não bloqueante.
