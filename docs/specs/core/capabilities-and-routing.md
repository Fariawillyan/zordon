---
document: spec-core-capabilities-routing
module: core
section: routing
version: 1
updatedAt: 2026-09-18
securityLevel: internal
tags: [capacidades,roteamento,modelos,custo,privacidade,latencia]
specId: null
---

# Capacidades e roteamento de modelos

Como o Zordon decide **quem** executa cada etapa — qual modelo, qual agente,
qual ferramenta — pelo que sabem fazer, e não pelo nome. Decisão em
[ADR-0033](../../adr/ADR-0033-capacidades-e-roteador-de-modelos.md).

## 1. Vocabulário de capacidades

Fechado e versionado em `zordon-api` (`Capability`). Acrescentar uma capacidade
é mudança de contrato, com SPEC.

| Capacidade | Significa | Exemplo de quem declara |
|---|---|---|
| `TEXT` | Conversa e redação | Todo modelo de linguagem |
| `CODE` | Ler, escrever e corrigir código | Modelos de código, `DeveloperAgent` |
| `VISION` | Entender imagem (captura de tela, foto) | Modelos multimodais |
| `WEB` | Buscar e ler páginas | `ResearchAgent`, MCP de busca |
| `TERMINAL` | Executar comandos, no sandbox | Skills de build, `DeveloperAgent` |
| `RESEARCH` | Tarefa longa de pesquisa com fontes | `ResearchAgent` |
| `SPEECH` | Transcrever e sintetizar voz | Motor de voz ([SPEC-011](../voice/SPEC-011-motor-de-voz-ouvir-e-falar.md)) |
| `LONG_CONTEXT` | Janela acima de 100 mil tokens | Modelos grandes |
| `TOOL_USE` | Chamada de ferramenta estruturada | A maioria dos modelos atuais |
| `LOCAL_ONLY` | Processa sem sair da máquina | Ollama, motor de voz, skills locais |

Modelos declaram capacidades no `config.toml` junto com custo e latência
esperada; agentes, skills e MCPs no manifesto
([Extensões](../../architecture/extensions.md)). Quando dá, a declaração é
conferida por teste de contrato: `VISION` exige aceitar uma imagem de teste;
`TOOL_USE` exige devolver uma chamada válida.

## 2. O roteador

```text
 Etapa (do Planner ou do turno)
   exige: capacidades, dado sensível?, orçamento, janela
        │
        ▼
 1. Restrições duras (filtram)
    · tem todas as capacidades exigidas
    · dado sensível → só LOCAL_ONLY (contaminação, Segurança §6)
    · custo estimado ≤ orçamento restante da tarefa
    · janela ≥ contexto necessário
    · provider pronto (SPEC-004)
        │
        ▼
 2. Preferências (ordenam)
    · qualidade medida pela capacidade (Evaluation Engine)
    · custo
    · latência (voz exige resposta rápida)
        │
        ▼
 3. Decisão registrada: escolhido, descartados e por qual restrição
```

Os **papéis** de hoje (`conversation`, `routing`, `agent_heavy`…,
[SPEC-004](SPEC-004-providers-configuraveis.md)) continuam: um papel vira uma
consulta salva ao roteador ("`TEXT` + `TOOL_USE`, preferência qualidade"). Nada
quebra para quem configurou papéis.

## 3. Privacidade

A regra que o roteador nunca relaxa: **conteúdo sensível só vai para
`LOCAL_ONLY`**. Sensível é o que a contaminação marca
([Segurança §6](../../security/model.md)): conteúdo de arquivos pessoais, saída de
comandos com caminhos do usuário, qualquer coisa do trace. Se nenhum modelo local
tem as capacidades exigidas, a etapa falha com motivo; ela não "sobe para a
nuvem só desta vez".

## 4. Orçamento

Cada tarefa tem orçamento (tokens e US$) do Planner
([Planner](../agents/planner.md)); o roteador desconta a estimativa antes de
chamar e o custo real depois, pelo `TokenUsageService`
([Uso de tokens](../../operations/token-usage.md)). A 90% do orçamento, o roteador
só aceita modelos mais baratos; a 100%, a tarefa para e o narrador avisa.

## 5. Interfaces

```java
public enum Capability { TEXT, CODE, VISION, WEB, TERMINAL, RESEARCH, SPEECH, LONG_CONTEXT, TOOL_USE, LOCAL_ONLY }

public record RouteRequest(Set<Capability> requires, boolean sensitive, Budget budget, int contextTokens,
                           Preference preference) {}

public interface ModelRouter {
    RouteDecision route(RouteRequest request);   // escolhido + descartados com motivo
}

public interface CapabilityRegistry {
    Set<Capability> of(ProviderId provider, String model);
    List<Candidate> providing(Set<Capability> capabilities);
}
```

## 6. Observabilidade

A decisão vai para o evento do turno (`AI_THINKING.route`) e para o trace:
escolhido, descartados, restrição que descartou cada um, custo estimado. O modo
técnico mostra; a voz não narra ([ADR-0029](../../adr/ADR-0029-voice-first.md)).

## 7. Marco

M3, junto com a decisão do provider por assinatura: ela entra como mais um
provider com capacidades e custo declarados. O roteador substitui a escolha fixa
por papel sem mudar o `config.toml` existente.
