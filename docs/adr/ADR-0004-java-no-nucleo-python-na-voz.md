---
document: adr-0004
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,java,no,nucleo,python,na,voz]
specId: null
---

# ADR-0004 — Java no núcleo, Python como sidecar de voz

**Status:** Aceito · 2026-09-17

## Contexto

O núcleo precisa de uma linguagem. O briefing sugeriu Java implicitamente (as
interfaces propostas são Java, e a UI é JavaFX). Mas o subsistema de voz — VAD,
wake word, STT, TTS — tem seu ecossistema esmagadoramente em Python/ONNX:
Silero VAD, openWakeWord, faster-whisper e Piper são todos Python ou têm Python
como caminho principal.

## Alternativas

**A. Tudo em Java.** Um processo, uma linguagem, um build. Voz via ONNX Runtime
para Java (existe e funciona). Mas: faster-whisper é CTranslate2, sem binding
Java mantido; Piper idem; openWakeWord traz pós-processamento em Python. Seria
reimplementar e manter portes de quatro projetos que evoluem rápido.

**B. Tudo em Python.** Ecossistema de IA perfeito. Mas: `zordon-api` precisa ser
compartilhado com o JavaFX, o que exigiria duas definições do protocolo; a
concorrência estruturada do núcleo (dezenas de agentes, ferramentas e watchers
simultâneos) é muito melhor servida por threads virtuais do que por asyncio com
GIL; e o usuário é desenvolvedor Java.

**C. Java no núcleo + sidecar Python só para voz.** Cada parte na linguagem certa.
Custo: um processo a mais e um IPC a manter.

## Decisão

**Alternativa C.**

- **Núcleo em Java 25.** Threads virtuais resolvem o modelo de concorrência do
  núcleo com elegância (cada turno, agente e ferramenta é um thread virtual
  bloqueante, sem async coloring). `record` e `sealed interface` modelam
  protocolo e eventos com precisão. Compartilha `zordon-api` com o JavaFX,
  eliminando a duplicação do protocolo. É a linguagem do usuário.
- **Voz em Python**, como processo filho supervisionado, comunicando por socket
  de domínio Unix.

A fronteira é escolhida onde é mais barata: o sidecar troca **áudio e texto**,
nada mais. Ele não conhece agentes, ferramentas, permissões nem memória. Isso o
mantém pequeno, substituível e testável isoladamente.

## Consequências

**Positivas.** Cada ecossistema no seu terreno. Atualizar o modelo de STT é
mexer em um `requirements.lock`, não em código Java. Falha do sidecar degrada só
a voz. O núcleo permanece uma aplicação Java convencional, fácil de depurar.

**Negativas.** Dois runtimes para instalar e versionar. Um IPC a mais.
Provisionar o venv é responsabilidade do instalador. Empacotamento fica mais
complexo.

**Mitigações.** O venv é criado a partir de um lockfile na instalação, nunca
resolvido em tempo de execução. O sidecar tem contrato mínimo e versionado. Se
ele não subir, o núcleo funciona por texto e reporta `voice: unavailable` —
degradação graciosa, não falha.

**Sobre Java 25 especificamente:** threads virtuais são a razão técnica principal.
Um assistente com 6 agentes, 7 MCP servers, 10 watchers e um pipeline de áudio
tem centenas de operações bloqueantes concorrentes. Com threads de plataforma,
isso exigiria pools cuidadosamente dimensionados; com threads virtuais, é código
bloqueante simples.
