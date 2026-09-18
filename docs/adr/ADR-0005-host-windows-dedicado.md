---
document: adr-0005
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,host,windows,dedicado]
specId: null
---

# ADR-0005 — Processo Windows headless separado da interface

**Status:** Aceito · 2026-09-17
**Desvia do briefing original**, que previa dois processos.

## Contexto

O briefing definiu dois componentes: `Zordon Desktop` (JavaFX) no Windows e
`Zordon Core` no WSL2, com o requisito explícito de que o Zordon permaneça ativo
com a interface fechada.

Mas duas capacidades **só podem existir no Windows**:

1. **Áudio** — o WSL não tem dispositivo de áudio confiável
   ([R6](../architecture/windows-wsl.md#r6--não-há-áudio-confiável-dentro-do-wsl)).
2. **Operações Windows** — abrir aplicação, clipboard, screenshot, notificação
   nativa, foco de janela. E o interop (`powershell.exe` de dentro do WSL) não
   funciona sob systemd
   ([R5](../architecture/windows-wsl.md#r5--wsl_interop-não-existe-dentro-de-um-serviço-systemd)).

Se essas capacidades vivem no processo JavaFX, elas morrem quando a interface
morre — contradizendo o requisito central do produto.

## Alternativas

**A. Dois processos, com o desktop sempre residente no tray.** Fiel ao briefing.
Funciona enquanto o usuário não encerrar o processo. Mas: uma stack JavaFX
completa fica residente (~400 MB, contexto gráfico, driver de GPU) só para
manter o microfone vivo; um crash da UI (JavaFX e driver gráfico não são
infalíveis) derruba a voz junto; e "encerrar a interface" passa a ser uma ação
destrutiva que o usuário não espera que seja.

**B. Dois processos, com áudio e bridge no núcleo via interop.** Elimina o
processo extra. Mas interop não funciona sob systemd, e o WSL não tem áudio.
Tecnicamente inviável, não apenas ruim.

**C. Três processos: núcleo (WSL) + host headless (Windows) + desktop (Windows).**
O host, pequeno e sem UI, detém áudio e bridge, sobe no logon e nunca fecha. O
desktop é puramente descartável.

## Decisão

**Alternativa C.**

| Processo | Vive enquanto | Se cair |
|---|---|---|
| `zordon-core` | O WSL estiver de pé | Sistema fora do ar; `Restart=always` |
| `zordon-host` | O usuário estiver logado | Perde voz e ações Windows; automações e monitoramento seguem |
| `zordon-desktop` | O usuário quiser | Nada para de funcionar |

O `zordon-host` também acumula duas responsabilidades que se encaixam
naturalmente: **supervisor do WSL** (verifica a saúde do núcleo e acorda a VM
quando necessário — [R1](../architecture/windows-wsl.md#r1--o-wsl-não-sobe-no-boot-do-windows))
e **detector de eventos de energia** (retorno de suspensão, tela cheia exclusiva).

Ambos só podem ser feitos por um processo Windows residente. Juntando isso às
duas capacidades originais, o host deixa de ser "um processo a mais" e passa a ser
o componente que faltava.

## Consequências

**Positivas.** Voz e ações Windows independem da UI. A UI é genuinamente
descartável e pode ser reescrita ou substituída. Isolamento de falha: um crash
de JavaFX não afeta nada. Memória residente muito menor com a janela fechada
(~150 MB em vez de ~550 MB). Fronteira de segurança explícita: o host é o único
com poder no Windows, e sua superfície é pequena e auditável.

**Negativas.** Três processos para instalar, versionar e diagnosticar. Uma
conexão ZWP a mais. Um desvio do briefing que precisa ser comunicado.

**Mitigação do custo de complexidade.** Os dois processos Windows saem do mesmo
MSI, compartilham `zordon-api` e o cliente ZWP, e são versionados juntos. Do
ponto de vista do usuário, existe "o Zordon" — a separação é interna.

**Nota de implementação.** No M1, `zordon-host` pode nascer como um módulo dentro
do processo do desktop, desde que a fronteira (cliente ZWP próprio, capacidades
declaradas separadamente) já exista. Separar em dois processos vira uma mudança
de empacotamento, não de arquitetura. O que **não** é aceitável é a UI chamar
diretamente uma API de áudio ou de Windows sem passar pela fronteira — isso
tornaria a separação futura uma reescrita.
