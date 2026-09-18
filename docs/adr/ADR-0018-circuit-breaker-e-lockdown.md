---
document: adr-0018
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,circuit,breaker,e,lockdown]
specId: null
---

# ADR-0018 — Circuit breaker por sujeito e Defense Lockdown

**Status:** Aceito · 2026-09-17

## Contexto

Quando um componente do Zordon se comporta de forma anômala — um agente numa
sequência estranha de ferramentas, um MCP violando o que declarou, um processo
tocando credenciais — é preciso parar o dano sem derrubar o sistema inteiro, e
sem tomar decisões irreversíveis com base em um sinal que pode ser falso
positivo.

Há também um caso categoricamente diferente: quando a evidência indica que uma
**premissa do sistema quebrou** (a auditoria foi adulterada, a política foi
tocada, o próprio binário mudou). Aí não se trata de conter um componente — nada
é confiável, inclusive os outros detectores.

## Decisão

Dois mecanismos, para os dois casos.

### `ZordonSafetyCircuitBreaker` — contenção por sujeito

Granularidade é o **sujeito** (agente, MCP, ferramenta, processo), não o sistema.

```text
   CLOSED ──── anomalia ────► OPEN ──── usuário autoriza ────► HALF_OPEN
      ▲                                                            │
      └───────────── comportamento normal na janela de prova ──────┘
```

Sequência ao abrir, nesta ordem: bloquear novas ações → revogar capacidades →
interromper execução (cancelar, não matar) → desconectar componente → preservar
evidências → registrar → notificar.

**Só o usuário move `OPEN` → `HALF_OPEN`.** Não há recuperação automática, não há
timeout de fechamento. Um disjuntor que fecha sozinho é um disjuntor que o
atacante simplesmente espera. Em `HALF_OPEN`, o sujeito opera com capacidades
mínimas e cada ação é confirmada individualmente.

A ordem importa: bloquear **antes** de revogar, revogar **antes** de interromper,
e preservar evidências **antes** de notificar. Interromper antes de bloquear
deixaria uma janela para a última ação passar.

### Defense Lockdown — contenção do sistema

| Subsistema | Em lockdown |
|---|---|
| Agentes, MCP | Somente leitura |
| Execução de processo, escrita de arquivo, rede de ferramenta | Bloqueadas |
| Automações | Suspensas |
| Monitoramento, detecção, notificação, conversa | **Ativos** |

**Entrada:** automática em `ai.policy-tamper`, `integrity.audit-chain`,
`integrity.self`, ou em duas ou mais ameaças CRITICAL não resolvidas em 10
minutos. Ou manual, pelo usuário.

**Saída: somente o usuário, na interface, com confirmação explícita.** Sem
timeout, sem saída automática, sem método ZWP que um agente alcance. Se o Zordon
pudesse sair sozinho do lockdown, o lockdown não protegeria contra o cenário que
o motivou — um Zordon comprometido sairia imediatamente.

O "Pausar Zordon" do tray e o Defense Lockdown são **o mesmo mecanismo**, com
rótulos diferentes conforme quem o acionou. Manter dois modos parecidos mas
distintos criaria a dúvida "estou pausado ou em lockdown?" no pior momento
possível.

## Alternativas descartadas

**Matar o componente.** `SIGKILL` no processo, remover o MCP da configuração,
apagar o agente. Destrói evidência, é irreversível e desproporcional a um sinal
que pode ser falso positivo. Suspender preserva tudo e é desfazível.

**Disjuntor com recuperação automática (padrão clássico).** No padrão de
resiliência, o disjuntor testa sozinho depois de um tempo, porque a falha
presumida é transitória e não adversária. Aqui a falha presumida **é**
adversária. Recuperação automática é exatamente o que um atacante paciente
explora.

**Lockdown como postura padrão.** Seguro e inútil: o usuário desligaria o Zordon
no primeiro dia. Lockdown é resposta a ameaça, não modo de operação.

## Consequências

**Positivas.** Contenção proporcional: um agente suspeito não derruba o
monitoramento nem a voz. Evidência preservada em todos os casos. Falso positivo
custa uma autorização, não um reinstala. O usuário permanece no controle da
recuperação — que é onde ele precisa estar.

**Negativas.** Um falso positivo interrompe trabalho até o usuário intervir, e se
ele estiver ausente, fica interrompido. Exige a tela de Segurança para gerenciar
disjuntores abertos. O lockdown automático pode disparar em hora ruim.

**Mitigações.** Limiares conservadores, revisáveis em casos golden. Notificação
imediata com as evidências e a opção "liberar com supervisão". Métrica
`zordon.breaker.opened` por sujeito e motivo: se um agente abre disjuntor toda
semana, o limiar está errado — e é a métrica que torna isso visível em vez de
virar irritação acumulada.
