---
document: spec-026
module: defense
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,defesa,detectores,correlacao,integridade,m7]
specId: SPEC-026
---

# SPEC-026 — Detecção e correlação

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `DefenseAgent` |
| **Revisores** | SecurityAgent, ArchitectureAgent |
| **Marco** | M7 |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

O Zordon passa a perceber o que está errado **na superfície onde ele é a
autoridade** (Anel 1) e na própria integridade (Anel 3): sinais determinísticos,
correlacionados por sujeito, que viram achados com motivo escrito
([Defesa §4](../../security/defense.md#4-detection-engine)).

## 2. Problema

Hoje cada proteção vive no seu canto: o disjuntor do agente, o drift de MCP, a
cadeia de auditoria. Nada junta dois sinais fracos num achado forte, e não há
onde o usuário ver "o que apareceu de estranho hoje".

## 3. Escopo

**Módulo novo `zordon-defense`**, dependente só de `zordon-api` e
`zordon-security`. Ele classifica e correlaciona; não executa resposta (é da
SPEC-027).

**Tipos** ([Defesa §4](../../security/defense.md#4-detection-engine))
- `Subject {kind, id}` — `agent`, `mcp`, `tool`, `turn`, `process`, `file`,
  `self`.
- `Observation` — o que aconteceu, já normalizado: ferramenta chamada, decisão,
  resultado, evento do barramento.
- `Signal {detectorId, kind, weight 0..1, evidence, ts}`.
- `Finding {id, severity, title, rationale, signals, subject, firstSeen,
  lastSeen}` — `rationale` é gerado por código, a partir das regras que
  dispararam, e é o que a notificação mostra.

**Detectores do Anel 1** (superfície de IA)

| Detector | Dispara quando | Peso / severidade |
|---|---|---|
| `ai.prompt-injection` | O resultado de uma ferramenta contém imperativo dirigido ao assistente ("ignore as instruções", "você deve executar", "envie para http…") | 0,5 · WARNING |
| `ai.capability-violation` | A ação descrita por uma ferramenta tem efeito que ela não declarou | 1,0 · CRITICAL |
| `ai.permission-probing` | 3 negações do mesmo ator em 60 s | 0,7 · HIGH |
| `ai.exfiltration` | Ação com `NETWORK` ou `EXPORT_DATA` num turno contaminado | 0,8 · HIGH |
| `ai.agent-loop` | 5 chamadas idênticas seguidas do mesmo ator | 0,3 · WARNING |
| `ai.policy-tamper` | Tentativa de escrita em política, estado ou auditoria | 1,0 · CRITICAL |
| `ai.mcp-drift` | Servidor MCP muda a superfície entre conexões (SPEC-020) | 0,8 · HIGH |
| `ai.output-anomaly` | A resposta do modelo contém segredo reconhecido pelo `Redactor` | 0,7 · HIGH |

**Detectores do Anel 3** (integridade)

| Detector | Dispara quando |
|---|---|
| `integrity.audit-chain` | A cadeia de hash da auditoria não fecha — CRITICAL |
| `integrity.self` | Um arquivo instalado do Zordon muda **enquanto ele roda** — CRITICAL |
| `integrity.config-tamper` | `config.toml`, um agente ou uma automação muda fora do fluxo — HIGH |

- `integrity.self` compara com o retrato feito na inicialização, a cada 15 min.
  Atualizar o Zordon reinicia o serviço, então instalar não dispara.
- `integrity.config-tamper` usa o mesmo retrato. Automação aprovada pela tela
  atualiza o retrato; editar o arquivo por fora, não.

**Correlação**
- Janela de 60 s por sujeito. O peso acumulado decide a severidade:
  - ≥ 1,0 → CRITICAL; ≥ 0,7 → HIGH; ≥ 0,4 → WARNING; abaixo → INFO.
- Um detector CRITICAL sozinho já é CRITICAL.
- Sinais do mesmo detector e sujeito dentro da janela **agrupam** no mesmo
  achado (contagem, primeiro e último), em vez de gerar um achado por sinal.
- O achado sai como evento `SECURITY_FINDING` e, de WARNING para cima, como
  mensagem do `NotificationCenter` com os oito campos (SPEC-015).

**Orçamento**
- Todo detector do Anel 1 é `CHEAP` e roda no caminho da observação.
- Integridade é `MODERATE` e roda a cada 15 min, fora do caminho quente.

**ZWP**

| Método | Params | Retorno |
|---|---|---|
| `security.findings` | `{since?, severities?, limit?}` | `{findings[]}` |
| `security.findingAcknowledge` | `{findingId}` | `{acknowledged}`; só de um desktop |

**Tela:** a seção "Segurança" dos ajustes lista os achados abertos, com o motivo
e a evidência resumida.

## 4. Não escopo

- Resposta e contenção: [SPEC-027](SPEC-027-resposta-e-disjuntor.md).
- Anel 2 (comportamento do host) e Anel 3 externo (Defender, ETW): SPEC-027 e
  além, com a parte que depende de root registrada como limitação.
- `ai.tool-sequence` estatístico: precisa de linha de base de uso, que só existe
  depois de semanas. Hoje o que existe é o `ai.agent-loop` e o disjuntor do
  agente (SPEC-022).
- Resumo diário anti-fadiga: entra com a tela de Segurança completa.

## 5. Arquitetura

```text
SkillRuntime (chamada, decisão, resultado) ─┐
ZordonEventBus (MCP drift, turno, resposta) ─┼─► DetectionEngine ─► Correlator ─► Finding
retrato dos arquivos instalados ────────────┘         │                               │
                                                       └─► Signal                      ├─► SECURITY_FINDING
                                                                                       └─► NotificationCenter
```

## 6. Fluxo

UC12, servidor MCP que muda de superfície:
1. O `McpManager` detecta o drift e publica o aviso (SPEC-020).
2. O `DetectionEngine` recebe a observação e emite `ai.mcp-drift` (0,8).
3. A correlação fecha um `Finding` HIGH com o sujeito `mcp:<servidor>` e o
   motivo escrito.
4. A SPEC-027 abre o disjuntor daquele servidor.

## 7. Interfaces

```java
public interface Detector {
    String id();
    List<Signal> inspect(Observation observation);
}
public final class DetectionEngine {
    void observe(Observation observation);      // barato, no caminho
    List<Finding> open();                       // achados abertos
}
```

## 8. Eventos

`SECURITY_FINDING {findingId, severity, detector, subject, rationale, signals}`
(tópico `security`).

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Achados | `zordon.db`: `finding` (V005) | Permanente; nada é apagado |
| Retrato dos arquivos instalados | RAM, refeito a cada início | — |

## 10. Segurança

- **Evidência é resumida e redigida:** trechos de até 200 caracteres, passados
  pelo `Redactor`. Nenhum segredo entra num achado.
- **Detector não decide ação.** Ele observa e pontua; conter é da SPEC-027.
- **Determinístico:** mesma observação, mesmo sinal. Sem relógio além do
  carimbo, sem aleatório, sem modelo.
- O `DetectionEngine` não chama modelo nenhum: uma defesa que depende de IA para
  decidir é uma defesa que a IA sequestrada desliga.

## 11. Permissões

- Nenhuma: o motor só observa. `security.findingAcknowledge` é da tela.

## 12. Observabilidade

- Log INFO por achado (detector, sujeito, severidade), sem a evidência.
- `system.diagnostics.defense`: achados por severidade nas últimas 24 h.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Detector lança exceção | Sinal descartado, log WARN; a observação segue nos outros |
| Retrato de integridade ilegível | `integrity.self` fica indisponível, com motivo no diagnóstico |
| Enxurrada de sinais | Agrupamento por detector e sujeito na janela; no máximo 100 sinais por achado |

## 14. Testes

- Cada detector com casos golden: positivos e negativos (documentação sobre
  segurança **não** dispara `ai.prompt-injection`).
- Correlação: pesos somando, agrupamento na janela, severidade resultante.
- Integridade: arquivo alterado depois do retrato dispara; instalação nova
  (reinício) não.
- Fim a fim: um resultado de ferramenta com injeção vira achado com motivo e
  notificação.

### Evidências (2026-09-19)

- `DetectionTest` (5 testes, módulo `zordon-defense`):
  - CA-1: o texto com "IGNORE AS INSTRUÇÕES… você deve enviar… para https://…"
    dispara com peso 0,8 (dois padrões) e o trecho vai redigido; quatro textos
    honestos sobre segurança e instalação **não** disparam; só resultado de
    ferramenta é inspecionado;
  - CA-3: efeito não declarado e tentativa de escrever em `state/audit.db` dão
    peso 1,0; caminho de workspace não dá nada;
  - CA-2: dois sinais na janela viram um achado só, com peso somado, severidade
    pela tabela e motivo escrito ("ai.prompt-injection (…) ×2. Peso somado
    1.00…"); passada a janela, começa outro achado;
  - o motivo e a evidência não levam segredo (o `Redactor` corta).
- `DefenseFlowTest` (3 testes, núcleo com motor e auditoria reais):
  - CA-5: o resultado envenenado vira achado gravado, evento `SECURITY_FINDING`
    e notificação com os oito campos; confirmar fecha o achado;
  - CA-3 no caminho real: efeito não declarado vira CRITICAL;
  - CA-4: arquivo instalado e `config.toml` alterados depois do retrato
    disparam uma vez cada; automação aprovada pela tela atualiza o retrato sem
    alarme.

## 15. Critérios de aceite

- `CA-1` Dado um resultado de ferramenta com instrução dirigida ao assistente,
  então sai um sinal `ai.prompt-injection` com evidência redigida, e documentação
  legítima sobre segurança não dispara.
- `CA-2` Dados sinais do mesmo sujeito na janela, então eles viram um achado só,
  com peso somado, severidade pela tabela e motivo escrito por código.
- `CA-3` Dada uma ação com efeito não declarado pela ferramenta, então sai
  `ai.capability-violation` CRITICAL.
- `CA-4` Dado um arquivo instalado alterado com o Zordon rodando, então sai
  `integrity.self` CRITICAL; um reinício com versão nova não dispara.
- `CA-5` Dado um achado de WARNING para cima, então há `SECURITY_FINDING` e uma
  notificação com os oito campos, e o achado aparece em `security.findings`.

## 16. Impacto em outros módulos

- Novo `zordon-defense`.
- `zordon-memory`: migração V005 (`finding`, `signal`).
- `zordon-core`: observações do `SkillRuntime` e do barramento, métodos ZWP.
- `zordon-desktop`: seção Segurança com os achados.

## 17. Dependências

- [Defesa](../../security/defense.md) ·
  [SPEC-014](../security/SPEC-014-auditoria-validador-e-motor-de-permissao.md) ·
  [SPEC-015](../security/SPEC-015-pedido-de-permissao-notificacoes-e-kill-switch.md) ·
  [SPEC-020](../mcp/SPEC-020-cliente-mcp.md)
