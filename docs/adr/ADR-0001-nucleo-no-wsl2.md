---
document: adr-0001
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,nucleo,no,wsl2]
specId: null
---

# ADR-0001 — Núcleo no WSL2 sob systemd

**Status:** Aceito · 2026-09-17

## Contexto

O Zordon precisa de um processo residente que sobreviva ao fechamento da
interface e continue executando automações e monitoramento. Ele pode morar no
Windows ou no WSL2.

O núcleo depende de um ecossistema que é substancialmente melhor no Linux:
modelos de voz (ONNX, CTranslate2, Piper), servidores MCP (quase todos
distribuídos como pacotes npm/pip pensados para Unix), ferramentas de
desenvolvimento (Git, Docker, Maven) e supervisão de processo (systemd). Ao
mesmo tempo, o WSL2 tem características hostis para um serviço permanente:
ele não sobe no boot, pode ser derrubado por fora, e não tem áudio.

## Alternativas

**A. Núcleo no Windows como serviço.** Sobe no boot de forma nativa e
confiável, tem áudio direto, e a Windows Bridge deixa de existir. Mas: a stack de
voz e MCP fica hostil (dependências nativas Python no Windows são um problema
recorrente), CUDA para STT é mais chato, Docker e ferramentas de dev já vivem no
WSL do usuário, e supervisão de processo no Windows é inferior ao systemd.

**B. Núcleo no WSL2 sob systemd.** Ecossistema natural, supervisão excelente,
proximidade com Docker e projetos. Mas: exige resolver o ciclo de vida do WSL, a
rede, o interop e a ausência de áudio.

**C. Híbrido — inteligência no WSL, residência no Windows.** Um serviço Windows
mínimo mantendo estado e delegando trabalho ao WSL. Combina o pior dos dois:
estado dividido entre dois lugares, duas fontes de verdade, e a complexidade de
sincronizá-las.

## Decisão

**Alternativa B.** O núcleo roda no WSL2 como `zordon.service` sob systemd, com
`Restart=always` e `Type=notify`.

Os problemas do WSL2 são reais, mas são **finitos e conhecidos**, e cada um tem
mitigação concreta documentada em [Windows↔WSL](../architecture/windows-wsl.md):

| Problema | Mitigação |
|---|---|
| Não sobe no boot | Tarefa agendada no logon + supervisor no host + `vmIdleTimeout=-1` |
| Derrubado por fora | Supervisor religa; estado é durável no momento da escrita |
| Rede instável | Modo espelhado quando disponível; arquivo de endpoint sempre |
| Interop quebrado sob systemd | Não usamos interop; bridge via ZWP inverso |
| Sem áudio | Captura no Windows ([ADR-0009](ADR-0009-captura-windows-inferencia-wsl.md)) |

Já a alternativa A teria um problema **não-finito**: manter a stack de voz e MCP
funcionando no Windows seria um custo recorrente e crescente a cada dependência
nova.

## Consequências

**Positivas.** Ecossistema de IA e dev nativo. systemd resolve supervisão,
reinício, ordem de inicialização e endurecimento. Núcleo portável para um Linux
de verdade sem alteração. Isolamento do sistema Windows por construção.

**Negativas.** Três camadas para garantir que o WSL está de pé. Um processo
Windows adicional se torna obrigatório ([ADR-0005](ADR-0005-host-windows-dedicado.md)).
Qualquer operação Windows paga uma ida e volta de rede. Depuração atravessa dois
sistemas operacionais.

**Riscos aceitos.** Se a Microsoft mudar o comportamento do WSL de forma
incompatível, o impacto é alto. O mitigador é que o núcleo é Java puro e não usa
nada específico de WSL — migrá-lo para um contêiner ou uma VM real seria uma
mudança de empacotamento, não de código.
