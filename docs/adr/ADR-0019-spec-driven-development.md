---
document: adr-0019
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,spec,processo]
specId: null
---

# ADR-0019 — Spec-Driven Development como processo obrigatório

**Status:** Aceito · 2026-09-17

## Contexto

O Zordon é construído em grande parte por agentes de IA. Um agente é excelente
em produzir código plausível e péssimo em descobrir sozinho qual é o problema
certo: sem especificação, ele preenche lacunas com suposições — e com suposições
diferentes a cada execução.

O sintoma clássico disso é um repositório que cresce rápido e funciona mal:
muito código, pouca coerência, e ninguém consegue dizer por que um componente é
como é.

## Alternativas

**A. Código direto, documentação depois.** Rápido no começo. A documentação
"depois" não acontece, e seis meses depois cada mudança vira arqueologia. Para um
repositório mantido por agentes é pior ainda: não há ninguém com a memória
institucional para compensar.

**B. Ticket informal + implementação.** Comum e suficiente para equipes humanas
pequenas, onde a conversa de corredor preenche o que o ticket não diz. Um agente
não tem corredor.

**C. SPEC obrigatória antes de implementar**, com critérios de aceite que viram
testes.

## Decisão

**Alternativa C**, com o fluxo:

```text
IDEIA → SPEC → REVIEW → PLANO → IMPLEMENTAÇÃO → TESTES → VALIDAÇÃO → DOCUMENTAÇÃO
```

Pontos que tornam isto executável em vez de aspiracional:

1. **A tabela de quando a SPEC é obrigatória é curta e específica**
   ([SDD §4](../process/spec-driven-development.md#4-quando-uma-spec-é-obrigatória)).
   Correção de bug e refatoração não exigem SPEC. Sem esse limite, o processo
   vira burocracia e as pessoas o contornam.
2. **Um agente recusa implementar SPEC que não esteja `APPROVED`.** É verificação,
   não recomendação.
3. **Critério de aceite vira teste**, ligado por `@AcceptanceCriteria`. O build
   reprova SPEC `DONE` com critério sem teste.
4. **SPEC rejeitada não é apagada.** O registro do que decidimos não fazer tem o
   mesmo valor que o registro do que fizemos — pela mesma razão que um ADR aceito
   nunca é editado.

## Consequências

**Positivas.** O agente implementa um alvo definido em vez de um alvo imaginado.
Discordar custa minutos, não dias. Critérios de aceite dão um portão objetivo de
conclusão, o que ataca diretamente o modo de falha mais comum de desenvolvimento
assistido por IA: declarar vitória quando compila. A SPEC também é o documento
que o [Context Router](../rag/context-router.md) entrega — ela paga duas vezes.

**Negativas.** Mais lento para mudanças pequenas — mitigado pela tabela de §4.
Exige disciplina para manter SPEC e código em sincronia — mitigado pela
[rastreabilidade](../process/traceability.md) verificada no build. Uma SPEC
ruim produz implementação ruim com aparência de rigor.

**A última negativa é a mais séria** e não tem mitigação automática: revisão por
`ArchitectureAgent` e `SecurityAgent` reduz, não elimina. A defesa real é a
seção "não escopo" do template, que força quem escreve a declarar o que
deliberadamente não está resolvendo.

## Relação com os ADRs de segurança

Uma SPEC é **conteúdo**, não autoridade:

```text
Security Policy > SPEC aprovada > Arquitetura/ADR > contexto RAG > raciocínio do agente
```

Uma SPEC não concede permissão, não relaxa classificação de risco e não cria
exceção à política. Se precisa disso, precisa antes de um ADR e de alteração da
política pelo usuário no sistema de arquivos.
