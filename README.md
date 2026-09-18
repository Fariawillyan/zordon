# ZORDON

> Plataforma pessoal de inteligência, automação **e defesa** residente no
> computador. Núcleo permanente no WSL2, interface JavaFX no Windows.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Status: marco `M1` — conversa de texto com streaming.** O núcleo roda como
serviço no WSL, a janela conversa com ele e mostra o custo de cada resposta. Voz,
ações no computador e agentes vêm nos próximos marcos do [Roadmap](docs/roadmap.md).

**Quer usar?** Siga os [Primeiros passos](docs/operations/quickstart.md) — uns 15
minutos, do zero à primeira resposta.

---

## O que é

Zordon é um assistente residente: um processo que vive no computador, ouve a
palavra de ativação "Zordon", entende o pedido, escolhe um agente especializado,
executa ferramentas reais (arquivos, Git, Docker, processos, Windows) sob um
motor de permissões, lembra do que já aconteceu e responde por voz, texto e UI.

Ele é também um **guardião**: observa a máquina continuamente, detecta
comportamento hostil, contém a ameaça de forma reversível e **explica o que fez**.

Ele **não** é um chatbot com botões. A diferença está em quatro propriedades:

1. **Residência** — o núcleo continua rodando quando a janela fecha, quando o
   usuário faz logoff da interface e enquanto o PC está ligado.
2. **Ação** — o valor não está na resposta, está na execução verificável, com
   trilha de auditoria e permissão explícita.
3. **Extensibilidade sem recompilação** — novas Skills, MCP servers e Agents
   entram por configuração e descoberta, não por alteração do núcleo.
4. **Transparência verificável** — nenhuma iniciativa autônoma acontece em
   silêncio. O usuário nunca descobre depois o que o Zordon fez.

## Como o Zordon é construído

O Zordon é construído pelo próprio Zordon: um time de agentes especializados
sobre o mesmo mecanismo que serve o usuário
([ADR-0021](docs/adr/ADR-0021-dois-perfis-de-agente.md)).

```text
   SPEC → RAG / conhecimento → planejamento → agentes especializados
        → implementação → testes → review → documentação → observabilidade
```

Nenhuma funcionalidade relevante nasce como código: nasce como
[SPEC](docs/process/spec-driven-development.md). Nada é considerado pronto por
ter compilado — existe uma [Definition of Done](docs/process/definition-of-done.md)
com dez portões. E todo token consumido é
[medido e atribuído](docs/operations/token-usage.md).

O Zordon também altera o **próprio** projeto, e isso tem um controle específico:
ele escreve código-fonte em branch, mas **nunca se instala** e **nunca altera o
próprio núcleo de confiança** sem revisão humana
([Auto-modificação](docs/process/self-modification.md)). Um Zordon comprometido
consegue escrever código ruim; não consegue fazê-lo rodar.

## As cinco invariantes

O Zordon tem opiniões fortes, e estas cinco não admitem exceção:

| # | Invariante | Consequência |
|---|---|---|
| 1 | **Não existe execução de shell arbitrária** | Injeção de shell é impossível, não improvável — [ADR-0007](docs/adr/ADR-0007-permissao-sobre-acao-estruturada.md) |
| 2 | **O Zordon não apaga arquivos** | Quarentena reversível no lugar. Dano irreversível não existe — [ADR-0015](docs/adr/ADR-0015-exclusao-impossivel-por-construcao.md) |
| 3 | **Nenhuma iniciativa autônoma é silenciosa** | `DETECTAR → COMUNICAR → AGIR → COMUNICAR → REGISTRAR` — [ADR-0014](docs/adr/ADR-0014-nenhuma-iniciativa-silenciosa.md) |
| 4 | **O LLM nunca decide segurança** | Ele explica e recomenda; o código determinístico decide — [ADR-0016](docs/adr/ADR-0016-defesa-deterministica.md) |
| 5 | **O Zordon não altera o próprio núcleo de confiança** | Ele escreve código-fonte em branch; não se instala, e não enfraquece as próprias proteções — [ADR-0024](docs/adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md) |

E um limite declarado: **o Zordon não é um antivírus.** Ele é a autoridade sobre
a superfície de IA (MCP, agentes, ferramentas, injeção), um detector
comportamental complementar no host, e um orquestrador de resposta para o que o
Windows Defender já detecta. Ver
[ADR-0017](docs/adr/ADR-0017-zordon-nao-e-antivirus.md).

## Topologia em uma tela

```text
 WINDOWS                                     │  WSL2 (Ubuntu + systemd)
 ─────────────────────────────────────────── │ ──────────────────────────────
                                             │
  zordon-desktop        zordon-host          │        zordon-core
  (JavaFX, tray)        (headless)           │        (zordon.service)
       │                     │               │             │
       │  UI, chat,          │  microfone,   │   IA · Agents · MCP · Skills
       │  overlay,           │  alto-falante,│   Memória · Automação
       │  diagnostics        │  ops Windows  │   Permission Engine
       │                     │               │
       └──────── ZWP (WebSocket) ────────────┴──────────►  :8777
```

Três processos, um protocolo. Detalhes e justificativa em
[Arquitetura](docs/architecture/overview.md) e [ADR-0005](docs/adr/ADR-0005-host-windows-dedicado.md).

## Decisões já tomadas

| # | Decisão | ADR |
|---|---------|-----|
| 1 | Núcleo roda no WSL2 sob systemd, não no Windows | [ADR-0001](docs/adr/ADR-0001-nucleo-no-wsl2.md) |
| 2 | Build com Gradle (Kotlin DSL) multi-módulo, não Maven | [ADR-0002](docs/adr/ADR-0002-gradle-como-build.md) |
| 3 | Transporte: JSON-RPC 2.0 sobre WebSocket, não gRPC | [ADR-0003](docs/adr/ADR-0003-websocket-json-rpc.md) |
| 4 | Núcleo em Java 25; voz em Python como sidecar | [ADR-0004](docs/adr/ADR-0004-java-no-nucleo-python-na-voz.md) |
| 5 | Um processo Windows headless separado da UI | [ADR-0005](docs/adr/ADR-0005-host-windows-dedicado.md) |
| 6 | Descoberta e autenticação por arquivo de endpoint | [ADR-0006](docs/adr/ADR-0006-descoberta-por-arquivo-de-endpoint.md) |
| 7 | Permissão sobre ação estruturada, nunca sobre string de shell | [ADR-0007](docs/adr/ADR-0007-permissao-sobre-acao-estruturada.md) |
| 8 | SQLite único (FTS5 + sqlite-vec) para as três memórias | [ADR-0008](docs/adr/ADR-0008-sqlite-como-memoria.md) |
| 9 | Captura de áudio no Windows, inferência de voz no WSL | [ADR-0009](docs/adr/ADR-0009-captura-windows-inferencia-wsl.md) |
| 10 | Seleção semântica de ferramentas em dois estágios | [ADR-0010](docs/adr/ADR-0010-selecao-semantica-de-tools.md) |
| 11 | Event bus com sequência e replay para reconexão | [ADR-0011](docs/adr/ADR-0011-event-bus-com-replay.md) |
| 12 | Skills in-process no MVP; plugin isolado depois | [ADR-0012](docs/adr/ADR-0012-skills-in-process-primeiro.md) |
| 13 | Apache License 2.0, sem CLA | [ADR-0013](docs/adr/ADR-0013-apache-2.md) |
| 14 | Nenhuma iniciativa autônoma silenciosa | [ADR-0014](docs/adr/ADR-0014-nenhuma-iniciativa-silenciosa.md) |
| 15 | Exclusão de arquivos impossível por construção | [ADR-0015](docs/adr/ADR-0015-exclusao-impossivel-por-construcao.md) |
| 16 | Defesa determinística; o LLM fora do caminho de decisão | [ADR-0016](docs/adr/ADR-0016-defesa-deterministica.md) |
| 17 | Escopo honesto: três anéis, não um antivírus | [ADR-0017](docs/adr/ADR-0017-zordon-nao-e-antivirus.md) |
| 18 | Circuit breaker por sujeito e Defense Lockdown | [ADR-0018](docs/adr/ADR-0018-circuit-breaker-e-lockdown.md) |
| 19 | Spec-Driven Development obrigatório | [ADR-0019](docs/adr/ADR-0019-spec-driven-development.md) |
| 20 | RAG sobre a própria documentação, como conhecimento | [ADR-0020](docs/adr/ADR-0020-rag-como-conhecimento.md) |
| 21 | Dois perfis de agente sobre um único mecanismo | [ADR-0021](docs/adr/ADR-0021-dois-perfis-de-agente.md) |
| 22 | Todo consumo de token é medido, orçado e atribuído | [ADR-0022](docs/adr/ADR-0022-token-observability.md) |
| 23 | Documentação como fonte de verdade, com estrutura e metadados | [ADR-0023](docs/adr/ADR-0023-documentacao-como-fonte-de-verdade.md) |
| 24 | O Zordon escreve o próprio código, mas não se instala | [ADR-0024](docs/adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md) |
| 25 | O transporte do protocolo é um módulo separado do contrato | [ADR-0025](docs/adr/ADR-0025-modulo-de-transporte-zwp.md) |
| 26 | O núcleo não pertence a nenhum provider de IA | [ADR-0026](docs/adr/ADR-0026-provider-agnostico.md) |

## Por onde começar a ler

Para **instalar e usar**: [Primeiros passos](docs/operations/quickstart.md).

Para **entender e contribuir**:

1. [Visão e escopo](docs/vision.md) — o que é e o que não é
2. [Arquitetura](docs/architecture/overview.md) — processos, camadas, fluxo de um turno
3. [Windows ↔ WSL](docs/architecture/windows-wsl.md) — **leia antes de codar**
4. [Spec-Driven Development](docs/process/spec-driven-development.md) — como uma ideia vira funcionalidade
5. [Segurança](docs/security/model.md) + [Defesa](docs/security/defense.md) + [Comunicação](docs/security/communication.md)
6. [Roadmap](docs/roadmap.md) — M0 a M7

Índice completo: [docs/README.md](docs/README.md).

## Contribuindo

Leia [CONTRIBUTING.md](CONTRIBUTING.md). O projeto assume, por política, que
qualquer contribuição externa pode estar comprometida — nenhum PR faz merge
automático, e há invariantes que reprovam o PR sem discussão.

Vulnerabilidade: **não abra issue pública.** Ver [SECURITY.md](SECURITY.md).

## Licença

[Apache License 2.0](LICENSE) — ver [NOTICE](NOTICE) e
[ADR-0013](docs/adr/ADR-0013-apache-2.md).

O Zordon executa ações reais na máquina do usuário. Leia
[Segurança](docs/security/model.md) antes de rodar qualquer build com
permissões relaxadas.
