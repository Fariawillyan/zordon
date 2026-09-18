---
document: adr-0007
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,permissao,sobre,acao,estruturada]
specId: null
---

# ADR-0007 — Permissão sobre ação estruturada, nunca sobre shell

**Status:** Aceito · 2026-09-17

## Contexto

O Zordon executa ações na máquina do usuário a partir de saída de um LLM que
consome conteúdo não-confiável (arquivos, logs, páginas web, issues). Duas
perguntas precisam de resposta arquitetural:

1. Como o modelo expressa o que quer fazer?
2. Quem decide se aquilo acontece?

A resposta tentadora para (1) é "uma ferramenta de shell" — é flexível, cobre
todos os casos, e o modelo é bom em escrever comandos. É também a decisão que
torna todo o resto do sistema de segurança teatro.

## Alternativas

**A. Ferramenta de shell com validação de string.** O modelo produz um comando; o
sistema analisa a string e decide. Problema: é um problema de parsing
indecidível. `git reset --hard`, `git  reset --hard`, `git reset $(echo --hard)`,
`bash -c 'git reset --hard'`, um alias, um script que faz isso. Toda blocklist
por string tem um bypass, e a superfície cresce com a criatividade de quem ataca
— que aqui inclui o conteúdo de qualquer arquivo lido.

**B. Shell com confirmação humana em tudo.** O usuário lê e aprova cada comando.
Seguro no papel. Na prática, dezenas de confirmações por dia treinam o usuário a
aprovar sem ler em uma semana. A proteção existe no diagrama e não na realidade.

**C. Ferramentas tipadas, sem shell.** O modelo só pode chamar ferramentas com
schema declarado. Não existe ferramenta de shell. A decisão de permissão opera
sobre a **ação resolvida** — ferramenta, argumentos validados, caminhos
canônicos, efeitos derivados — e não sobre texto.

## Decisão

**Alternativa C**, com estas consequências obrigatórias:

1. **Não existe `ShellSkill`, `exec(String)` nem método ZWP `system.exec`.**
   A ausência é um requisito arquitetural, verificado por ArchUnit
   ([Componentes §4](../architecture/components.md#4-regras-de-dependência-verificadas-no-build)).
2. **Execução de processo usa `ProcessBuilder` com lista de argumentos.** Nunca
   `sh -c` nem `powershell -Command` com string montada. Sem shell, não há
   injeção de shell — a categoria inteira desaparece.
3. **Programas vêm de lista de permissão**, resolvidos a caminho absoluto na
   inicialização.
4. **Classificação sobre a ação resolvida.** Caminhos canônicos (symlinks
   resolvidos, relativos expandidos) antes de classificar, senão
   `../../../Windows` passa por verificação de prefixo ingênua.
5. **Risco é dinâmico nos argumentos.** `assess(input)` em vez de apenas
   `permission()` ([Interfaces §3](../api/core-interfaces.md#3-zordonskill)). A
   maioria das ações perigosas não é perigosa pela ferramenta, é perigosa pelo
   alvo. Sem isso, cai-se no problema da alternativa B.
6. **A descrição exibida ao usuário é gerada pelo núcleo**, a partir dos
   argumentos resolvidos — nunca pelo modelo. Um modelo comprometido não pode
   descrever "listar arquivos" enquanto pede exclusão.
7. **Lista `forbidden` vence tudo**, inclusive confirmação do usuário. Alterá-la
   exige editar configuração, um ato deliberado fora do fluxo de conversa. É o
   que impede uma injeção de convencer o usuário no calor do momento.
8. **Efeitos além de ferramentas.** Políticas se expressam sobre `Effect`
   (`QUARANTINE_FS`, `SPAWN_PROCESS`, `NETWORK`…), de modo que uma regra vale para
   ferramentas que ainda não existem — inclusive as de um MCP server futuro.

## Consequências

**Positivas.** Injeção de shell é impossível por construção, não por vigilância.
A classificação é determinística, testável e versionada em tabela golden,
revisável em diff. Ferramenta nova nasce classificada. O usuário vê a verdade do
núcleo, não a narrativa do modelo. A confirmação só aparece quando importa, o que
preserva o valor dela.

**Negativas.** Toda capacidade nova exige uma ferramenta, com schema e
classificação — não dá para "só rodar um comando". Casos raros ficam
descobertos até alguém escrever a Skill. Mais código do que uma ferramenta de
shell.

**Isso é o trade-off central do projeto e ele é aceito conscientemente.** A
alternativa A é mais rápida de construir e transforma o Zordon num sistema em que
qualquer `README.md` malicioso pode causar dano real. Um assistente com poder de
execução na máquina pessoal do usuário não pode ter essa propriedade.

**Válvula de escape controlada.** Quando o usuário genuinamente quer rodar um
comando exato, ele roda no terminal — é mais rápido e ele já sabe fazer. O Zordon
opera em intenções, não em comandos; isso é posicionamento de produto, não só
segurança ([Visão §4](../vision.md#explicitamente-não-objetivos)).
