---
document: security-supply-chain
module: security
section: supply-chain
version: 1
updatedAt: 2026-09-17
securityLevel: restricted
tags: [apache,pr,sbom,dependencias]
specId: null
---

# Segurança — open source e cadeia de suprimentos

## 1. Licença

O Zordon é software livre sob **Apache License 2.0** ([LICENSE](../../LICENSE)).

Por que Apache 2.0 e não MIT ou GPL, em [ADR-0013](../adr/ADR-0013-apache-2.md).
Em resumo: a cláusula expressa de patentes e o requisito de `NOTICE` importam
para um projeto de segurança que pode ser adotado em ambiente corporativo, e a
permissividade mantém o caminho aberto para integração com ferramentas de
licenças variadas.

Cabeçalho obrigatório em todo arquivo de código:

```java
/*
 * Copyright 2026 Willyan Faria
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
```

Verificado por Spotless; a ausência reprova o build.

## 2. A premissa: assuma comprometimento

Um projeto de segurança aberto tem um problema que outros projetos abertos não
têm: **ele é um alvo de valor**. Comprometer o Zordon dá ao atacante um processo
residente, com permissão de arquivo, processo e rede, na máquina de todo mundo
que o instalou — e com a confiança do usuário, que acredita estar protegido.

Por isso o projeto assume, como política e não como suspeita pessoal, que
qualquer um destes **pode estar comprometido**:

```text
Pull Request     fork          plugin        MCP server
Agent            modelo        biblioteca    script
qualquer contribuição externa
```

Isso não é desconfiança de pessoas. É reconhecer que ataques de cadeia de
suprimentos funcionam precisamente porque o mantenedor confia — e que a defesa
correta é tornar a confiança desnecessária.

## 3. Pipeline obrigatória de PR

**Nenhum PR externo faz merge automático. Nenhum.**

```text
   PR aberto
      │
      ▼
   ┌──────────────────────────────────────────────────────┐
   │ 1. CI — build + testes                               │
   │ 2. ArchUnit — as invariantes arquiteturais           │
   │ 3. SAST — análise estática de segurança              │
   │ 4. Dependency Scan — CVE em dependências             │
   │ 5. Secret Scan — segredo no diff e no histórico      │
   │ 6. SBOM diff — o que entrou na árvore de dependência │
   │ 7. License check — compatibilidade com Apache 2.0    │
   │ 8. Golden tests — permissão, detectores, protocolo   │
   └───────────────────────┬──────────────────────────────┘
                           ▼
   ┌──────────────────────────────────────────────────────┐
   │ 9. Code review humano                                │
   │ 10. CODEOWNERS do caminho tocado                     │
   └───────────────────────┬──────────────────────────────┘
                           ▼
                        Merge
```

Nenhuma etapa é pulável por label, por urgência ou por quem abriu. Uma correção
urgente passa pela mesma pipeline — se a pipeline é lenta demais para uma
urgência, o problema é a pipeline.

### Regras adicionais para PR de fora

| Regra | Motivo |
|---|---|
| Workflows de CI **não rodam automaticamente** para contribuidores de primeira viagem | `pull_request_target` e segredos de CI são vetor conhecido |
| Nenhum workflow tem acesso a segredo em PR | Um PR não deve conseguir exfiltrar credencial de release |
| Arquivos binários exigem justificativa e revisão explícita | Binário é o payload mais simples |
| Alteração em `.github/workflows/` exige revisão do CODEOWNER | Alterar o CI é alterar quem verifica |
| Mudança de dependência **separada** da mudança funcional | Esconder uma dependência dentro de um PR grande é o truque clássico |
| Commits assinados em `zordon-security/`, `zordon-defense/`, `zordon-api/` | Autoria verificável no núcleo de confiança |

### Branch protection em `main`

```text
✅ Exigir PR antes de merge
✅ Exigir aprovação de 1+ revisor
✅ Exigir revisão de CODEOWNERS
✅ Descartar aprovações obsoletas ao surgir commit novo
✅ Exigir que todos os checks passem
✅ Exigir branch atualizada com a base
✅ Exigir commits assinados
✅ Exigir conversas resolvidas
✅ Bloquear force push
✅ Bloquear exclusão de branch
✅ Aplicar também a administradores
```

A última linha é a que costuma ser omitida e é a que mais importa: uma proteção
que o mantenedor pode contornar é uma proteção que a conta comprometida do
mantenedor pode contornar.

## 4. Verificações, em detalhe

| Etapa | Ferramenta sugerida | Reprova quando |
|---|---|---|
| SAST | CodeQL | Qualquer achado de severidade alta |
| Dependências | OWASP Dependency-Check, Dependabot | CVE alta ou crítica sem exceção documentada |
| Secret scan | Gitleaks + GitHub Secret Scanning | Qualquer candidato a segredo, inclusive no histórico do PR |
| SBOM | CycloneDX via plugin Gradle | Dependência nova não declarada no PR |
| Licenças | Plugin de license check | Licença incompatível com Apache 2.0 (GPL, AGPL, SSPL) |
| Arquitetura | ArchUnit | Violação das invariantes de [Componentes §4](../architecture/components.md) |
| Golden | Testes do repositório | Classificação de risco ou detector mudou sem revisão explícita |

O golden test de permissão é o mais importante da lista: ele transforma "essa
ação passou a ser considerada menos perigosa" — que um PR malicioso tentaria
esconder numa mudança de uma linha — em um diff explícito que o revisor **tem**
que aprovar conscientemente.

## 5. Release e distribuição

| Garantia | Como |
|---|---|
| Artefatos assinados | Assinatura do MSI e checksums do tarball publicados junto |
| SBOM por release | CycloneDX 1.6, `./gradlew sbom` → `build/reports/cyclonedx/bom.json` (74 componentes, 65 com licença, 63 com hash) |
| Proveniência | Atestação de build (SLSA) gerada pelo CI, não pela máquina do mantenedor |
| Build reproduzível | Metas: timestamps fixos, ordem determinística. Objetivo, não garantia ainda |
| Changelog com marcação de segurança | Correções de segurança sinalizadas explicitamente |
| Sem release manual | Só o CI publica; a máquina do mantenedor não tem credencial de publicação |

**Estado real (2026-09-20):** o SBOM e o changelog existem. Assinatura de
artefato, atestação SLSA e publicação só-pelo-CI ainda **não** — esta tabela
descreve o alvo, e o que falta está dito aqui para ninguém confundir plano com
garantia.

## 6. Segredos em CI

- Nenhum segredo disponível em workflow disparado por PR.
- Credencial de publicação só existe no ambiente de release, protegido por
  revisor obrigatório.
- Tokens de menor privilégio possível, com escopo e prazo.
- Rotação a cada 90 dias.
- Falha do secret scan **bloqueia o merge**; segredo encontrado no histórico é
  tratado como vazado e rotacionado, mesmo que pareça de teste.

## 7. Critérios para admitir uma dependência

Cada dependência é superfície de ataque herdada. A pergunta padrão é "dá para não
adicionar?".

| Critério | Exigência |
|---|---|
| Necessidade | Não é substituível por ~100 linhas nossas |
| Licença | Compatível com Apache 2.0 |
| Manutenção | Commit nos últimos 12 meses, mantenedor identificável |
| Adoção | Uso amplo e verificável |
| Árvore transitiva | Revisada; dependência que arrasta 40 outras é recusada |
| Superfície | Não pede rede, disco ou reflexão sem necessidade clara |
| Alternativa no JDK | Se o JDK resolve, usa-se o JDK |

Dependência nova em `zordon-security` ou `zordon-defense` exige justificativa
escrita no PR e revisão do CODEOWNER. Esses módulos devem ter a menor árvore
possível — idealmente, só o JDK.

### Dependências de `zordon-security` ([SPEC-014](../specs/security/SPEC-014-auditoria-validador-e-motor-de-permissao.md))

| Dependência | Licença | Por que não dá para não ter |
|---|---|---|
| `org.xerial:sqlite-jdbc` | Apache 2.0 | A auditoria precisa de triggers que recusam `UPDATE`/`DELETE` no próprio banco e de escrita transacional; o SQLite já é o banco da memória ([ADR-0008](../adr/ADR-0008-sqlite-como-memoria.md)). Traz o SQLite nativo, sem dependências transitivas |
| `jackson-databind` | Apache 2.0 | Serialização canônica (chaves ordenadas) dos argumentos auditados; já é a do núcleo |
| `tomlj` | Apache 2.0 | Ler `[paths]` e `[programs]` do `config.toml`; já é o leitor da configuração de IA |

### Dependências de `zordon-memory` ([SPEC-021](../specs/memory/SPEC-021-memoria-de-longo-prazo.md))

Nenhuma nova no build: `sqlite-jdbc` (com FTS5 embutido), `jackson-databind` e
`slf4j-api`, as mesmas de `zordon-security`. A busca vetorial (`sqlite-vec`) é
extensão nativa e entra só com a sua própria justificativa, no M8.

## 8. Plugins, MCP e modelos de terceiros

Código de terceiro que roda **no computador do usuário** recebe tratamento
específico, além da pipeline:

| Origem | Tratamento |
|---|---|
| Servidor MCP | `riskFloor` vem da nossa configuração, nunca do servidor. +1 nível de risco nos primeiros 7 dias. `ai.mcp-drift` detecta mudança de superfície entre conexões |
| Skill de terceiro | Não suportado no v1 — o caminho recomendado é MCP, que já isola por processo ([ADR-0012](../adr/ADR-0012-skills-in-process-primeiro.md)) |
| Modelo local | Checksum verificado; arquivo de modelo é dado que executa, e é tratado como binário |
| Agente compartilhado | É um arquivo TOML; o prompt é conteúdo `UNTRUSTED` até o usuário revisar. Teto de permissão vem da nossa política, não do arquivo |

Um agente compartilhado merece cuidado extra porque parece inofensivo — é "só um
arquivo de configuração". Mas o prompt dele é instrução para um sistema com
acesso à máquina. Importar um agente exige revisão do prompt na UI e confirmação
explícita do teto de permissão.

## 9. Divulgação de vulnerabilidade

Processo completo em [SECURITY.md](../../SECURITY.md). Resumo:

- Canal privado do GitHub (Private Vulnerability Reporting), nunca issue pública.
- Confirmação em 72 h; avaliação em 7 dias; correção em 30 dias (crítico: 7).
- Divulgação coordenada, com crédito.
- Porto seguro para pesquisa de boa-fé.
- Correção de segurança sempre acompanhada de teste de regressão que reproduz a
  falha — para que ela não volte silenciosamente.

## 10. Governança

Enquanto o projeto for de um mantenedor, a governança é simples e honesta sobre
isso:

- Um mantenedor, listado em [CODEOWNERS](../../CODEOWNERS).
- Decisões arquiteturais em ADR público, com alternativas descartadas registradas.
- Roadmap público ([Roadmap](../roadmap.md)).
- Nenhuma decisão de segurança tomada em canal privado; a discussão fica no ADR.

Se o projeto crescer, os pontos que precisarão mudar: mais de um CODEOWNER para
`zordon-security` (hoje há um único ponto de falha humano), processo de
promoção de contribuidor, e revisão de dois pares para mudanças no núcleo de
confiança. Está registrado aqui para não ser esquecido.
