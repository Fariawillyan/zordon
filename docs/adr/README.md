---
document: adr-index
module: adr
section: index
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,indice]
specId: null
---

# Architecture Decision Records

Decisões arquiteturais do Zordon. Formato MADR simplificado: contexto,
alternativas consideradas, decisão, consequências.

**Regra:** um ADR nunca é editado depois de aceito. Se a decisão mudar, escreve-se
um ADR novo que **supera** o anterior, e o anterior é marcado como superado com
um link. O histórico de por que decidimos errado vale tanto quanto o registro do
acerto.

| # | Decisão | Status |
|---|---|---|
| [0001](ADR-0001-nucleo-no-wsl2.md) | Núcleo no WSL2 sob systemd | Aceito |
| [0002](ADR-0002-gradle-como-build.md) | Gradle com Kotlin DSL como build | Aceito |
| [0003](ADR-0003-websocket-json-rpc.md) | JSON-RPC 2.0 sobre WebSocket | Aceito |
| [0004](ADR-0004-java-no-nucleo-python-na-voz.md) | Java no núcleo, Python na voz | Aceito |
| [0005](ADR-0005-host-windows-dedicado.md) | Processo Windows headless separado da UI | Aceito |
| [0006](ADR-0006-descoberta-por-arquivo-de-endpoint.md) | Descoberta e autenticação por arquivo de endpoint | Aceito |
| [0007](ADR-0007-permissao-sobre-acao-estruturada.md) | Permissão sobre ação estruturada, nunca sobre shell | Aceito |
| [0008](ADR-0008-sqlite-como-memoria.md) | SQLite único para as três memórias | Aceito |
| [0009](ADR-0009-captura-windows-inferencia-wsl.md) | Captura de áudio no Windows, inferência no WSL | Aceito |
| [0010](ADR-0010-selecao-semantica-de-tools.md) | Seleção semântica de ferramentas em dois estágios | Aceito |
| [0011](ADR-0011-event-bus-com-replay.md) | Event bus com sequência, políticas de fila e replay | Aceito |
| [0012](ADR-0012-skills-in-process-primeiro.md) | Skills in-process primeiro, plugin depois | Aceito |
| [0013](ADR-0013-apache-2.md) | Apache License 2.0 e projeto aberto | Aceito |
| [0014](ADR-0014-nenhuma-iniciativa-silenciosa.md) | Nenhuma iniciativa autônoma acontece silenciosamente | Aceito |
| [0015](ADR-0015-exclusao-impossivel-por-construcao.md) | Exclusão de arquivos é impossível por construção | Aceito |
| [0016](ADR-0016-defesa-deterministica.md) | Defesa determinística: o LLM aconselha, o código decide | Aceito |
| [0017](ADR-0017-zordon-nao-e-antivirus.md) | O Zordon não é um antivírus: escopo honesto | Aceito |
| [0018](ADR-0018-circuit-breaker-e-lockdown.md) | Circuit breaker por sujeito e Defense Lockdown | Aceito |
| [0019](ADR-0019-spec-driven-development.md) | Spec-Driven Development obrigatório | Aceito |
| [0020](ADR-0020-rag-como-conhecimento.md) | RAG sobre a própria documentação, como conhecimento | Aceito |
| [0021](ADR-0021-dois-perfis-de-agente.md) | Dois perfis de agente sobre um único mecanismo | Aceito |
| [0022](ADR-0022-token-observability.md) | Todo consumo de token é medido, orçado e atribuído | Aceito |
| [0023](ADR-0023-documentacao-como-fonte-de-verdade.md) | Documentação como fonte de verdade, com estrutura e metadados | Aceito |
| [0024](ADR-0024-auto-modificacao-e-nucleo-de-confianca.md) | O Zordon escreve o próprio código, mas não se instala | Aceito |
| [0025](ADR-0025-modulo-de-transporte-zwp.md) | Módulo de transporte `zordon-zwp`, separado do contrato | Aceito |
| [0026](ADR-0026-provider-agnostico.md) | O núcleo não pertence a nenhum provider de IA | Aceito |

## Decisões que definem o produto

Se for ler apenas cinco, leia estas — elas explicam por que o Zordon é
diferente de um chatbot com acesso a ferramentas:

| ADR | A propriedade que ela cria |
|---|---|
| [0007](ADR-0007-permissao-sobre-acao-estruturada.md) | Injeção de shell é impossível, não improvável |
| [0014](ADR-0014-nenhuma-iniciativa-silenciosa.md) | O usuário nunca descobre depois o que o Zordon fez |
| [0015](ADR-0015-exclusao-impossivel-por-construcao.md) | Dano irreversível a arquivos não existe como categoria |
| [0016](ADR-0016-defesa-deterministica.md) | Convencer o modelo não desativa nenhuma proteção |
| [0024](ADR-0024-auto-modificacao-e-nucleo-de-confianca.md) | O Zordon não consegue enfraquecer as próprias proteções |
