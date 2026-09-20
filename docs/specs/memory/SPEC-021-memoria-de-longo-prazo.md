---
document: spec-021
module: memory
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,memoria,sqlite,fts5,rrf,destilacao,m5]
specId: SPEC-021
---

# SPEC-021 — Memória de longo prazo

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `MemoryAgent` |
| **Revisores** | SecurityAgent, ArchitectureAgent, DatabaseAgent |
| **Marco** | M5 |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

O Zordon passa a lembrar entre conversas e entre reinícios: o que o usuário
declarou, em que projeto trabalhou e o que foi decidido. A lembrança é
inspecionável, tem procedência e pode ser esquecida pelo dono
([Memória](design.md)).

## 2. Problema

As conversas ficam só na RAM do núcleo. Um reinício apaga tudo, e "abra o
projeto que trabalhamos ontem" (UC5) não tem de onde tirar a resposta.

## 3. Escopo

**Armazenamento:** um módulo novo, `zordon-memory`, com a interface
`MemoryStore` e a implementação `SqliteMemoryStore`.

- Banco em `~/.zordon/zordon.db`, com WAL, no ext4.
- Migrações numeradas em `migrations/V###__*.sql`, só para frente, em transação.
  A tabela `schema_version` guarda a versão.
- Antes de migrar um banco existente, ele é copiado para
  `zordon.db.bak.<versão>`. Cópia antiga nunca é apagada nem sobrescrita.
- A V001 cria:
  - `session` e `message` (a conversa, como registro);
  - `fact`, com o índice `fact_fts` em FTS5 (`unicode61`, sem acentos) mantido
    por gatilhos;
  - `entity` e `relation`, que ficam vazias até o M8;
  - `distill_queue`.

**Conversa:** o `ConversationStore` grava cada sessão e cada mensagem no banco.
A leitura do turno continua na RAM. Carregar sessões antigas na tela fica para
depois.

**Fatos:**
- Tipos: `PREFERENCE`, `PROJECT`, `ENTITY`, `EVENT`, `PROCEDURE`.
- Todo fato tem `subject`, `content`, `confidence` (0 a 1), `observed_at`,
  `expires_at` opcional e `provenance` (o `turnId`). Sem procedência, não grava.
- Fato igual a um ativo do mesmo assunto (texto normalizado) não duplica: o
  existente é devolvido.
- Correção: um fato com `corrects = true` marca os ativos do mesmo tipo e assunto
  com `superseded_by` e grava o novo. O antigo continua consultável pela história.

**Busca híbrida:**
- FTS5 (BM25, até 30) e, quando houver um `Embedder`, a lista vetorial (até
  30). As duas são fundidas por RRF: `Σ 1/(60 + posição)`.
- Nesta SPEC não há `Embedder`: a lista vetorial fica vazia e o RRF funciona só
  com a lexical. O ponto de extensão existe e é testado com um embedder falso.
- Reordenação pela fórmula de [Memória §4](design.md#4-recuperação-híbrida):
  confiança, recência (meia-vida de 90 dias para `EVENT`, nenhuma para
  `PREFERENCE` e `PROCEDURE`, 365 dias para os demais) e acessos.
- Filtro por tipo e por janela de tempo. "hoje", "ontem" e "esta semana" na
  consulta viram janela.
- Só entram fatos ativos: sem `superseded_by` e não expirados.

**Escritas:**
1. **Pelo usuário:** "lembre que…", "anote que…", "guarde que…" viram a rota
   rápida `memory.remember`, com confiança 0,95. O tipo vem por palavras:
   "prefiro/gosto/não gosto" → `PREFERENCE`; "projeto" → `PROJECT`; "para …,
   rode/use" → `PROCEDURE`; senão `ENTITY`. A resposta é "Anotado: …".
2. **Pelo trabalho:** uma ferramenta que termina OK tocando um caminho dentro de
   um workspace (`~/dev/<projeto>/…`) gera o fato `EVENT` "trabalhou no projeto
   `<projeto>`", no máximo um por projeto por dia, com confiança 0,9.
3. **Por destilação:**
   - Depois da resposta entregue, o turno entra na `distill_queue`.
   - Um trabalhador assíncrono pede ao modelo do papel `summarize` (ou, sem ele,
     `conversation`) uma lista JSON de fatos.
   - A entrada é o texto do usuário e o da resposta. Resultado de ferramenta
     nunca entra. Turno contaminado (SPEC-019) destila só o texto do usuário.
   - Validação de cada fato: tipo conhecido, até 300 caracteres e confiança
     limitada a 0,8 (0,9 se for correção). Fato com segredo detectado pelo
     `Redactor` é descartado.
   - Falha do modelo: três tentativas, com espera de 1, 5 e 30 min.
   - `[memory] distill = false` no `config.toml` desliga.
   - Pulados: turnos de rota rápida e pedidos com menos de 12 caracteres.

**Leituras:**
- Antes de cada volta ao modelo, até 6 fatos relevantes ao pedido (máximo de
  1.500 caracteres) entram **no início da última mensagem do usuário**, como
  bloco de dados marcado. O prompt de sistema fica estável por causa do cache.
  Fatos usados assim têm `access_count` incrementado.
- A ferramenta `memory.search` (GREEN, sem efeito) fica disponível ao modelo e
  aceita `query` e `when` (`hoje`, `ontem`, `semana`).
- `memory.remember` **não** é oferecida ao modelo. O modelo não escreve memória
  por conta própria: seria o caminho de uma injeção se tornar permanente.

**Controles (ZWP):**

| Método | Params | Retorno |
|---|---|---|
| `memory.facts` | `{kind?, subject?, limit?}` | `{facts[]}` ativos, do mais novo para o mais antigo |
| `memory.search` | `{query, limit?}` | `{hits[{fact, score}]}` |
| `memory.forget` | `{factId}` | `{forgotten}`; só de um desktop |
| `memory.forgetSubject` | `{subject}` | `{forgotten: n}`; só de um desktop |
| `memory.export` | `{}` | `{facts[], sessions}`: JSON completo dos fatos |

**Tela:** a seção "Memória" nos ajustes lista os fatos (tipo, assunto,
conteúdo e data) com o botão "Esquecer".

**Evento:** `MEMORY_WRITTEN {kind, id, summary}` no tópico `memory`.

## 4. Não escopo

- Embeddings locais e `sqlite-vec`: o `Embedder` fica como ponto de extensão;
  o modelo local e a extensão nativa entram com o RAG (M8).
- Sessão efêmera ("não lembre desta conversa"): próxima SPEC da memória.
- Retenção configurável da conversa: nada é apagado sozinho.
- Carregar conversas antigas na tela.
- Relações e grafo: M8 (as tabelas já existem).

## 5. Arquitetura

```text
TurnManager ──► ConversationStore ──(grava)──► MemoryStore (zordon.db)
     │                                            ▲   ▲
     │ antes da volta: MemoryContext.recall ──────┘   │
     │ depois da resposta: Distiller (fila, assíncrono) ┘
SkillRuntime ──(ferramenta OK num workspace)──► WorkObserver ──► fato EVENT
IntentRouter ──("lembre que …")──► memory.remember
```

`zordon-memory` depende só de `zordon-api`, sqlite-jdbc, Jackson e SLF4J. O
núcleo depende dele. Nenhum SQL de memória fora do módulo.

## 6. Fluxo

UC5, "Zordon, abra o projeto que trabalhamos ontem":

1. A rota rápida de abrir aplicativo **não** pega o pedido, porque ele fala de
   memória ("ontem", "que trabalhamos"), e o pedido vai ao modelo.
2. O contexto de memória traz os `EVENT` de ontem ("trabalhou no projeto
   zordon").
3. O modelo responde com o projeto ou, se houver mais de um, pergunta qual.

## 7. Interfaces

```java
public interface MemoryStore extends AutoCloseable {       // zordon-memory
    void session(String sessionId, String title, Instant startedAt);
    void message(String sessionId, String turnId, String role, String content, Instant ts);
    List<StoredLine> history(String sessionId, int limit);
    Fact remember(NewFact fact);
    List<MemoryHit> search(RecallQuery query);
    List<Fact> facts(FactKind kind, String subject, int limit);
    void touched(Collection<String> factIds);
    boolean forget(String factId);
    int forgetSubject(String subject);
}
```

## 8. Eventos

`MEMORY_WRITTEN` a cada fato gravado, com `summary` de até 80 caracteres.

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Conversas | `zordon.db`: `session`, `message` | Permanente (sem limpeza automática) |
| Fatos | `zordon.db`: `fact`, `fact_fts` | Até expirar, ser substituído (fica na história) ou esquecido |
| Fila de destilação | `zordon.db`: `distill_queue` | Até destilar ou esgotar as tentativas |
| Cópias antes de migrar | `zordon.db.bak.<versão>` | Permanente; o usuário decide |

## 10. Segurança

- **Esquecer é do dono:** `memory.forget` apaga a linha e o índice de verdade
  ([Memória §8](design.md#8-controles-do-usuário)). É linha do banco do próprio
  Zordon, não arquivo do usuário (ADR-0015 continua valendo). Só a tela pede;
  voz, modelo e automação não esquecem.
- **Injeção não vira memória:** o modelo não tem ferramenta de escrita;
  resultados de ferramenta não são destilados; turno contaminado destila só o
  texto do usuário.
- **Memória é dado:** o bloco no prompt vai marcado como dado, com a mesma regra
  de resultado de ferramenta.
- **Segredos:** fato com segredo detectado é descartado antes de gravar.

## 11. Permissões

- `memory.search`: GREEN, sem efeito externo.
- `memory.remember`: GREEN, só pela rota do usuário.
- `memory.forget` e `memory.forgetSubject`: só do desktop, fora do motor (não são
  ferramentas).

## 12. Observabilidade

- Log INFO de migração (de/para, cópia), de fatos gravados (tipo e id, sem o
  conteúdo) e de destilação (turno, fatos aceitos e rejeitados, motivo).
- `system.diagnostics` ganha `memory`: versão do schema, número de fatos e fila.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Migração falha | Rollback; o núcleo não sobe, com erro claro |
| Banco mais novo que o código | O núcleo não sobe: "o banco é da versão N, este Zordon conhece até M" |
| Modelo indisponível na destilação | Fica na fila; três tentativas |
| JSON inválido do modelo | Tentativa perdida, com log; nada gravado |
| Consulta vazia ou só com palavras comuns | Lista vazia |

## 14. Testes

- Migração em banco novo, em banco da V001 (idempotente), rollback de migração
  quebrada e recusa de banco mais novo.
- Fatos: procedência obrigatória, deduplicação, correção com `superseded_by`,
  expiração, esquecer e esquecer por assunto (inclusive do FTS).
- Busca: BM25 sem acentos, RRF com embedder falso, janela "ontem", recência de
  `EVENT` e preferência sem decaimento.
- Destilação: provider roteirizado; turno contaminado sem a resposta; segredo
  descartado; falha reenfileira.
- Núcleo: "lembre que…" grava e responde; fato no prompt da volta seguinte;
  `memory.forget` recusado fora do desktop; EVENT pelo trabalho num workspace.
- Tela: a lista e o botão Esquecer.

### Evidências (2026-09-19)

- `zordon-memory` `MemoryStoreTest` (13 testes):
  - CA-1: banco novo na versão atual; reaberto sem cópia; migração quebrada
    desfaz tudo, depois da cópia; a segunda cópia não sobrescreve a primeira;
    banco mais novo que o código é recusado com a mensagem;
  - CA-3: correção marca `superseded_by`, o antigo continua na tabela e sai da
    busca;
  - CA-4: busca sem acento e com flexão ("trabalhamos" acha "trabalhou"); janela
    "ontem"; RRF com embedder falso (o fato das duas listas sobe); recência
    derruba o evento velho; preferência não envelhece; acessos contam;
  - CA-6: esquecer apaga da tabela e do FTS; esquecer por assunto.
- Núcleo, `MemoryFlowTest` (8 testes):
  - CA-2: "lembre que eu prefiro respostas curtas" grava `PREFERENCE` com o
    `turnId`; a volta seguinte recebe o bloco no começo da última mensagem; o
    prompt de sistema e a conversa gravada ficam limpos;
  - CA-5: a destilação corta confiança, descarta o segredo e o tipo inválido;
    turno contaminado destila só o pedido; falha do modelo reenfileira com
    espera;
  - CA-7: trabalho em `~/dev/aurora` vira um evento por dia, e "abra o projeto
    que trabalhamos ontem" vai ao modelo com o fato de ontem;
  - o modelo não vê nem chama `memory.remember`.
- `MemoryMethodsTest`: núcleo inteiro, anota, reinicia, a lembrança está lá; o
  host é recusado em `memory.forget`; o desktop esquece (CA-6).

## 15. Critérios de aceite

- `CA-1` Dado o núcleo reiniciado, então conversas e fatos continuam no
  `zordon.db`, migrado por versão, com cópia antes de migrar e nada apagado.
- `CA-2` Dado "lembre que eu prefiro respostas curtas", então um fato
  `PREFERENCE` com a procedência do turno é gravado, e a volta seguinte ao
  modelo o recebe como dado.
- `CA-3` Dada uma correção, então o fato antigo fica `superseded_by` e sai da
  busca, sem ser apagado.
- `CA-4` Dada uma consulta, então a busca funde as listas por RRF e reordena por
  confiança, recência e acessos; "ontem" filtra pela janela.
- `CA-5` Dado um turno concluído, então a destilação roda depois da resposta, sem
  resultado de ferramenta, descartando segredos, e reenfileira se o modelo
  falhar.
- `CA-6` Dado `memory.forget` pela tela, então o fato some da tabela e do índice;
  pedido de outro cliente é recusado.
- `CA-7` Dado trabalho num projeto do workspace, então um `EVENT` "trabalhou no
  projeto X" é gravado uma vez por dia, e "abra o projeto que trabalhamos ontem"
  vai ao modelo com esse fato no contexto.

## 16. Impacto em outros módulos

- Novo `zordon-memory`, incluído no `settings.gradle.kts`.
- `zordon-core`: `ConversationStore` grava; `TurnManager` recebe o contexto de
  memória e avisa o fim do turno; `IntentRouter` com a rota "lembre que" e a
  exceção de memória na rota de abrir; `SkillRuntime` avisa ferramentas
  concluídas; `Tool.modelVisible()`.
- `zordon-desktop`: seção Memória.
- `docs/api/zwp-protocol.md`: métodos `memory.*`.

## 17. Dependências

- [Memória — design](design.md) ·
  [ADR-0008](../../adr/ADR-0008-sqlite-como-memoria.md) ·
  [ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md) ·
  [SPEC-019](../core/SPEC-019-ferramentas-pelo-modelo.md)
