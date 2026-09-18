---
document: spec-002
module: core
section: spec
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [spec,build,zwp,nucleo,endpoint,reconexao]
specId: SPEC-002
---

# SPEC-002 — Fundação: build, contrato ZWP e núcleo que sobe

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaAgent` |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M0 |
| **Supera** | — |

## 1. Objetivo

Ter um núcleo que sobe no WSL, serve o ZWP autenticado e é descoberto sozinho por
um cliente Windows — com as invariantes arquiteturais já verificadas no build.

## 2. Problema

O risco que pode reprovar o projeto inteiro está no ambiente, não na IA: se o
núcleo não sobrevive a um reboot do Windows e não é descoberto sem intervenção
([R1](../../architecture/windows-wsl.md#r1--o-wsl-não-sobe-no-boot-do-windows),
[R3](../../architecture/windows-wsl.md#r3--o-ip-do-wsl2-muda-a-cada-boot)), tudo
que for construído em cima é investimento em cima de areia. É melhor descobrir
isso na semana 1 do que no M5.

Medições desta máquina que moldaram as decisões desta SPEC:

- rede do WSL em modo `nat`, IP `172.x` variável a cada boot;
- `WSL_INTEROP` indisponível dentro de serviço systemd
  ([R5](../../architecture/windows-wsl.md#r5--wsl_interop-não-existe-dentro-de-um-serviço-systemd));
- o usuário do Windows e o do WSL **têm nomes diferentes**
  ([R21](../../architecture/windows-wsl.md#r21--o-usuário-do-windows-não-é-o-usuário-do-wsl));
- o JDK 25 **não** oferece socket Unix de datagrama, que é o que `sd_notify` usa.

## 3. Escopo

- Build Gradle multi-módulo com Kotlin DSL, wrapper com checksum fixado,
  version catalog, plugin de convenção, Spotless com cabeçalho de licença.
- `zordon-api`: records do ZWP, envelope de evento, tipos de identificação,
  anotações de rastreabilidade — **sem nenhuma dependência de produção**.
- `zordon-zwp`: codec JSON-RPC, leitura e escrita do `endpoint.json`, cliente ZWP
  e política de reconexão.
- `zordon-core`: barramento de eventos com `seq` e replay, servidor ZWP com
  autenticação, publicação do endpoint, notificação ao systemd, composition root.
- `zordon-desktop`: janela mínima que mostra o estado da conexão.
- Teste arquitetural (ArchUnit) com as regras verificáveis hoje.
- CI: build, testes, Spotless, `validateDocs`, `traceability`, SAST, varredura de
  segredo, revisão de dependências e licenças.
- Empacotamento: unit systemd, instalador do WSL, tarefa agendada do Windows.

## 4. Não escopo

- **Frames binários do ZWP.** O cabeçalho de 8 bytes é contrato, mas nada o usa
  antes do áudio; entra com o M2, junto de quem o exercita.
- **Requisições do núcleo para o cliente** (`bridge.*`, `ui.*`). Dependem do
  `PermissionEngine`, que é M3. O cliente responde `METHOD_NOT_FOUND` até lá.
- **Limite de taxa por conexão** (fechamento `4429`). Sem superfície para abuso
  enquanto só existem `session.*`.
- **`zordon-host`.** O M0 prova a descoberta com o desktop; o host tem sentido
  quando houver áudio e bridge.
- **Configuração em arquivo.** Enquanto o que há para configurar cabe em quatro
  variáveis de ambiente, um formato de configuração é um formato a manter.
- **Regras arquiteturais de módulos inexistentes** (`zordon-security`,
  `zordon-defense`, `zordon-notify`). Entram junto com os módulos, no M3 e no M7.

## 5. Arquitetura

```text
zordon-api      contrato compartilhado Windows ↔ WSL, dependência zero
   ▲
   ├── zordon-zwp      codec, endpoint.json, cliente, backoff
   │      ▲       ▲
   │      │       └── zordon-desktop   (JavaFX; conhece só api e zwp)
   │      │
   └── zordon-core     EventBus, ZwpServer, EndpointPublisher, SystemdNotifier
```

`zordon-zwp` não estava no mapa de módulos original e é justificado em
[ADR-0025](../../adr/ADR-0025-modulo-de-transporte-zwp.md): o codec não cabe em
`zordon-api` sem quebrar a regra de dependência zero, e duplicá-lo entre núcleo e
clientes seria duplicação de contrato — o tipo de duplicação que
[padrões §4](../../process/code-standards.md#4-code-smells) trata como bloqueante.

## 6. Fluxo

```text
systemd inicia zordon-core
   │
   ├─ gera startId (ULID) e token de 256 bits, novos a cada inicialização
   ├─ sobe o ZwpServer e ESPERA a porta aceitar conexões
   ├─ publica endpoint.json em ~/.zordon e no perfil do Windows (escrita atômica)
   ├─ sd_notify READY=1        ← só agora o serviço é "ativo"
   └─ publica CORE_STARTED no barramento

zordon-desktop abre
   │
   ├─ lê endpoint.json; ausente → OFFLINE e observa o arquivo
   ├─ tenta cada endereço da lista, em ordem
   ├─ handshake: Authorization: Bearer <token>, sem Origin
   ├─ session.hello → {protocol, core{startId}, sessionId, resumed}
   │      startId diferente do anterior → resumed=false → descarta estado volátil
   └─ ONLINE

núcleo cai
   │
   ├─ cliente detecta pelo fechamento do socket → OFFLINE
   ├─ backoff 250 ms → 10 s com jitter de ±20%
   └─ endpoint.json muda → tenta imediatamente, sem esperar o backoff
```

## 7. Interfaces

Novas em `zordon-api` (contrato do ZWP v1):

```java
sealed interface ZwpMessage permits ZwpRequest, ZwpResponse, ZwpNotification
record ZwpRequest(long id, String method, Map<String,Object> params)
record ZwpResponse(long id, Map<String,Object> result, ZwpError error)
record ZwpNotification(String method, Map<String,Object> params)
record ZwpError(int code, String message, ZwpErrorKind kind, Map<String,Object> data)

record HelloParams(ClientInfo client, ProtocolRange protocol,
                   List<String> capabilities, ResumeRequest resume)
record HelloResult(int protocol, CoreInfo core, String sessionId,
                   List<String> capabilities, boolean resumed, long heartbeatIntervalMs)
record EndpointFile(int version, String startId, Instant startedAt, String networkingMode,
                    List<String> endpoints, String token, ProtocolRange protocol, long pid)

record EventEnvelope(long seq, Instant ts, EventType type, Map<String,Object> payload)
```

`EventEnvelope` difere do esboço de
[Interfaces §1](../../api/core-interfaces.md#1-tipos-base-zordon-api) em um ponto,
deliberadamente: `turnId`, `agentId` e `runId` viajam **dentro de `payload`** em
vez de no envelope. Um evento de sistema não tem turno; mantê-los no envelope
significaria três campos nulos atravessando a fronteira de módulo em todo evento,
contra a convenção do próprio documento.

Novas em `zordon-zwp`:

```java
class  ZwpCodec            // JSON-RPC ↔ records, ordem de campos determinística
class  EndpointFileStore   // escrita atômica, permissão 600, leitura tolerante
class  ZwpClient           // uma conexão, correlação de id, sem reconexão
class  CoreConnection      // descoberta, reconexão, observação do endpoint.json
class  ReconnectBackoff    // 250 ms → 10 s, jitter ±20%
```

Novas em `zordon-core`:

```java
class ZordonEventBus      // publish nunca bloqueia; seq monotônico; replay
class ZwpServer           // único listener do sistema
class SessionMethods      // session.hello | subscribe | unsubscribe | ping
class EndpointPublisher   // token novo por inicialização
class SystemdNotifier     // sd_notify via FFM
```

## 8. Eventos

| Evento | Tópico | Payload | Quem publica |
|---|---|---|---|
| `CORE_STARTED` | `system` | `{startId, version}` | `ZordonCore` ao ficar pronto |
| `SYSTEM_ALERT` | `system` | `{severity, source, message}` | reservado, ainda sem emissor |

Assinatura por tópico, com duas políticas de fila distintas: `security` e `change`
usam `REJECT_PUBLISH` (falham alto em vez de descartar em silêncio), os demais
usam `DROP_OLDEST` com marcador de descontinuidade
([ADR-0011](../../adr/ADR-0011-event-bus-com-replay.md)).

## 9. Dados

Nenhum banco. Dois arquivos:

| Arquivo | Onde | Retenção |
|---|---|---|
| `endpoint.json` | `~/.zordon/` e `<perfil Windows>/.zordon/` | sobrescrito a cada inicialização |
| `logback.xml` | recurso do módulo | versionado |

O `endpoint.json` **não é apagado** no encerramento. Seria a coisa "óbvia" a
fazer, e é justamente o que [ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md)
proíbe: o sistema não apaga arquivos, sem exceção de conveniência. O cliente
descobre que o núcleo saiu pelo socket, não pela ausência do arquivo, e o token
daquela execução deixa de valer na seguinte.

## 10. Segurança

- **Que `Effect` produz?** `WRITE_FS` em dois caminhos fixos (`~/.zordon/` e
  `<perfil>/.zordon/`) e `NETWORK` (escuta local). Nenhum outro.
- **Risco, e varia por argumento?** Não varia: não há ação do usuário aqui. O
  núcleo ainda não executa nada em nome de ninguém.
- **Superfície nova para conteúdo não confiável?** Sim, e é a mais importante
  desta SPEC: uma porta WebSocket alcançável por qualquer processo do usuário e
  por qualquer aba de navegador
  ([R12](../../architecture/windows-wsl.md#r12--qualquer-coisa-no-pc-pode-falar-com-a-porta-do-núcleo)).
  Três camadas respondem: token de 256 bits novo a cada inicialização, lido de um
  arquivo que só o usuário lê; recusa de qualquer handshake com `Origin`, que
  navegador sempre envia e não consegue omitir; e nenhum método servido antes do
  `session.hello`.
- **Toca segredo?** O token. Ele é gravado com permissão `600`, nunca aparece em
  log, e a comparação usa `MessageDigest.isEqual` para não vazar informação por
  tempo de resposta.
- **Ação autônoma?** Nenhuma. O núcleo não decide nada em nome do usuário neste
  marco.

Duas decisões de segurança merecem registro explícito:

**Onde o núcleo escuta depende do modo de rede.** Em `nat`, o serviço precisa
escutar em `0.0.0.0` ou o `localhost` do Windows não o alcança. Em `mirrored`,
`0.0.0.0` exporia a porta à rede local — então lá o padrão é `127.0.0.1`. O modo
é resolvido na instalação, lendo o `.wslconfig`, e vai para a unit.

**A única chamada nativa do núcleo é o `sd_notify`.** Ela existe porque o JDK não
expõe socket Unix de datagrama e a alternativa seria o serviço se declarar pronto
antes de estar. Fica isolada em `zordon.core.platform`, e uma regra do ArchUnit
reprova qualquer uso de `java.lang.foreign` fora dali.

## 11. Permissões

Não se aplica: nenhum agente executa nada neste marco e o `PermissionEngine` não
existe ainda. O que existe é o contrário da permissão — a **ausência de
capacidade**: não há método ZWP que leia arquivo, execute processo ou alcance o
Windows.

## 12. Observabilidade

| Sinal | Onde |
|---|---|
| `ZWP escutando em <bind>:<porta>/zwp/v1` | log, ao subir |
| `sessão <id> com <kind> <versão>, resumed=<bool>` | log, por handshake |
| Estado da conexão (`ONLINE`/`CONNECTING`/`OFFLINE`) | barra de status do desktop |
| `EventSubscription.droppedCount()` | por assinante; deve ser zero em tópico obrigatório |
| `systemd status` do serviço | `STATUS=ZWP em ws://…` |

## 13. Casos de erro

| Falha | Comportamento | O que o usuário vê |
|---|---|---|
| Token ausente ou errado | Fecha com `4401` | Desktop permanece "NÚCLEO OFFLINE" |
| Handshake com `Origin` | Fecha com `4403` | — (é um navegador, não o usuário) |
| Caminho diferente de `/zwp/v1` | Fecha com `4400` | — |
| Método antes do `session.hello` | `ERR_UNAUTHORIZED` | erro de programação; fica no log |
| Versão de protocolo incompatível | `ERR_PROTOCOL_UNSUPPORTED` | "atualize o cliente" |
| `endpoint.json` ausente | Cliente fica OFFLINE e observa o arquivo | "procurando o núcleo" |
| `endpoint.json` truncado | Tratado como ausente | idem — melhor do que autenticar com token cortado |
| `/mnt/c` não montado | Núcleo sobe, endpoint não publicado no Windows, log de aviso | núcleo funcional porém indescobrível |
| Perfil do Windows não resolvido | Mesmo comportamento, aviso na instalação | idem |
| Porta ocupada | `onError` sem conexão; `awaitListening` estoura e o núcleo não se declara pronto | systemd reinicia |
| `sd_notify` indisponível | Log de aviso; núcleo continua | serviço sobe sem notificação |
| Assinante travado | Fila descarta o mais antigo e marca descontinuidade | UI avisa que perdeu histórico |
| Núcleo reinicia | `startId` novo → `resumed=false` | UI recarrega em vez de mostrar estado velho |

## 14. Testes

| Arquivo | Nível | Cobre |
|---|---|---|
| `ArchitectureTest` | arquitetura | as sete regras verificáveis hoje |
| `ZwpHandshakeTest` | integração | handshake contra um núcleo real em porta efêmera |
| `ZordonEventBusTest` | unidade | sequência, filas, replay, transbordo |
| `ZordonConfigTest` | unidade | modo de rede → bind; perfil do Windows |
| `StartIdTest` | unidade | formato ULID e ordenação por tempo |
| `ZwpCodecTest` | contrato | amostras douradas do protocolo |
| `EndpointFileStoreTest` | unidade | escrita atômica, permissão, arquivo truncado |
| `ReconnectBackoffTest` | unidade | escalada, teto e jitter |

Os testes de handshake sobem **o servidor de verdade**, não um substituto: o que
se quer provar é que o handshake recusa quem deve recusar, e um fake do servidor
provaria apenas que o fake foi escrito para recusar.

## 15. Critérios de aceite

- `CA-1` Dado um núcleo no ar, quando um cliente com o token do `endpoint.json`
  envia `session.hello`, então a resposta traz `protocol=1`, o `startId` da
  execução e um `sessionId`.
- `CA-2` Dado um cliente com token inválido, quando ele conecta, então a conexão
  é fechada com o código `4401`.
- `CA-3` Dado um handshake que traz o cabeçalho `Origin`, quando ele chega, então
  a conexão é fechada com o código `4403`.
- `CA-4` Dada uma conexão autenticada, quando um método é chamado antes do
  `session.hello`, então a resposta é `ERR_UNAUTHORIZED`.
- `CA-5` Dado um `resume` com `startId` diferente do da execução atual, quando o
  cliente se apresenta, então a resposta traz `resumed=false`.
- `CA-6` Dado um assinante que não consome, quando 50 eventos são publicados,
  então nenhuma publicação bloqueia e a sequência continua avançando.
- `CA-7` Dada uma fila com política de não descarte, quando ela enche, então a
  publicação falha alto em vez de descartar em silêncio.
- `CA-8` Dado um pedido de replay a partir de um `seq`, quando o anel ainda o
  cobre, então só os eventos posteriores a ele são devolvidos.
- `CA-9` Dado o `endpoint.json` publicado, então ele é legível apenas pelo dono, e
  um arquivo truncado é tratado como ausente.
- `CA-10` Dado um núcleo indisponível, quando o cliente tenta reconectar, então o
  intervalo cresce até no máximo 10 s e dois clientes não voltam no mesmo instante.
- `CA-11` Dado um ambiente sem `ZORDON_WINDOWS_HOME`, então nenhum caminho do
  Windows é derivado do usuário do WSL.
- `CA-12` Dada uma mensagem do protocolo, quando serializada, então os bytes são
  idênticos às amostras douradas versionadas.
- `CA-13` Dada uma violação das regras de dependência, quando o build roda, então
  ele reprova.
- `CA-14` Dado um campo desconhecido em uma mensagem, quando o cliente a recebe,
  então ele a processa ignorando o campo.
- `CA-15` Dado o `startId` de duas inicializações, então a ordem lexicográfica
  deles é a ordem temporal.
- `CA-16` Dado um cliente conectado, quando o núcleo cai e volta, então o cliente
  reconecta sozinho, recebe `resumed=false` e descarta o estado volátil — sem
  nenhuma ação do usuário.
- `CA-17` Dada uma VM com pontes virtuais (Docker), então o endereço alternativo
  publicado no `endpoint.json` é o da interface da rota padrão, e nunca o de uma
  ponte inalcançável a partir do Windows.

## 16. Impacto em outros módulos

- Define o mapa de módulos inicial; `docs/architecture/components.md` §2 ganha
  `zordon-zwp`.
- `docs/api/zwp-protocol.md` §6 passa a descrever o envelope de evento como
  implementado (correlação no `payload`).
- `docs/testing/strategy.md` §3 passa a localizar o teste de contrato em
  `zordon-zwp:test`, onde o codec vive.
- Nada quebra: não havia código antes desta SPEC.

## 17. Dependências

- [ADR-0001](../../adr/ADR-0001-nucleo-no-wsl2.md) — núcleo no WSL2
- [ADR-0002](../../adr/ADR-0002-gradle-como-build.md) — Gradle com Kotlin DSL
- [ADR-0003](../../adr/ADR-0003-websocket-json-rpc.md) — WebSocket com JSON-RPC
- [ADR-0006](../../adr/ADR-0006-descoberta-por-arquivo-de-endpoint.md) — arquivo de endpoint
- [ADR-0011](../../adr/ADR-0011-event-bus-com-replay.md) — barramento com replay
- [ADR-0025](../../adr/ADR-0025-modulo-de-transporte-zwp.md) — módulo de transporte
- [SPEC-001](../process/SPEC-001-validacao-de-documentacao.md) — portões de documentação
