---
document: adr-0041
module: adr
section: decision
version: 1
updatedAt: 2026-09-23
securityLevel: internal
tags: [adr,decisao,seguranca,permissao,oppressor,senha-mestre]
specId: null
---

# ADR-0041 — A senha mestre suspende o motor de permissão, e o lockdown não

**Status:** Aceito · 2026-09-23

## Contexto

O Zordon assume usuário único e local ([Visão §8](../vision.md)), mas cobra
confirmação a cada ação acima de GREEN. Quem confirma é a mesma pessoa que deu
a ordem. Numa sessão longa, isso deixa de ser controle e vira ruído: a pessoa
aprova sem ler, e o reflexo treinado de clicar "sim" é pior do que não
perguntar.

O pedido é um modo de execução direta, ativado por senha mestre, em que a
ordem do usuário é executada sem intermediação — e que seja uma mudança real do
mecanismo de execução, não um tema vermelho com o mesmo comportamento por baixo.

Isso conflita de frente com o [ADR-0030](ADR-0030-origem-da-ordem.md), que faz a
origem da ordem limitar o risco que ela autoriza.

## Alternativas

| Alternativa | Por que não |
|---|---|
| Lista de ferramentas pré-aprovadas | Resolve o caso fácil e deixa o difícil; a fricção volta na primeira ação fora da lista |
| Sessão com teto elevado por tempo | É o mesmo modo, com o pior de dois mundos: ainda pergunta, e ainda expira no meio do trabalho |
| Modo total, inclusive sobre o lockdown | O lockdown é acionado sozinho quando a defesa detecta adulteração; desligá-lo com uma senha digitada antes da detecção é desligar justamente o que sobrou |
| **Modo total sob senha mestre, com o lockdown preservado** | — |

## Decisão

Uma senha mestre ativa o OPPRESSOR MODE. Enquanto ativo, o `Gatekeeper` libera
toda ação sem consultar o `PermissionEngine`: sem risco calculado, sem teto por
origem, sem confirmação. A senha é a autorização, e não há segunda pergunta
depois dela.

Três limites, e só três:

1. **O lockdown vence o modo.** Um kill switch que um modo desliga não é um
   kill switch. Vale também para o lockdown automático por cadeia de auditoria
   quebrada, que é o caso em que a senha digitada antes deixou de provar algo.
   Sair do lockdown continua sendo ação na tela.
2. **O estado não sobrevive ao reinício.** O seguro do lockdown é continuar
   fechado, então ele persiste; o seguro daqui é acordar fechado.
3. **O modo não toca no modelo de IA.** Ele muda o que o Zordon executa, não o
   que ele pensa. Não há instrução de sistema pedindo ao modelo que não recuse:
   seria ineficaz, e o provedor tem política própria
   ([ADR-0026](ADR-0026-provider-agnostico.md)).

## Consequências

- O teto por origem do [ADR-0030](ADR-0030-origem-da-ordem.md) fica suspenso
  enquanto o modo estiver ativo. `voice`, `automation`, `agent` e `autonomous`
  passam a valer como `ui`.
- **Risco aceito, explicitamente:** com o teto suspenso, uma instrução plantada
  em conteúdo que o Zordon leia — página, e-mail, README, saída de ferramenta —
  vale como ordem, com as permissões do processo e sem confirmação. É o cenário
  de [Modelo §6](../security/model.md#6-prompt-injection). A mitigação é
  operacional: sessões curtas, supervisionadas, com indicação vermelha
  permanente na janela para que o modo não seja esquecido ligado.
- A auditoria continua gravando tudo, com `by = oppressor`, e continua não
  sendo etapa de aprovação.
- O `Gatekeeper` passa a ter duas portas de saída em vez de uma; ser o caminho
  único continua sendo o que faz o modo valer para o processo inteiro.
