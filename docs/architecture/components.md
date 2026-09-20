---
document: architecture-components
module: architecture
section: components
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [modulos,gradle,build,archunit]
specId: null
---

# Arquitetura — componentes e build

## 1. Decisão de build: Gradle

O briefing pedia avaliação entre Maven e Gradle. A decisão é **Gradle com Kotlin
DSL**, registrada em [ADR-0002](../adr/ADR-0002-gradle-como-build.md). Resumo do
raciocínio:

Este não é um projeto Java homogêneo. Ele precisa, no mesmo build:

- compilar bibliotecas Java puras (`zordon-api`, capacidades);
- compilar e empacotar uma aplicação JavaFX para **Windows** com `jpackage`
  (MSI + ícone + tray + JRE embutido);
- montar um tarball para **Linux** com unit systemd e script de instalação;
- provisionar um ambiente Python (venv + `requirements.lock`) para o sidecar de voz;
- baixar e verificar checksum de modelos ONNX (wake word, VAD) que não podem ir
  para o Git;
- gerar dois artefatos de plataformas diferentes a partir de um único repositório.

Em Maven, tudo depois do segundo item vira `exec-maven-plugin` e `maven-antrun`,
que é onde builds Maven vão para morrer. Em Gradle, cada um é uma task tipada,
incremental e cacheável.

O contrapeso honesto: Maven é mais simples e mais previsível, e um
desenvolvedor Spring o conhece melhor. Se o projeto fosse só o núcleo, Maven
venceria. O empacotamento cross-platform é que decide.

**Toolchain:** Java 25 (LTS) — já instalado nesta máquina (Temurin 25.0.2). O
mesmo `languageVersion` para todos os módulos, declarado via toolchain Gradle
para que o build não dependa do JDK do `PATH`.

## 2. Mapa de módulos

```text
zordon/
├── settings.gradle.kts
├── build.gradle.kts                  convenções compartilhadas
├── gradle/libs.versions.toml         version catalog — versões só aqui
│
├── zordon-api/                       ◄── compartilhado Windows+WSL
│     records do ZWP, eventos, ZPath, enums de risco
│     dependência: NENHUMA além do JDK
│
├── zordon-zwp/                       ◄── compartilhado Windows+WSL
│     codec JSON-RPC, endpoint.json, cliente ZWP, backoff de reconexão
│     dependência: zordon-api + JSON + WebSocket  (ADR-0025)
│
├── zordon-core/                      ◄── aplicação Linux
│     ZwpServer, EventBus, IntentRouter, TurnManager,
│     composition root (injeção de todos os módulos abaixo)
│
├── zordon-ai/            AiProvider, providers, roteamento de modelo, custo
├── zordon-agents/        Agent, AgentOrchestrator, runtime, orçamentos
├── zordon-mcp/           McpManager, clientes stdio/http, discovery
├── zordon-skills/        ZordonSkill, SkillRuntime, skills nativas
├── zordon-memory/        MemoryStore, SQLite, FTS5, vetores, destilação
├── zordon-automation/    Scheduler, watchers, WorkflowEngine
├── zordon-security/      PermissionEngine, CommandValidator, AuditLog, SecretManager
│                        SecurityPolicy (imutável), mascaramento, scanning
├── zordon-defense/       DetectionEngine, detectores, DefenseEngine, playbooks,
│                        CircuitBreaker, Lockdown, QuarantineVault
├── zordon-notify/        NotificationCenter, fila durável, correlação, anti-fadiga
├── zordon-monitor/       coletores de CPU/RAM/GPU/Docker/rede/Git
├── zordon-voice-client/  cliente do sidecar (Java)
│
├── zordon-desktop/                   ◄── aplicação Windows (JavaFX)
├── zordon-host/                      ◄── aplicação Windows (headless)
├── zordon-windows-bridge/            contrato do bridge (usado pelos dois lados)
│
├── voice/                            ◄── Python, não é módulo Gradle
│     zordon_voice/  requirements.lock  models/
│
├── packaging/
│     windows/   (jpackage, ícones, MSI, tarefa agendada)
│     wsl/       (zordon.service, install.sh, config padrão)
│
└── docs/
```

`zordon-zwp` separa **o que viaja** (o contrato, em `zordon-api`) de **como
viaja** (codec, socket, descoberta). Sem ele, ou o contrato passaria a depender de
um serializador, ou o mesmo codec existiria duplicado no núcleo e em cada cliente
— ver [ADR-0025](../adr/ADR-0025-modulo-de-transporte-zwp.md).

`zordon-monitor` e `zordon-voice-client` não estavam no briefing. O primeiro
existe porque coleta de métricas tem ciclo de vida próprio (amostragem,
coalescência, watchers) e não pertence nem ao core nem às skills. O segundo
existe para isolar o protocolo com o sidecar Python.

## 3. Responsabilidades e limites

| Módulo | Responsável por | **Não** é responsável por |
|---|---|---|
| `zordon-api` | Formato dos dados que cruzam processo | Qualquer lógica |
| `zordon-zwp` | Serializar, conectar, descobrir o núcleo, reconectar | Qualquer regra de produto |
| `zordon-core` | Compor tudo, servir ZWP, conduzir o turno | Saber como uma ferramenta executa |
| `zordon-ai` | Falar com modelos, contar tokens e custo | Decidir qual agente usar |
| `zordon-agents` | Laço do agente, orçamento, delegação | Implementar ferramentas |
| `zordon-mcp` | Protocolo MCP, ciclo de vida dos servidores | Escolher quais tools mandar ao modelo |
| `zordon-skills` | Executar capacidades locais | Decidir se pode executar |
| `zordon-memory` | Persistir e recuperar | O que é digno de lembrar (isso é do core) |
| `zordon-automation` | Disparar no tempo/evento certo | O que fazer ao disparar |
| `zordon-security` | Classificar, validar, auditar | Executar |
| `zordon-defense` | Detectar ameaça e escolher a contenção | Executar sem passar por `security`; decidir com LLM |
| `zordon-notify` | Garantir que o usuário saiba | Decidir se a ação acontece |
| `zordon-monitor` | Amostrar o sistema | Alertar (isso é automação) |
| `zordon-desktop` | Mostrar e capturar interação | Qualquer decisão de negócio |
| `zordon-host` | Fazer coisas no Windows | Decidir se deve fazer |

Responsabilidades que chegam com o M3 em diante e ainda não têm módulo próprio
(ficam no módulo indicado até crescerem):

| Componente | Onde | Responsável por | **Não** é responsável por |
|---|---|---|---|
| Identidade/origem | `zordon-security` | Aplicar o teto de risco por origem ([Identidade](../security/identity.md)) | Classificar a ação |
| `Sandbox` | `zordon-security` | Rodar código de agente isolado ([Sandbox](../security/sandbox.md)) | Aprovar o que roda |
| `SecretBroker` | `zordon-security` | Usar o segredo sem entregá-lo | Decidir se o uso é permitido |
| `CapabilityRegistry` + `ModelRouter` | `zordon-ai` | Escolher o modelo ([Capacidades](../specs/core/capabilities-and-routing.md)) | Escolher o agente |
| `ExtensionRegistry` | `zordon-core` | Manifestos, versões, reversão ([Extensões](extensions.md)) | Executar a extensão |
| `Planner` + `TaskStore` | `zordon-agents` | Planos duráveis ([Planner](../specs/agents/planner.md)) | Executar passos |
| `Verifier` / Evaluation Engine | `zordon-agents` | Provar a conclusão ([Avaliação](../specs/agents/evaluation.md)) | Corrigir o que falhou |
| `KnowledgeGraph` | `zordon-memory` | Relações com fonte | Inferir relação sem fonte |

A coluna da direita é a que importa. Quando alguém for tentado a colocar lógica
de permissão dentro de uma Skill "porque é mais fácil", é essa tabela que diz não.

## 4. Regras de dependência (verificadas no build)

```text
  api  ◄──────────────────────────── todos
  security ◄─────────── capacidades (exceção permitida)
  core ──► capacidades  (nunca o inverso)
  capacidades ✗ capacidades  (não se conhecem)
  desktop / host ──► api  (e nada mais do núcleo)
```

Estas regras são testadas com **ArchUnit** em `zordon-core:test`, com falha de
build:

1. Nada em `zordon-api` importa de fora de `java.*`.
2. Nenhum módulo de capacidade importa outro módulo de capacidade.
3. `zordon-desktop` e `zordon-host` não importam de `zordon-core` nem das
   capacidades — só de `zordon-api`, `zordon-zwp` e `zordon-windows-bridge`.
4. Nenhuma classe fora de `zordon-security` chama `ProcessBuilder`,
   `Runtime.exec` ou `Files.delete` diretamente.
5. Nenhuma classe fora de `zordon-core` publica no `EventBus` sem passar pela
   fachada (garante `seq` e auditoria).
6. **Nenhuma chamada a `Files.delete`, `Files.deleteIfExists` ou `File.delete`
   fora de `zordon-defense.vault`** ([ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md)).
7. Nenhuma classe fora de `zordon-security` referencia `SecurityPolicy` em
   contexto de escrita.
8. Nenhuma classe de `zordon-defense` importa `zordon-ai` — a defesa não consulta
   o LLM no caminho de decisão ([ADR-0016](../adr/ADR-0016-defesa-deterministica.md)).
9. Todo caminho que executa ação com `origin = AUTONOMOUS` passa por
    `NotificationCenter` antes de `ProcessRunner`/`FileAccess`.
10. **Nenhuma escrita alcança os diretórios de instalação do Zordon**
    (`~/.local/share/zordon/**`, `%LOCALAPPDATA%\Programs\Zordon\**`).
11. Classes que tocam `PermissionEngine`, `AuditLog`, `SecurityPolicy` ou
    `NotificationCenter` vivem dentro de `zordon-security` ou `zordon-defense`.
    Lógica de segurança não pode existir num módulo que o Zordon poderia editar
    livremente ([ADR-0024](../adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md)).

As regras 4, 6, 8, 9 e 10 são as que impedem o sistema de apodrecer. Sem a 4, em
três meses haverá um `ProcessBuilder` escondido numa Skill e o Permission Engine
virará decoração. Sem a 6, alguém adiciona um `delete` "só para o caso de
limpeza". Sem a 8, a defesa começa a "perguntar ao modelo se é suspeito". Sem a
9, surge o primeiro caminho silencioso. Sem a 10, o Zordon se instala sozinho e
todas as outras deixam de valer.

Estas cinco regras codificam as
[cinco invariantes](security.md#2-as-cinco-invariantes) do projeto. Elas não são
estilo: são a diferença entre o produto documentado e um parecido com ele.

A regra 11 é a guarda da regra 10: se lógica de segurança vazar para um módulo
comum, o núcleo de confiança deixa de cobri-la — e o Zordon passaria a poder
editá-la livremente sem que ninguém percebesse.

## 5. Empacotamento

### Windows

```text
gradlew :zordon-desktop:jpackageMsi
gradlew :zordon-host:jpackageMsi
```

Resultado: `Zordon-0.1.0.msi`, instalando em `%LOCALAPPDATA%\Programs\Zordon`:

- `zordon-desktop.exe` (JRE embutido, JavaFX, ícone, tray)
- `zordon-host.exe` (JRE embutido, sem console, headless)
- Atalho no menu Iniciar
- Tarefa agendada `Zordon Host` no logon → inicia `zordon-host.exe`
- Tarefa agendada `Zordon WSL Boot` no logon → `wsl.exe -d Ubuntu --exec /bin/true`

O instalador **não** inicia `zordon-desktop` automaticamente. O host basta; a UI
é aberta pelo tray ou pelo menu Iniciar.

### WSL

```text
gradlew :zordon-core:installDist
packaging/wsl/install.sh
```

Instala em `~/.local/share/zordon/`, cria `~/.zordon/` (config, banco, logs,
modelos), instala e habilita `~/.config/systemd/user/zordon.service` ou
`/etc/systemd/system/zordon.service`, e provisiona o venv do sidecar de voz.

A escolha entre unit de usuário e de sistema é discutida em
[Operação §3](../operations/install.md#3-unit-systemd).

### Modelos de ML

Modelos ONNX (Silero VAD, wake word, embeddings) e vozes Piper **não vão para o
Git**. Uma task Gradle (`downloadModels`) baixa por URL fixada, valida SHA-256 e
grava em `~/.zordon/models/`. O build falha se o checksum não bater — modelo é
código que executa; baixar sem verificar é uma porta de entrada.

## 6. Version catalog

Todas as versões vivem em `gradle/libs.versions.toml`. Nenhum número de versão
em `build.gradle.kts`. Dependências previstas:

| Área | Escolha | Por quê |
|---|---|---|
| WebSocket (servidor e cliente) | `org.java-websocket:Java-WebSocket` (MIT) | Biblioteca enxuta, sem framework web; o mesmo código serve os dois lados do ZWP |
| Configuração | `org.tomlj:tomlj` (Apache-2.0) | Preços, agentes e rotas rápidas são TOML; parser sem reflexão |
| JSON | Jackson (databind + records) | Maduro, suporte a records, streaming |
| SQLite | `sqlite-jdbc` (Xerial) | Bundle nativo, suporta extensões (FTS5, sqlite-vec) |
| Cliente Claude | `com.anthropic:anthropic-java` | SDK oficial; ver [Core](../specs/core/design.md) |
| JavaFX | OpenJFX 25 via `org.openjfx.javafxplugin` | Casa com a toolchain |
| Métricas | Micrometer (core apenas, sem backend obrigatório) | Permite Prometheus depois sem reescrever |
| Log | SLF4J + Logback com encoder JSON | Estruturado desde o início |
| Teste | JUnit 5, AssertJ, ArchUnit, Testcontainers (MCP/Docker) | |
| Windows nativo | JNA (apenas no `zordon-host`) | Tray, janelas, eventos de energia |

A coluna "Escolha" registra o que o build **usa**, não o que foi cogitado; as
versões estão em `gradle/libs.versions.toml`. Toda linha nova aqui passa pela
revisão de dependência de
[Cadeia de suprimentos §3](../security/supply-chain.md#3-pipeline-obrigatória-de-pr),
separada de mudança funcional.

Deliberadamente **fora**: Spring, Quarkus, Micronaut. O núcleo é um processo
long-running com injeção manual no composition root. Um framework de DI aqui
adiciona tempo de inicialização e magia de classpath sem resolver nenhum
problema que este projeto tenha. (O usuário conhece Spring; a recomendação é
técnica e específica a este projeto, não uma opinião geral sobre Spring.)

## 7. Qualidade no build

| Verificação | Ferramenta | Quando falha o build |
|---|---|---|
| Regras arquiteturais | ArchUnit | Sempre |
| Contrato ZWP | Golden files versionados | Mudança de serialização não intencional |
| Classificação de permissão | Tabela de casos golden | Uma ação muda de risco sem revisão |
| Cobertura em `zordon-security` e `zordon-defense` | JaCoCo, mínimo 90% | Abaixo do mínimo |
| Vulnerabilidades | OWASP Dependency-Check | CVE alta ou crítica |
| Detectores | Casos golden (positivos e negativos) | Sensibilidade muda sem revisão |
| Notificação obrigatória | Teste de invariante | `SecurityEvent` executado com `userMessageId` nulo é construível |
| Ausência de exclusão | ArchUnit + grep no build | Qualquer API de exclusão reintroduzida |
| SAST | CodeQL | Achado de severidade alta |
| Licenças | License check | Dependência GPL/AGPL/SSPL ([ADR-0013](../adr/ADR-0013-apache-2.md)) |
| Cabeçalho de licença | Spotless | Arquivo sem o cabeçalho Apache 2.0 |
| Formatação | Spotless | Sempre |

A tabela golden de permissões é a mais importante: ela transforma "essa ação é
perigosa?" numa pergunta com resposta versionada e revisável em diff, em vez de
uma decisão implícita no código. O mesmo vale para os casos golden de detector:
um PR que afrouxa um limiar de detecção precisa mostrar isso no diff, e é
exatamente esse o tipo de mudança que um PR malicioso tentaria esconder
([Cadeia de suprimentos §4](../security/supply-chain.md#4-verificações-em-detalhe)).

A pipeline completa de PR, incluindo SAST, SBOM e secret scan, está em
[Cadeia de suprimentos §3](../security/supply-chain.md#3-pipeline-obrigatória-de-pr).
