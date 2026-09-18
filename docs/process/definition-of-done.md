---
document: definition-of-done
module: process
section: dod
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [dod,qualidade,aceite,portao]
specId: null
---

# Definition of Done

## 1. Objetivo

Definir, sem ambiguidade, quando uma tarefa está concluída.

## 2. A regra principal

> **Documentação não é subproduto do código. Ela faz parte da implementação.**

Uma tarefa está concluída quando, e apenas quando:

```text
SPEC + implementação + testes + segurança + documentação + RAG + auditoria
```

Faltando qualquer item, a tarefa permanece **incompleta** — não "quase pronta",
não "pendente de ajuste depois". Incompleta.

Tratar documentação como algo que se faz "se sobrar tempo" é o que produz um
repositório onde ninguém sabe por que as coisas são como são. No Zordon isso é
mais grave que o normal: a documentação **alimenta o RAG, que alimenta os
agentes** que constroem o produto ([ADR-0020](../adr/ADR-0020-rag-como-conhecimento.md)).
Documentação desatualizada não é dívida estética — é conhecimento errado sendo
servido com confiança para quem escreve o código seguinte.

## 3. O problema que isto resolve

**Compilar não é concluir.** Um agente de IA, deixado por conta própria, declara
vitória quando o código compila — porque compilar é o sinal mais fácil de
observar. É o modo de falha mais comum de desenvolvimento assistido por IA, e ele
produz um repositório que cresce rápido e funciona mal.

## 4. Os dez portões

Uma tarefa está `DONE` quando **todos** são verdadeiros:

```text
   ✓ SPEC validada                    o que foi feito é o que foi especificado
   ✓ código implementado              completo, não o caminho feliz apenas
   ✓ Clean Code validado              padrões de code-standards.md
   ✓ testes passando                  incluindo os dos critérios de aceite
   ✓ CodeReviewAgent aprovado         sem smell bloqueante pendente
   ✓ security review quando aplicável SecurityAgent, nas categorias de §6
   ✓ documentação atualizada          docs afetados, não só javadoc
   ✓ RAG reindexado                   chunks alterados re-embedados
   ✓ auditoria registrada             plano, decisão, arquivos e resultado
   ✓ token usage registrado           custo da tarefa contabilizado
```

Nenhum é opcional. Nenhum é "fica para depois" — "depois" não existe em um
repositório mantido por agentes, porque não há ninguém com a memória do débito.

## 4. Verificação

Cada portão tem um sinal objetivo, não uma opinião:

| Portão | Como se verifica |
|---|---|
| SPEC validada | SPEC em `APPROVED`+, e todo `CA-n` tem teste ligado |
| Código implementado | Casos de erro da SPEC §13 têm caminho implementado |
| Clean Code | Métricas de [padrões](code-standards.md#5-complexidade) dentro do limite |
| Testes | Suite verde, cobertura mínima do módulo atingida |
| Code review | `CodeReviewAgent` sem achado de severidade bloqueante |
| Security review | `SecurityAgent` aprovou, quando a SPEC toca as categorias de §5 |
| Documentação | Nenhum documento marcado como afetado ficou sem atualização |
| RAG | `indexedAt` dos chunks afetados ≥ timestamp do commit |
| Auditoria | Existe `ChangeAudit` com `userMessageId`, arquivos e resultado |
| Tokens | Existe registro em `TokenUsageService` para o `taskId` |

## 6. Quando a revisão de segurança é obrigatória

Não é para toda tarefa. É obrigatória quando a mudança:

- toca qualquer caminho do **núcleo de confiança**
  ([Auto-modificação §4](self-modification.md#4-núcleo-de-confiança)) — nesse
  caso é sempre RED e termina em PR com revisão humana;
- adiciona ou altera uma Skill, ferramenta, detector ou agente;
- muda classificação de risco, `Effect` ou capacidade;
- toca segredo, credencial, caminho sensível ou política;
- introduz superfície para conteúdo não confiável (arquivo, rede, MCP);
- adiciona ação autônoma ou altera o que é comunicado ao usuário;
- adiciona dependência.

## 7. O que não conta como pronto

| Não é pronto | Por quê |
|---|---|
| "Compilou" | Ver §2 |
| "Testei manualmente" | Não é reexecutável; não protege contra regressão |
| "Os testes que escrevi passam" | Se os testes só cobrem o caminho feliz, eles medem otimismo |
| "Documento depois" | Documentação adiada é documentação que não acontece |
| "TODO no código" | Um `TODO` é débito sem dono nem prazo. Se importa, vira SPEC; se não, some |
| "Documentação depois do merge" | A documentação é parte da implementação (§2), não uma etapa posterior |
| "Funciona na minha máquina" | Especialmente traiçoeiro aqui: WSL e Windows divergem — ver [Windows↔WSL](../architecture/windows-wsl.md) |

## 8. Dependências

- [Spec-Driven Development](spec-driven-development.md)
- [Auto-modificação](self-modification.md) — quando a tarefa altera um projeto
- [Padrões de código](code-standards.md)
- [Estratégia de testes](../testing/strategy.md)
- [Rastreabilidade](traceability.md)
- [Uso de tokens](../operations/token-usage.md)

## 9. Riscos

| Risco | Mitigação |
|---|---|
| Oito portões tornam qualquer mudança cara | Bug fix e refatoração não exigem SPEC nem security review; a maioria dos portões é automática |
| Portão automatizável virar carimbo manual | Sete dos nove sinais de §4 são verificados no build |
| `CodeReviewAgent` aprovar por complacência | Achados bloqueantes são categorias fechadas, não julgamento livre — [padrões §4](code-standards.md#4-code-smells) |

## 10. Critérios de aceite

- `CA-1` Uma tarefa não é marcada `DONE` com qualquer portão de §4 falso.
- `CA-2` Os portões automatizáveis são verificados no build, não declarados.
- `CA-3` É possível listar, para qualquer tarefa `DONE`, a evidência de cada
  portão.
- `CA-4` Toda tarefa que alterou um projeto tem `ChangeAudit` com
  `userMessageId` não nulo.
