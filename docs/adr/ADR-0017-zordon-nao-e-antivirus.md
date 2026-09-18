---
document: adr-0017
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,zordon,nao,e,antivirus]
specId: null
---

# ADR-0017 — O Zordon não é um antivírus: escopo honesto da detecção

**Status:** Aceito · 2026-09-17

## Contexto

O requisito é que o Zordon proteja o computador continuamente contra malware,
vírus, comportamento suspeito, invasões, brute force, exploração de serviços,
tráfego anormal, DDoS local, MCPs maliciosos, plugins comprometidos, prompt
injection, agentes hostis e outras IAs.

Parte dessa lista o Zordon consegue fazer melhor do que qualquer produto
existente. Outra parte ele **não consegue fazer**, e é importante registrar qual
é qual antes de escrever código — porque a falha aqui não seria técnica, seria de
honestidade: uma proteção que o usuário acredita ter, mas não tem, é pior do que
a ausência declarada dela.

### Limites técnicos, concretos

| Limitação | Consequência |
|---|---|
| Processo em modo usuário dentro de uma VM (WSL2) | Não intercepta chamadas de sistema do Windows no kernel |
| Sem driver, sem minifilter, sem callback de kernel | Não bloqueia escrita de arquivo no Windows em tempo real |
| Sem banco de assinaturas | Não identifica malware conhecido por assinatura |
| Sem motor de emulação/sandbox de binário | Não detona amostra para análise |
| `/mnt/c` é 9p, ~47× mais lento em metadados ([R9](../architecture/windows-wsl.md#r9--io-em-mntc-é-ordens-de-grandeza-mais-lento)) | Varredura completa do disco Windows é inviável |
| Sem privilégio administrativo por padrão | Não altera política de sistema do Windows |

## Decisão

Escopo declarado em **três anéis**, com honestidade sobre a força de cada um:

```text
ANEL 1 — Superfície de IA        →  o Zordon é a AUTORIDADE
ANEL 2 — Comportamento do host   →  detector COMPLEMENTAR
ANEL 3 — Malware clássico        →  ORQUESTRADOR de resposta
```

**Anel 1 — MCP, agentes, ferramentas, prompt injection, exfiltração,
escalonamento, IAs hostis.** Aqui o Zordon vê tudo, decide tudo e é o único
componente capaz de fazê-lo: nenhum antivírus inspeciona uma chamada de
ferramenta MCP ou percebe um agente mudando de objetivo. Recebe a maior parte do
investimento de engenharia.

**Anel 2 — processos, rede, arquivos em workspaces, serviços, Docker.** Detecção
por **comportamento e correlação** sobre a telemetria que o Zordon já coleta para
o monitoramento. Bom em anomalia (acesso a credencial, modificação em massa,
beaconing, persistência, brute force). Cego para o que não se manifesta em
comportamento observável.

**Anel 3 — vírus e malware conhecido.** O Zordon **não detecta**. Ele consome
eventos do Windows Defender e da telemetria do Windows via `zordon-host`,
correlaciona com o que sabe, dá contexto e histórico, e executa a resposta. Papel
de orquestrador, explicitamente.

### Compromissos que derivam

1. **Nenhum texto do produto — UI, documentação, README, notificação — afirma que
   o Zordon substitui um antivírus.** A tela de Segurança mostra o estado do
   Windows Defender ao lado do estado do Defense Engine, deixando claro quem faz
   o quê.
2. **Se o Defender estiver desativado, o Zordon avisa** e não finge cobrir a
   lacuna.
3. **Nenhuma varredura completa de disco.** Inspeção é sob demanda, por evento ou
   por caminho, sempre orçada.
4. **Detecção não pode custar o requisito de <3% de CPU em repouso**
   ([Visão §5](../vision.md#recursos)). Uma defesa que deixa a máquina
   lenta é uma defesa que o usuário desliga — e aí a proteção real vira zero.

## Consequências

**Positivas.** O usuário sabe exatamente o que tem. O investimento vai para onde
o Zordon é insubstituível, em vez de competir mal com produtos maduros. Não há
promessa que a arquitetura não sustenta. A integração com o Defender é
colaboração, não duplicação.

**Negativas.** Alguém que esperava um antivírus vai se decepcionar. A cobertura
do Anel 3 depende de um produto de terceiro estar ativo e saudável. Lacunas
existem e estão documentadas — o que é desconfortável de escrever e correto de
publicar.

**Reavaliação.** Se um dia o Zordon tiver um componente Windows com privilégio
elevado e integração a ETW/Sysmon de forma profunda, o Anel 2 pode crescer
bastante. Isso exigiria um ADR próprio: um processo com privilégio
administrativo residente muda o modelo de ameaça inteiro, e o ganho precisaria
justificar o novo risco.
