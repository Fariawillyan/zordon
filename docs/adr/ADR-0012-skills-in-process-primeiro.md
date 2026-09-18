---
document: adr-0012
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,skills,in,process,primeiro]
specId: null
---

# ADR-0012 — Skills in-process primeiro, plugin isolado depois

**Status:** Aceito · 2026-09-17

## Contexto

O princípio 3 do projeto exige extensibilidade sem recompilar o núcleo. Para MCP
servers e agentes isso está resolvido: são configuração. Para Skills, há uma
escolha de isolamento a fazer.

Uma Skill executa código com o poder do processo do núcleo. Quanto isolamento ela
merece?

## Alternativas

**A. In-process via `ServiceLoader`.** Skills são classes no classpath,
descobertas na inicialização. Chamada direta, sem serialização, sem IPC. Mas:
adicionar uma Skill exige rebuild do módulo `zordon-skills` (não do núcleo, mas
ainda assim um build); uma Skill com bug derruba o processo; nenhum isolamento de
segurança.

**B. Plugin em JAR isolado por `ClassLoader`.** JARs em `~/.zordon/skills/`,
carregados em classloaders separados. Sem rebuild. Mas: isolamento de
`ClassLoader` **não é isolamento de segurança** — o código roda no mesmo processo,
com as mesmas permissões, e o `SecurityManager` do Java foi depreciado e removido.
Dá modularidade, não contenção. Adiciona inferno de classpath.

**C. Plugin como processo separado, falando MCP.** Isolamento real: processo
próprio, sem acesso à memória do núcleo, morre sem derrubar nada, pode ter limite
de recursos. Mas é exatamente o que MCP já é — e nesse caso a "Skill" deveria
simplesmente ser um MCP server.

**D. WASM.** Isolamento real com baixo overhead. Mas o ferramental Java para
WASM é imaturo, e Skills precisam justamente do que WASM não dá facilmente:
filesystem, processos, rede.

## Decisão

**Alternativa A no MVP (M3), com a interface projetada para B ou C depois.**

O raciocínio:

1. **As Skills iniciais são nossas.** Todas as ~30 Skills do M3 são escritas e
   revisadas por nós. Isolamento protege contra código de terceiro; não há
   código de terceiro ainda.
2. **A extensibilidade que importa já existe.** MCP cobre o caso "quero adicionar
   capacidade sem recompilar", e é por onde capacidade de terceiro deve entrar —
   com isolamento de processo de brinde.
3. **A segurança não vem do isolamento da Skill.** Ela vem do `PermissionEngine`,
   que é *externo* à Skill e não pode ser contornado por ela
   ([ADR-0007](ADR-0007-permissao-sobre-acao-estruturada.md)). Uma Skill
   maliciosa in-process poderia contornar — mas uma Skill maliciosa só existe se
   nós a escrevermos ou se o usuário instalar código não confiável, e nesse
   segundo caso o caminho correto é MCP.
4. **`ServiceLoader` com interface estável mantém o caminho aberto.**

### O que precisa estar certo desde já

Para que a migração futura não seja reescrita:

- `ZordonSkill` só troca tipos **serializáveis** (`SkillInput`, `SkillResult`,
  `JsonNode`). Nada de passar objetos vivos do núcleo.
- `SkillContext` expõe **interfaces mediadas** (`FileAccess`, `ProcessRunner`,
  `WindowsBridge`, `EventSink`), nunca handles diretos. Cada uma já é um ponto de
  corte natural para virar RPC.
- Skills não guardam estado global nem mexem em singletons do núcleo.
- Toda Skill respeita `ctx.deadline()` e interrupção de thread — pré-requisito
  para ser cancelável através de uma fronteira de processo.

Se essas quatro regras forem seguidas, mover uma Skill para fora do processo é
trocar a implementação do despacho.

## Consequências

**Positivas.** Simples e rápido no MVP. Chamada direta, sem serialização, no
caminho quente. Depuração trivial. Sem inferno de classloader.

**Negativas.** Skill nova exige rebuild do módulo. Skill com bug derruba o
processo (mitigado: toda invocação é envolvida por captura de exceção e
deadline). Skill maliciosa teria poder total — aceito, porque só nós escrevemos
Skills nesta fase.

**Gatilhos para migrar.** Skills de terceiros; usuário querendo escrever a
própria sem tocar no build; ou uma Skill instável derrubando o núcleo com
frequência. Nos dois primeiros casos, a primeira pergunta deve ser "isso não
deveria ser um MCP server?" — quase sempre a resposta é sim, e aí não há
migração a fazer.

**Superado por:** (nenhum, ainda)
