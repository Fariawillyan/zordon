---
document: spec-template
module: process
section: template
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [spec,template,formato]
specId: null
---

# Template de SPEC

Copie o bloco abaixo para `docs/specs/<módulo>/SPEC-XXX-<slug>.md`.

> Os caminhos `../../` dentro do bloco estão corretos **para o destino**
> (`docs/specs/<módulo>/`), não para este arquivo. Ajuste apenas se colocar a
> SPEC em outra profundidade.

Seções sem conteúdo são preenchidas com **"Não se aplica"** e a razão. Seção
apagada reprova a validação do build — o vazio é informação, a ausência é
descuido.

---

```markdown
---
document: spec-XXX
module: <core|voice|agents|mcp|memory|automation|ui|security|defense|rag>
section: spec
version: 1
updatedAt: AAAA-MM-DD
securityLevel: <public|internal|restricted>
tags: [...]
specId: SPEC-XXX
---

# SPEC-XXX — <Nome>

| Campo | Valor |
|---|---|
| **Status** | DRAFT \| REVIEW \| APPROVED \| IMPLEMENTING \| DONE \| BLOCKED \| REJECTED |
| **Owner** | quem responde por esta SPEC |
| **Agente responsável** | agente que implementa |
| **Revisores** | ArchitectureAgent, SecurityAgent (quando aplicável) |
| **Marco** | M0…M7 |
| **Supera** | SPEC-YYY, se houver |

## 1. Objetivo
Uma frase. O que passa a ser possível depois disto.

## 2. Problema
O que dói hoje. Com evidência — medição, caso de uso, incidente. Sem "seria bom
ter".

## 3. Escopo
Lista do que esta SPEC entrega.

## 4. Não escopo
Lista do que ela deliberadamente **não** entrega, com a razão. É a seção que mais
economiza tempo em revisão.

## 5. Arquitetura
Onde isto vive. Quais módulos são tocados. Referencie, não repita:
[Arquitetura](../../architecture/overview.md).

## 6. Fluxo
Diagrama ou sequência numerada do caminho feliz, mais os desvios relevantes.

## 7. Interfaces
Assinaturas novas ou alteradas. Referencie
[Interfaces do núcleo](../../api/core-interfaces.md) quando estender algo existente.

## 8. Eventos
Eventos ZWP publicados ou consumidos, com tópico e payload.
Ver [ZWP §6](../../api/zwp-protocol.md#6-eventos).

## 9. Dados
Esquema, migração, retenção, índices. Referencie
[Memória](../../specs/memory/design.md) quando aplicável.

## 10. Segurança
Obrigatório responder:
- Que `Effect` esta funcionalidade produz?
- Qual a classificação de risco, e ela varia por argumento?
- Introduz superfície nova para conteúdo não confiável?
- Toca segredo, credencial ou caminho sensível?
- Alguma ação aqui é autônoma? Se sim, como é comunicada?

Se todas as respostas forem triviais, escreva-as assim mesmo. Ver
[Segurança](../../security/model.md).

## 11. Permissões
Quem pode executar isto: qual agente, qual teto, quais capacidades e prazos.

## 12. Observabilidade
Métricas, eventos de log e spans novos. Ver
[Observabilidade](../../operations/observability.md).

## 13. Casos de erro
Tabela: falha → comportamento → o que o usuário vê. Inclua timeout,
indisponibilidade de dependência, entrada inválida e cancelamento.

## 14. Testes
Que tipos de teste cobrem isto e onde eles ficam. Ver
[Estratégia de testes](../../testing/strategy.md).

## 15. Critérios de aceite
Numerados `CA-1`, `CA-2`… Cada um verificável e ligado a um teste por
`@AcceptanceCriteria("SPEC-XXX/CA-n")`.

## 16. Impacto em outros módulos
O que quebra, o que precisa mudar junto, que documentação será atualizada.

## 17. Dependências
Outras SPECs, ADRs ou marcos que precisam existir antes.
```

---

## Como escrever bons critérios de aceite

O critério de aceite é a parte da SPEC que vira teste. Ele precisa ser
**verificável por alguém que não escreveu a SPEC**.

| Ruim | Bom |
|---|---|
| "Deve ser rápido" | "`CA-1` p95 do handshake abaixo de 250 ms com 3 clientes conectados" |
| "Deve tratar erro" | "`CA-2` Se o servidor MCP não responde em 30 s, a chamada falha com `ERR_TOOL_FAILED` e o agente recebe o erro como `tool_result`" |
| "Deve ser seguro" | "`CA-3` Uma ferramenta cujo `Effect` excede o teto do agente não aparece na seleção enviada ao modelo" |
| "Usuário é notificado" | "`CA-4` A notificação é persistida antes da execução; matar o processo entre as duas deixa a mensagem pendente na fila" |

Forma recomendada: **Dado … quando … então …**.

## Numeração

`SPEC-001` em diante, sequencial e global — não por módulo. O número nunca é
reutilizado, nem quando a SPEC é rejeitada.
