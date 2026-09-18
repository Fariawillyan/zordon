# Política de Segurança do Zordon

O Zordon executa ações reais na máquina pessoal de quem o usa e atua como camada
de defesa dessa máquina. Uma vulnerabilidade aqui não é um bug comum: ela pode
significar execução de código, exfiltração de credenciais ou desativação de uma
proteção que o usuário acredita estar ativa.

## Versões suportadas

Durante a fase pré-1.0, apenas a versão mais recente recebe correções.

| Versão | Suporte |
|---|---|
| `main` / último release | ✅ |
| Qualquer anterior | ❌ |

## Reportar uma vulnerabilidade

**Não abra uma issue pública.**

Use o canal privado do GitHub: **Security → Report a vulnerability**
(GitHub Private Vulnerability Reporting).

Inclua, se possível:

- versão do Zordon, do Windows e da distro WSL;
- o componente afetado (núcleo, host, desktop, sidecar de voz, uma Skill, o
  Permission Engine, o Defense Engine);
- passos de reprodução ou prova de conceito;
- o impacto que você consegue demonstrar;
- se a falha permite contornar o `PermissionEngine`, o `SecurityPolicy` ou o
  `AuditLog` — isso eleva a severidade automaticamente.

**Prazos de resposta:**

| Etapa | Prazo |
|---|---|
| Confirmação de recebimento | 72 horas |
| Avaliação inicial e severidade | 7 dias |
| Correção ou plano de correção | 30 dias (crítico: 7 dias) |
| Divulgação coordenada | Após a correção, com crédito ao relator |

## Escopo

**Dentro do escopo — reporte:**

- Contornar o `PermissionEngine` (executar ação YELLOW/RED sem a decisão correta).
- Contornar o `CommandValidator` (alcançar caminho em `forbidden`, executar
  programa fora da lista de permissão, injetar argumento).
- Adulterar o `AuditLog` sem quebrar a cadeia de hash.
- Alterar a `SecurityPolicy` por qualquer caminho que não seja o usuário no
  sistema de arquivos.
- Fazer o núcleo aceitar uma conexão ZWP não autenticada, ou vinda de um
  navegador.
- Exfiltrar segredos via log, evento, prompt, notificação ou tela.
- Qualquer caminho que faça o Zordon **apagar** um arquivo
  (ver [ADR-0015](docs/adr/ADR-0015-exclusao-impossivel-por-construcao.md)).
- Prompt injection que resulte em **ação executada**, não apenas em texto
  indevido na resposta.
- Elevar privilégio de um Agent, MCP ou Tool acima do teto declarado.
- Desativar o Defense Engine ou sair do Defense Lockdown sem o usuário.

**Fora do escopo:**

- O modelo de linguagem produzir conteúdo incorreto, ofensivo ou inventado sem
  que isso resulte em ação executada.
- Ataques que exigem que o atacante já execute código como o mesmo usuário —
  nesse cenário ele já tem os arquivos do usuário, e o Zordon não é o elo fraco.
- Ataques físicos à máquina.
- Vulnerabilidades em servidores MCP de terceiros (reporte ao projeto deles;
  avise-nos se o Zordon amplifica o impacto).
- Negação de serviço obtida configurando o próprio Zordon de forma absurda.

## Compromissos do projeto

- Nenhum PR externo entra sem revisão humana e sem a pipeline completa
  ([Cadeia de suprimentos](docs/security/supply-chain.md)).
- Toda release publica SBOM e artefatos com checksum.
- Correções de segurança entram com teste de regressão que reproduz a falha.
- O `CHANGELOG` marca correções de segurança explicitamente.

## Porto seguro

Pesquisa de segurança feita de boa-fé, sem acessar dados de terceiros, sem
degradar serviço de outras pessoas e com divulgação coordenada, é bem-vinda e não
será tratada como ato hostil.
