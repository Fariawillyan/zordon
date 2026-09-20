# Changelog

Todas as mudanças relevantes deste projeto ficam aqui.

O formato segue [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as
versões seguem [SemVer](https://semver.org/lang/pt-BR/). Correções de segurança
são marcadas com **[SEGURANÇA]**, como o [SECURITY.md](SECURITY.md) promete.

## [Não publicado]

### Adicionado

- SBOM CycloneDX das dependências distribuídas (`./gradlew sbom`), cumprindo o
  compromisso do `SECURITY.md`. O plugin é de build e não entra no binário.
- Cobertura de testes medida (`./gradlew coverage`): 76,4% das linhas do projeto.

## [0.1.0] — 2026-09-20

Primeira versão pública. Marcos M0 a M8 implementados; as SPECs correspondentes
estão em `IMPLEMENTING` — implementadas e testadas, aguardando revisão do owner.

### Adicionado

- **M0 · Fundação** — núcleo como serviço systemd no WSL2, protocolo ZWP
  (JSON-RPC sobre WebSocket), host no Windows e janela JavaFX.
- **M1 · Conversa** — chat com streaming, providers configuráveis por papel
  (`conversation`, `routing`, `agent_*`, `summarize`, `fallback`) e custo por
  resposta na tela.
- **M2 · Voz** — palavra de ativação treinada no próprio projeto, motor de voz
  como sidecar Python, console de voz e efeitos sonoros.
- **M3 · Ação com segurança primeiro** — auditoria em cadeia SHA-256, validador
  de comandos sem shell, motor de permissão com 48 casos golden, diálogo com
  *Negar* como padrão, kill switch, execução mediada (um único `ProcessBuilder`
  no projeto), ponte com o Windows e cofre de quarentena reversível.
- **M4 · Ferramentas e MCP** — o modelo chama ferramentas dentro do turno, com
  tetos; cliente MCP por stdio que bloqueia servidor que muda de superfície até
  aprovação na tela.
- **M5 · Memória, agentes e planos** — memória de longo prazo em SQLite com FTS5,
  agentes como arquivo TOML com teto e orçamento, e planos duráveis que só
  concluem com veredito.
- **M6 · Monitor e automações** — amostragem de `/proc` com frequência
  adaptativa, eventos do Docker por push, e automações aprovadas na tela.
- **M7 · Defesa** — detectores de injeção de prompt e de comportamento do host,
  correlação por sujeito, resposta reversível e disjuntores.
- **M8 · Plataforma** — documentação indexada com citação de arquivo e seção,
  agentes de engenharia, preflight de nove passos que termina esperando o dono, e
  contagem de tokens por dia e por ator.

### Segurança

- **[SEGURANÇA]** O provider por assinatura passou a ser a primeira opção e a
  chave de API paga por uso, a última — em código, não só em configuração
  ([SPEC-018 §3](docs/specs/core/SPEC-018-provider-por-assinatura-claude-cli.md)).
- **[SEGURANÇA]** Nenhum segredo é gravado em `config.toml`: o arquivo guarda o
  **nome** da variável de ambiente, e uma chave escrita nele é recusada na
  leitura.
- **[SEGURANÇA]** Modelos de voz são fixados por URL, SHA-256 e tamanho em
  `voice/models.lock`; o instalador recusa arquivo que não bata.

### Limitações conhecidas

- A **palavra de ativação não atende ao próprio critério**: 71% de acerto contra
  os 95% pedidos, e a frase dita de um fôlego não dispara. Dois testes estão
  marcados como falha esperada, de propósito
  ([SPEC-013](docs/specs/voice/SPEC-013-palavra-de-ativacao-e-conversa-sem-clique.md)).
- A **auto-modificação para no preflight**: o Zordon planeja a mudança do próprio
  código e registra o plano, mas não a executa.
- Embeddings locais, detecção que exige root e telas próprias de Conhecimento e
  Uso ficaram para depois, com o motivo em [docs/roadmap.md](docs/roadmap.md).

[Não publicado]: https://github.com/Fariawillyan/zordon/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Fariawillyan/zordon/releases/tag/v0.1.0
