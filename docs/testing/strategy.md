---
document: testing-strategy
module: testing
section: strategy
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [testes,aceite,regressao,seguranca,cobertura]
specId: null
---

# Estratégia de testes

## 1. Objetivo

Definir o que é testado, como, e o que impede um merge.

## 2. Princípio

> Um teste existe para **falhar quando o comportamento muda de forma indesejada**.
> Teste que nunca falha não protege nada; teste que falha por qualquer motivo
> treina todo mundo a ignorá-lo.

Em um repositório mantido por agentes, isso é mais forte do que em um projeto
comum: o teste é a única coisa que impede um agente de declarar vitória cedo
demais ([DoD §2](../process/definition-of-done.md#3-o-problema-que-isto-resolve)).

## 3. Pirâmide

| Nível | O que cobre | Onde | Velocidade |
|---|---|---|---|
| **Unidade** | Regra de domínio isolada | `src/test/java` | ms |
| **Arquitetura** | Invariantes estruturais (ArchUnit) | `zordon-core:test` | ms |
| **Contrato** | Serialização ZWP, golden files | `zordon-zwp:test` | ms |
| **Golden** | Classificação de risco, detectores, recuperação RAG | módulo dono | ms |
| **Integração** | Módulos reais juntos, com fakes nas bordas | `src/integrationTest` | s |
| **Aceite** | Critérios de aceite de SPEC | `src/acceptanceTest` | s |
| **Segurança** | Tentativas de contornar controles | `zordon-security:test` | s |
| **Fim a fim** | Núcleo + host + desktop | manual e em release | min |

A base é larga de propósito. O topo é estreito porque teste fim a fim
atravessando WSL e Windows é lento e frágil — ele existe para o caminho crítico,
não para cobertura.

## 4. Testes de aceite

Todo critério de aceite de SPEC vira teste, ligado por anotação:

```java
@AcceptanceCriteria("SPEC-014/CA-3")
@Test
void ferramentaAcimaDoTetoDoAgenteNaoEhOferecidaAoModelo() {
    var agent   = agentWithCeiling(GREEN);
    var offered = toolRegistry.select(queryFor(agent));

    assertThat(offered)
        .noneMatch(t -> t.baseRisk().isAbove(GREEN));
}
```

O build reprova se uma SPEC `DONE` tem critério sem teste
([Rastreabilidade §6](../process/traceability.md#6-verificações-no-build)).

## 5. Testes de segurança

Categoria própria, porque testam o que **não** deve acontecer. Cada invariante
tem teste que tenta violá-la:

| Invariante | Teste |
|---|---|
| Sem shell arbitrário | Nenhuma API aceita string de comando; ArchUnit reprova `ProcessBuilder` fora de `zordon-security` |
| Sem exclusão | ArchUnit reprova `Files.delete` fora do cofre; `FileAccess` não expõe `delete` |
| Nada silencioso | `SecurityEvent` executado com `userMessageId` nulo não é construível |
| LLM não decide segurança | `zordon-defense` não importa `zordon-ai` |
| Caminho proibido | Tentativa de ler `~/.ssh/id_rsa` falha antes da decisão do modelo |
| Travessia de diretório | `../../../Windows/System32` é canonicalizado antes de classificar |
| Teto de agente | Ferramenta acima do teto não é oferecida |
| Redação de segredo | Nenhum caminho de log, evento ou prompt emite segredo conhecido |
| Origin de navegador | Handshake com `Origin` é rejeitado com `4403` |
| Auditoria append-only | `UPDATE`/`DELETE` na tabela falham por trigger |

Estes não são testes de funcionalidade. São **testes de ausência de capacidade**,
e são os que mais valem em um sistema com poder na máquina do usuário.

## 6. Golden tests

Para decisões que devem mudar apenas com revisão consciente:

| Tabela golden | Reprova quando |
|---|---|
| Classificação de risco | Uma ação muda de nível sem revisão explícita |
| Detectores | Sensibilidade muda sem revisão |
| Serialização ZWP | Formato muda por acidente |
| Recuperação RAG | Mudança de chunking piora o resultado esperado |

O valor: um PR que afrouxa um limiar — a mudança que um PR malicioso tentaria
esconder — aparece como diff que alguém precisa aprovar
([Cadeia de suprimentos §4](../security/supply-chain.md#4-verificações-em-detalhe)).

## 7. Fakes, não mocks

| Preferir | Evitar |
|---|---|
| `FakeWindowsBridge` que registra chamadas | Mock com expectativas encadeadas |
| `FakeAiProvider` que devolve respostas roteirizadas | Mock do SDK |
| SQLite em memória | Mock do `MemoryStore` |
| Servidor MCP de teste em processo | Mock do protocolo |

Mock verifica **como** o código foi chamado; fake verifica **o que aconteceu**.
O segundo sobrevive a refatoração, o primeiro quebra a cada mudança interna e
ensina a equipe a apagar teste em vez de consertar código.

`FakeAiProvider` é essencial: testar um agente contra um modelo real é lento,
caro e não determinístico. O fake devolve uma sequência roteirizada de respostas
e chamadas de ferramenta, e o teste verifica o que o orquestrador fez com elas.

## 8. Cobertura

| Módulo | Mínimo | Bloqueia |
|---|---|---|
| `zordon-security` | 90% | Sim |
| `zordon-defense` | 90% | Sim |
| `zordon-api` | 80% | Sim |
| Demais | 70% | Aviso |

Cobertura é **piso, não meta**. 95% de cobertura com asserções fracas é pior que
70% com testes que realmente falham — dá confiança sem dar proteção. O
`CodeReviewAgent` sinaliza teste sem asserção significativa e teste que passa com
a implementação removida.

## 9. Testes Windows ↔ WSL

O ambiente é a fonte de bug mais teimosa deste projeto
([Windows↔WSL](../architecture/windows-wsl.md)). Testes específicos:

| Cenário | Verifica |
|---|---|
| Conversão `ZPath` ida e volta | Nomes com espaço, acento, caminho longo |
| Usuário Windows ≠ usuário WSL | Nada deriva o perfil Windows de `$USER` ([§R21](../architecture/windows-wsl.md#r21--o-usuário-do-windows-não-é-o-usuário-do-wsl)) |
| Arquivo de endpoint | Escrita atômica; cliente reconecta ao mudar |
| Reconexão com `startId` novo | Cliente não assume continuidade |
| Salto de relógio | Scheduler recalcula em vez de disparar em rajada |
| Bridge indisponível | Falha rápida com `ERR_BRIDGE_UNAVAILABLE`, sem travar |

## 10. Quem escreve

| Teste | Autor |
|---|---|
| Unidade e integração | Agente de domínio, junto com a implementação |
| Aceite | `TestingAgent`, a partir dos `CA-n` da SPEC |
| Segurança | `SecurityAgent` |
| Arquitetura e golden | `ArchitectureAgent` |

**`TestingAgent` não corrige produção.** Teste falhando é reportado ao agente de
domínio ([Catálogo §5](../agents/catalog.md#testingagent)). Um agente de teste com
escrita em produção converge para "ajustar a asserção até passar" — a pior falha
possível quando ninguém está olhando.

## 11. Casos de erro

| Situação | Comportamento |
|---|---|
| Teste instável (flaky) | Marcado e corrigido ou removido em 7 dias; teste instável é ruído |
| Teste lento no caminho rápido | Move para `integrationTest` |
| Teste que depende de rede externa | Proibido; use fake |
| Teste que depende de ordem | Proibido; reprovado por execução aleatória |

## 12. Critérios de aceite

- `CA-1` Todo `CA-n` de SPEC `DONE` tem teste ligado por `@AcceptanceCriteria`.
- `CA-2` Toda invariante de §5 tem teste que tenta violá-la.
- `CA-3` `zordon-security` e `zordon-defense` acima de 90% de cobertura.
- `CA-4` A suite de unidade completa roda em menos de 60 s.
- `CA-5` Nenhum teste depende de rede externa ou de ordem de execução.
