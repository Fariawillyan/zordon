---
document: code-standards
module: process
section: standards
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [clean-code,solid,smells,complexidade,naming,comentarios,testabilidade]
specId: null
---

# Padrões de código

## 1. Objetivo

Definir o que o `CodeReviewAgent` verifica e o que um humano deve esperar ao
abrir qualquer arquivo do Zordon.

## 2. O princípio acima de todos

> Quando houver duas soluções equivalentes, prefira a **mais simples, legível,
> testável e segura**.

Nesta ordem. "Equivalentes" significa que ambas resolvem o problema real — não
que ambas resolvem o problema mais um problema imaginário do futuro.

Prioridades, em ordem de desempate:

```text
legibilidade > simplicidade > coesão > baixo acoplamento
             > responsabilidade única > testabilidade > manutenção
```

Performance entra quando houver medição mostrando que importa. O Zordon passa a
maior parte do tempo esperando um LLM responder; micro-otimização em código Java
quase nunca é onde está o problema.

## 3. SOLID sem cerimônia

Aplicar onde resolve um problema concreto. **Não criar abstração artificial.**

| Princípio | Aplicação real no Zordon |
|---|---|
| Responsabilidade única | `PermissionEngine` classifica; `AuditLog` registra; `NotificationCenter` comunica. Nenhum faz dois |
| Aberto/fechado | Skill, agente, detector e MCP entram por registro, sem alterar o núcleo |
| Substituição de Liskov | Todo `AiProvider` honra o mesmo contrato, inclusive cancelamento e erro |
| Segregação de interface | `FileAccess`, `ProcessRunner` e `WindowsBridge` são separadas — uma Skill pede só o que usa |
| Inversão de dependência | `zordon-core` depende de interfaces; a composição acontece no composition root |

**Sinais de SOLID mal aplicado**, que o `CodeReviewAgent` sinaliza:

- Interface com uma única implementação e nenhuma perspectiva de segunda.
- Factory que só constrói uma coisa.
- Camada de adaptação que só repassa chamadas.
- Hierarquia de herança com três níveis onde composição bastaria.

Padrão criado "por criar padrão" é complexidade sem contrapartida. O custo dele
é pago toda vez que alguém lê o código.

## 4. Code smells

O `CodeReviewAgent` procura estes. Ao detectar, ele **explica o problema e propõe
a refatoração** — não apenas aponta.

| Smell | Sinal | Severidade |
|---|---|---|
| God object / God service | Classe com muitas responsabilidades não relacionadas | **Bloqueante** |
| Classe gigante | Acima do limite de [§5](#5-complexidade) | Bloqueante |
| Método gigante | Acima do limite de §5 | Bloqueante |
| Código duplicado | Bloco repetido em 3+ lugares | Bloqueante |
| Acoplamento forte | Classe conhece detalhes internos de outra | Bloqueante |
| Dependência circular | Entre classes ou módulos | **Bloqueante** |
| Feature envy | Método usa mais dados de outra classe que da própria | Aviso |
| Lista longa de parâmetros | Acima de 4 | Aviso |
| Obsessão por primitivos | `String` onde há tipo de domínio (ex.: caminho sem `ZPath`) | **Bloqueante** |
| Condicionais em excesso | Cadeia longa de `if`/`switch` sobre tipo | Aviso |
| Aninhamento profundo | Acima de 3 níveis | Aviso |
| Código morto | Sem referência e sem teste | Aviso |
| Número mágico | Literal sem nome em regra de negócio | Aviso |
| Abstração desnecessária | §3 | Aviso |
| Shotgun surgery | Uma mudança exige tocar muitos arquivos | Aviso |

**Obsessão por primitivos é bloqueante** neste projeto por um motivo específico:
caminho como `String` em vez de `ZPath` é a origem de uma classe inteira de bugs
Windows ↔ WSL ([Windows↔WSL §R8](../architecture/windows-wsl.md)), e de falha de
segurança quando a validação de caminho é feita por comparação de prefixo.

Formato do achado:

```text
CODE SMELL DETECTED — primitive-obsession                        bloqueante

Arquivo   zordon-skills/.../FileSearchSkill.java:42
Problema  O parâmetro `path` é String. A canonicalização acontece dentro do
          método, depois da verificação de permissão.
Risco     `../../../Windows/System32` passa por verificação de prefixo ingênua.
Proposta  Receber ZPath, já canônico, resolvido na borda.
Referência docs/api/core-interfaces.md §1
```

## 5. Complexidade

Limites que disparam revisão. **Não são regras cegas** — ultrapassar exige
justificativa escrita, não é proibição automática.

| Métrica | Limite | Bloqueia? |
|---|---|---|
| Complexidade ciclomática por método | 10 | Acima de 15, sim |
| Linhas por método | 40 | Acima de 80, sim |
| Linhas por classe | 400 | Acima de 700, sim |
| Métodos públicos por classe | 12 | Acima de 20, sim |
| Parâmetros por método | 4 | Acima de 6, sim |
| Profundidade de aninhamento | 3 | Acima de 5, sim |
| Duplicação no módulo | 3% | Acima de 5%, sim |
| Dependências de saída por classe | 10 | Acima de 15, sim |

Exceções legítimas e documentadas: máquina de estados exaustiva, tabela de
mapeamento, parser. A justificativa vai em comentário `WHY` ([§6](#6-comentários)),
não em uma anotação de supressão silenciosa.

`zordon-security` e `zordon-defense` usam limites 30% mais apertados. Código de
segurança que ninguém consegue ler não é código de segurança.

## 6. Comentários

**Não comente código óbvio.**

```java
// ❌ ruído
// incrementa contador
counter++;

// ❌ o comentário repete o nome
/** Retorna o usuário. */
public User getUser() { ... }
```

O código se explica por **nomes claros, métodos pequenos e classes coesas**. Um
comentário que descreve *o que* o código faz é, quase sempre, um método mal
nomeado disfarçado.

Comentário é permitido — e desejável — quando explica **WHY**, nunca **WHAT**:

```java
// ✅ limitação externa não óbvia
// O WSL não herda WSL_INTEROP em serviços systemd, então powershell.exe falha
// aqui mesmo funcionando no terminal. Por isso o bridge vai por ZWP inverso.
// Ver docs/architecture/windows-wsl.md §R5.

// ✅ regra de segurança
// A canonicalização acontece ANTES da classificação de risco: caso contrário
// "../../.." passaria pela verificação de prefixo. Não inverta a ordem.

// ✅ workaround com prazo e dono
// Piper trava se receber sentença vazia (issue #412). Filtrar até subir a versão.
```

Casos válidos: workaround complexo, decisão arquitetural não óbvia, limitação de
biblioteca, regra de segurança, detalhe de protocolo, algoritmo não trivial,
comportamento externo inesperado.

**Quando a explicação for grande, ela não é comentário.** É documentação ou
[ADR](../adr/README.md), e o comentário vira uma linha apontando para lá. Um
bloco de 30 linhas de comentário é um documento no lugar errado — ninguém o
atualiza e ele apodrece junto do código.

Javadoc: obrigatório em interface pública de módulo, descrevendo contrato,
pré-condições e o que acontece em erro. Dispensável em implementação privada
óbvia.

## 7. Naming

Evitar nomes genéricos quando existe nome de domínio:

| Evite | Prefira |
|---|---|
| `ZordonService` | `AgentOrchestrator`, `PermissionEngine`, `ContextRouter` |
| `DataManager` | `MemoryStore`, `ToolRegistry` |
| `Helper`, `Utils` | O nome do que ele realmente faz |
| `Processor` | `IntentRouter`, `SignalCorrelator` |
| `handle()`, `process()`, `doWork()` | `classifyRisk()`, `selectTools()`, `quarantine()` |
| `data`, `info`, `thing`, `obj` | O substantivo do domínio |
| `flag`, `temp`, `result2` | O que o valor significa |

`Manager` e `Service` não são proibidos — são **sinal de alerta**. Se a classe
realmente gerencia ciclo de vida de um conjunto (`McpConnectionRegistry`,
`AgentRegistry`), o nome é honesto. Se ela virou o lugar onde todo código sem
casa foi parar, o nome está escondendo o problema.

Convenções do projeto:

- Código, identificadores e Javadoc em **inglês**.
- Documentação e texto exibido ao usuário em **português**.
- Evento em `SCREAMING_SNAKE_CASE`, como no [ZWP](../api/zwp-protocol.md).
- Ferramenta com prefixo obrigatório: `skill:` ou `mcp:`.
- Booleano afirmativo: `isAvailable`, não `isNotAvailable`.

## 8. Nada de God services

Um `ZordonService` com milhares de linhas é a forma mais rápida de destruir este
projeto, porque ele dissolve todas as fronteiras que a arquitetura definiu — e é
nessas fronteiras que a segurança mora.

A separação está em [Componentes §3](../architecture/components.md#3-responsabilidades-e-limites).
A coluna "**não** é responsável por" daquela tabela é o antídoto: quando alguém
for tentado a colocar lógica de permissão dentro de uma Skill "porque é mais
fácil", é aquela tabela que diz não — e o ArchUnit que reprova.

## 9. Testabilidade

**Regra de ouro:** regra importante não mora em controller.

```text
❌ JavaFX Controller com regra de negócio
❌ Handler MCP decidindo permissão
❌ Método ZWP calculando classificação de risco

✅ Controller coordena: recebe, delega, apresenta
✅ Regra vive em componente de domínio, sem dependência de UI, rede ou framework
```

Um componente de domínio deve ser testável com `new`, sem container, sem
servidor, sem JavaFX, sem rede. Se testar exige subir infraestrutura, a regra
está no lugar errado.

Consequência prática: `zordon-security` e `zordon-defense` — os módulos mais
críticos — devem ser os mais fáceis de testar, com a menor árvore de
dependências possível, idealmente só o JDK.

## 10. Segurança nos padrões

Os padrões nunca relaxam as [invariantes](../README.md#as-cinco-invariantes).
Em conflito, a segurança vence:

| Tentação | Regra |
|---|---|
| "Um `exec(String)` simplificaria muito" | Não existe. [ADR-0007](../adr/ADR-0007-permissao-sobre-acao-estruturada.md) |
| "Um `delete` seria mais direto que quarentena" | Não existe. [ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md) |
| "Notificar aqui é ruído" | Se a iniciativa é autônoma, notifica. [ADR-0014](../adr/ADR-0014-nenhuma-iniciativa-silenciosa.md) |
| "Perguntar ao LLM se é suspeito é mais simples" | A defesa é determinística. [ADR-0016](../adr/ADR-0016-defesa-deterministica.md) |
| "Loga o objeto inteiro para depurar" | Tudo passa por `SecretManager.redact`. [Segurança §5](../security/model.md#5-segredos) |

## 11. Testes

| Verificação | Ferramenta | Bloqueia |
|---|---|---|
| Complexidade e tamanho | PMD / Checkstyle | Acima do limite duro de §5 |
| Duplicação | CPD | Acima de 5% |
| Dependência circular | ArchUnit | Sempre |
| Regras arquiteturais | ArchUnit | Sempre |
| Formatação e cabeçalho de licença | Spotless | Sempre |
| Smells semânticos | `CodeReviewAgent` | Achado bloqueante de §4 |

## 12. Critérios de aceite

- `CA-1` Nenhum arquivo em `main` excede os limites duros de §5 sem justificativa
  `WHY` no código.
- `CA-2` Nenhum comentário em `main` descreve *o que* uma linha faz.
- `CA-3` Nenhuma regra de negócio em controller de UI, handler MCP ou método ZWP.
- `CA-4` Todo achado bloqueante do `CodeReviewAgent` vem com proposta de
  refatoração, não apenas com o apontamento.
