# Contribuindo com o Zordon

Obrigado pelo interesse. Antes de abrir um PR, leia esta página inteira — o
Zordon tem regras de contribuição mais rígidas que a média, e elas existem por
um motivo específico.

## Por que as regras são rígidas

O Zordon roda permanentemente na máquina pessoal de alguém, com acesso a
arquivos, processos, credenciais e rede, e se apresenta como camada de defesa
dessa máquina. Um commit malicioso ou descuidado aqui não quebra um site: ele
compromete o computador de quem confiou no projeto.

Por isso o projeto assume, por política, que **qualquer contribuição externa
pode estar comprometida** — não como desconfiança da pessoa, mas como postura de
engenharia. Ver [Cadeia de suprimentos](docs/security/supply-chain.md).

## Antes de escrever código

1. **Leia o processo.** [Spec-Driven Development](docs/process/spec-driven-development.md).
   Funcionalidade relevante nasce como SPEC, não como código.
2. **Leia a arquitetura.** [Arquitetura](docs/architecture/overview.md) e
   [Interfaces](docs/api/core-interfaces.md). O Zordon tem opiniões fortes sobre
   onde cada coisa mora.
3. **Leia as regras de dependência.** [Componentes §4](docs/architecture/components.md).
   Elas são verificadas por ArchUnit e reprovam o build.
4. **Leia a segurança.** [Segurança](docs/security/model.md) e
   [Defesa](docs/security/defense.md).
5. **Leia os padrões de código.** [Padrões](docs/process/code-standards.md).
   O `CodeReviewAgent` verifica exatamente o que está ali.
6. **Abra uma issue antes** para qualquer mudança que toque contrato, segurança
   ou arquitetura. PR grande sem discussão prévia costuma ser recusado por
   direção, não por qualidade.

## Regras que reprovam o PR automaticamente

Estas não são preferências de estilo. São invariantes do projeto.

| Regra | Onde está escrita |
|---|---|
| Funcionalidade relevante sem SPEC `APPROVED` | [ADR-0019](docs/adr/ADR-0019-spec-driven-development.md) |
| Alteração no núcleo de confiança sem revisão de CODEOWNERS | [ADR-0024](docs/adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md) |
| Qualquer escrita nos diretórios de instalação do Zordon | [Auto-modificação §5](docs/process/self-modification.md) |
| Nenhuma execução de shell com string montada (`sh -c`, `powershell -Command`) | [ADR-0007](docs/adr/ADR-0007-permissao-sobre-acao-estruturada.md) |
| Nenhuma API que apague arquivo ou diretório | [ADR-0015](docs/adr/ADR-0015-exclusao-impossivel-por-construcao.md) |
| Nenhum `ProcessBuilder`/`Runtime.exec` fora de `zordon-security` | [Componentes §4](docs/architecture/components.md) |
| Nenhum caminho como `String` em API de Skill — use `ZPath` | [Interfaces §1](docs/api/core-interfaces.md) |
| Nenhuma decisão de segurança delegada ao LLM | [ADR-0016](docs/adr/ADR-0016-defesa-deterministica.md) |
| Nenhuma ação autônoma sem notificação ao usuário | [ADR-0014](docs/adr/ADR-0014-nenhuma-iniciativa-silenciosa.md) |
| Nenhum segredo em log, evento, prompt ou tela | [Segurança §5](docs/security/model.md) |
| Nenhuma dependência nova sem justificativa e revisão | [Cadeia de suprimentos §7](docs/security/supply-chain.md) |
| Nenhum módulo de capacidade importando outro módulo de capacidade | [Componentes §4](docs/architecture/components.md) |
| Regra de negócio em controller de UI, handler MCP ou método ZWP | [Padrões §9](docs/process/code-standards.md) |
| Comentário que descreve *o que* o código faz em vez de *por quê* | [Padrões §6](docs/process/code-standards.md) |
| Critério de aceite de SPEC `DONE` sem teste ligado | [Rastreabilidade](docs/process/traceability.md) |

## Pipeline obrigatória

Nenhum PR externo faz merge automático. Todo PR passa por:

```
PR → CI (build + testes) → SAST → Dependency Scan → Secret Scan
   → SBOM diff → License check → ArchUnit → Code Review humano
   → CODEOWNERS → Merge
```

Detalhes em [Cadeia de suprimentos §3](docs/security/supply-chain.md).

## O que todo PR precisa ter

- **Descrição do problema**, não só da solução.
- **Testes.** Mudança em `zordon-security` ou `zordon-defense` exige cobertura
  ≥ 90% no código tocado e caso golden quando altera classificação de risco.
- **Nenhum arquivo binário** sem justificativa explícita (binário é o vetor mais
  simples de comprometimento).
- **Commits assinados** (`git commit -S`) para qualquer coisa em
  `zordon-security/`, `zordon-defense/` ou `zordon-api/`.
- **Nenhuma alteração de dependência** misturada com mudança funcional. Separe.
- **Documentação atualizada** se o comportamento observável mudou.

## Mudanças que exigem ADR

Se sua mudança altera uma decisão arquitetural, escreva um ADR novo que **supere**
o anterior. ADR aceito nunca é editado. Ver [docs/adr/README.md](docs/adr/README.md).

Exemplos: trocar transporte, banco, formato de plugin, modelo de permissão,
adicionar um processo, mudar a hierarquia de confiança.

## Adicionando capacidade

Antes de escrever uma Skill, pergunte se aquilo não deveria ser um **MCP server**.
Quase sempre deveria — e aí você ganha isolamento de processo de graça e não
precisa de um PR aqui. Ver [MCP §5](docs/specs/mcp/design.md).

Skill nova precisa declarar: `inputSchema`, `baseRisk`, `assess(input)` quando o
risco depende de argumento, e o conjunto de `Effect` que produz.

## Estilo

Spotless reprova o build. Rode `./gradlew spotlessApply` antes de commitar.
Código em inglês; documentação e mensagens ao usuário em português.

Padrões completos — Clean Code, SOLID, code smells, limites de complexidade,
naming e quando comentar — em [Padrões de código](docs/process/code-standards.md).

## Definition of Done

> **Documentação não é subproduto do código. Ela faz parte da implementação.**

Compilar não é concluir. Uma tarefa está pronta quando os dez portões da
[Definition of Done](docs/process/definition-of-done.md) são verdadeiros:

```text
SPEC + implementação + testes + segurança + documentação + RAG + auditoria
```

Faltando qualquer item, a tarefa permanece **incompleta**.

## Reportando vulnerabilidade

**Não abra issue pública.** Ver [SECURITY.md](SECURITY.md).

## Licença

Ao contribuir, você concorda que sua contribuição é licenciada sob
[Apache License 2.0](LICENSE).
