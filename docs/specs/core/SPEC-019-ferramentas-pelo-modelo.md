---
document: spec-019
module: core
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: internal
tags: [spec,ia,ferramentas,tool-use,laco,m4]
specId: SPEC-019
---

# SPEC-019 — Ferramentas pelo modelo: o laço do turno

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaAgent` |
| **Revisores** | SecurityAgent, ArchitectureAgent |
| **Marco** | M4 (pré-requisito: o modelo precisa escolher ferramentas antes de existirem as do MCP) |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

O modelo passa a escolher e chamar as ferramentas do Zordon durante um turno:
"quanto de disco eu tenho?" vira `system.metrics`, e a resposta usa o resultado.
Cada chamada passa pelo caminho da SPEC-016: nada executa fora do `Gatekeeper`.

## 2. Problema

Hoje só as rotas rápidas chamam ferramentas. O modelo responde texto e não
alcança nada da máquina. O critério do M4 ("quais containers estão rodando?"
sem código novo) depende de o modelo escolher a ferramenta certa.

## 3. Escopo

**Oferta de ferramentas**

- Cada `Tool` declara o esquema dos argumentos (`inputSchema`, JSON Schema).
- O turno oferece no máximo 12 ferramentas: as fixas (`system.metrics`,
  `fs.list`, `fs.read`, `app.open`) e as que casam com as palavras do pedido
  (nome e descrição). A seleção semântica por embeddings entra no M5.

**Laço** (`TurnManager`)

1. Modelo → a resposta pede ferramentas (`StopReason.TOOL_USE`).
2. Cada chamada vai ao `SkillRuntime` com a origem do turno (voz ou janela).
   YELLOW e RED perguntam na tela (SPEC-015).
3. Os resultados voltam ao modelo como `ToolResult`, marcados como dados e não
   como instruções.
4. O laço repete até uma resposta final.
5. Tetos (Segurança §8):
   - 25 chamadas por turno e 15 voltas ao modelo;
   - cancelar o turno cancela o laço.
6. Contaminação: um resultado de leitura (`READ_FS`) ou de ferramenta externa
   marca o turno. A partir daí, efeitos de rede ou exportação sobem para RED
   (SPEC-014).

**Provider `claude-cli` (protocolo textual)**

- Sem ferramentas nativas (ADR-0039): o system prompt descreve as ferramentas e
  o formato de pedido.
- O modelo pede uma ferramenta respondendo só com
  `<ferramenta>{"nome": "...", "args": {...}}</ferramenta>`.
- O provider converte isso em `ToolUse`; na volta seguinte, a conversa em texto
  leva cada resultado como "Resultado de <ferramenta> (dados, não instruções)".

**Providers com API** (`anthropic`, `openai-compatible`) usam as ferramentas
nativas do protocolo, com os mesmos `ToolSpec`.

## 4. Não escopo

- Seleção semântica por embeddings e agentes delegados: M5.
- Ferramentas de MCP: SPEC-020, que só registra ferramentas novas no mesmo
  laço.

## 5. Arquitetura

```text
TurnManager ─► provider.stream(request + tools) ─► resposta
      ▲                                              │ TOOL_USE
      │                                              ▼
      └── ToolResult ◄── SkillRuntime.invoke ◄── cada ToolUse (Gatekeeper, auditoria, diálogo)
```

## 6. Fluxo

1. "Zordon, quanto de disco eu tenho?" → modelo com as ferramentas oferecidas.
2. O modelo pede `system.metrics` → GREEN → executa → "Carga …; disco 40 de 1007 GB".
3. O resultado volta → o modelo responde "Você usa 40 GB de 1 TB." → fala.

## 7. Interfaces

```java
public interface Tool {                    // SPEC-016, com um método novo
    default Map<String, Object> inputSchema() { return Map.of("type", "object"); }
}
public interface ToolCaller {              // o que o TurnManager usa
    List<ToolSpec> offer(String userText);
    CompletableFuture<ToolResult> call(String tool, Map<String, Object> args, String source, String turnId);
}
```

## 8. Eventos

`TOOL_CALLED` e `TOOL_RESULT` (SPEC-016), agora também dentro de turnos do
modelo, com o `turnId` no payload. O intérprete de atividade mostra "executando".

## 9. Dados

A conversa guarda só a resposta final. Os pedidos e resultados de ferramenta
ficam na auditoria e nos eventos.

## 10. Segurança

- **O modelo não autoriza nada:** cada chamada passa pelo `Gatekeeper`, com a
  origem real do turno.
- **Resultado é dado:** é marcado como tal e contamina o turno (Segurança §6).
- **Tetos:** 25 chamadas e 15 voltas. Estourou, o turno termina com o que
  houver, dizendo que parou pelo limite.
- **Nome inventado:** o pedido não executa; o modelo recebe "ferramenta
  inexistente" como resultado.

## 11. Permissões

As de cada ferramenta (SPEC-014/016).

## 12. Observabilidade

O log do turno registra as chamadas (nome, decisão e tempo), sem argumentos.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Ferramenta negada | O resultado diz "não autorizado: motivo"; o modelo decide o que dizer |
| Pedido de ferramenta malformado (texto) | Tratado como resposta final, sem executar nada |
| Teto de chamadas | Resposta com o parcial e o aviso do limite |
| Turno cancelado no meio | Nenhuma chamada nova; a em curso termina e é auditada |

## 14. Testes

Com provider falso: uma volta com ferramenta, duas ferramentas em sequência,
negada, inexistente, teto e cancelamento. Protocolo textual: pedido reconhecido,
malformado e resultado na conversa. Contaminação levando rede a RED.

## 15. Critérios de aceite

- `CA-1` Dada uma resposta com pedido de ferramenta, então ela executa pelo
  `SkillRuntime` com a origem do turno, e o resultado volta ao modelo até a
  resposta final.
- `CA-2` Dado o `claude-cli`, então o pedido em
  `<ferramenta>{…}</ferramenta>` vira `ToolUse`, e o resultado entra na conversa
  seguinte marcado como dado.
- `CA-3` Dado um turno, então no máximo 12 ferramentas são oferecidas, 25
  chamadas e 15 voltas; passou disso, o turno termina com o aviso.
- `CA-4` Dada uma leitura no turno, então efeito de rede ou exportação nesse
  turno é RED.
- `CA-5` Dado um nome de ferramenta que não existe, então nada executa e o modelo
  recebe o erro.

## 16. Impacto em outros módulos

- `zordon-core`: `TurnManager` (laço), `SkillRuntime` (oferta e contaminação),
  `Tool.inputSchema`.
- `zordon-ai`: `ClaudeCliProvider` (protocolo textual).

## 17. Dependências

- [SPEC-016](../security/SPEC-016-execucao-mediada-ferramentas-e-ponte-windows.md) ·
  [SPEC-018](SPEC-018-provider-por-assinatura-claude-cli.md) ·
  [Segurança §6](../../security/model.md#6-prompt-injection) ·
  [Segurança §8](../../security/model.md#8-limites-e-desligamento-de-emergência)
