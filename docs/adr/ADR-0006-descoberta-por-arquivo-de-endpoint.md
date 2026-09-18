---
document: adr-0006
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,descoberta,por,arquivo,de,endpoint]
specId: null
---

# ADR-0006 — Descoberta e autenticação por arquivo de endpoint

**Status:** Aceito · 2026-09-17

## Contexto

Os clientes Windows precisam descobrir onde o núcleo está e provar que têm o
direito de falar com ele. Dois problemas ao mesmo tempo:

**Descoberta.** Em modo NAT, o IP do WSL2 muda a cada boot da VM
([R3](../architecture/windows-wsl.md#r3--o-ip-do-wsl2-muda-a-cada-boot) —
**[medido]** `172.19.x.x/20` nesta máquina). `localhost` funciona via relay,
mas só se o serviço fizer bind em `0.0.0.0`, e o relay já falhou
historicamente. Em modo espelhado, `127.0.0.1` funciona — mas nem toda instalação
pode usar modo espelhado.

**Autenticação.** Um WebSocket em loopback é alcançável por qualquer processo do
usuário e, criticamente, por **qualquer página web aberta no navegador** — a
política de mesma origem não se aplica a WebSockets
([R12](../architecture/windows-wsl.md#r12--qualquer-coisa-no-pc-pode-falar-com-a-porta-do-núcleo)).

## Alternativas

**A. Porta fixa em `localhost`, sem autenticação.** Simples e comum em
ferramentas locais. Inaceitável: qualquer aba de navegador teria um assistente
com acesso a arquivos e processos.

**B. Porta fixa + token em arquivo de configuração compartilhado.** Resolve a
autenticação, mas não a descoberta em modo NAT quando o relay falha, e um token
estático em configuração é um segredo de longa duração.

**C. mDNS / descoberta por broadcast.** Resolve descoberta, não resolve
autenticação, e adiciona dependência e superfície de rede desnecessárias numa
comunicação que é local por definição.

**D. Arquivo de endpoint escrito pelo núcleo em área visível ao Windows**,
contendo endereços candidatos e um token gerado a cada inicialização.

## Decisão

**Alternativa D.** Ao subir, o núcleo escreve atomicamente (`.tmp` + `rename`):

```
WSL:     $WIN_HOME/.zordon/endpoint.json      (ex.: /mnt/c/Users/<user>/.zordon/)
Windows: %USERPROFILE%\.zordon\endpoint.json
```

`$WIN_HOME` é resolvido na instalação **perguntando ao Windows**, nunca derivando
de `$USER`: o usuário do WSL e o do Windows podem ter nomes diferentes — nesta
máquina, **[medido]**, eles diferem
([R21](../architecture/windows-wsl.md#r21--o-usuário-do-windows-não-é-o-usuário-do-wsl)).

```json
{ "version": 1,
  "startId": "01J9X2K7QF8ZP3",
  "startedAt": "2026-09-17T22:31:04Z",
  "networkingMode": "mirrored",
  "endpoints": ["ws://127.0.0.1:8777/zwp/v1", "ws://<WSL_IP>:8777/zwp/v1"],
  "token": "<256 bits, base64url>",
  "protocol": { "min": 1, "max": 1 },
  "pid": 4211 }
```

O arquivo resolve **três** problemas de uma vez:

1. **Descoberta.** Lista de endpoints candidatos, tentados em ordem. O modo de
   rede é informado para o cliente saber o que esperar.
2. **Autenticação.** Token novo a cada inicialização, enviado em
   `Authorization: Bearer`. Curta duração por construção.
3. **Prova de co-localização.** Só um processo rodando como o mesmo usuário na
   mesma máquina consegue ler o arquivo. Isso é a autorização: não é preciso
   verificar identidade de processo, basta o segredo ser inacessível de fora.

O ponto que fecha a defesa contra o navegador: **navegadores não conseguem
definir cabeçalhos customizados em conexões WebSocket**, e não conseguem ler o
arquivo. Uma aba maliciosa não tem como se autenticar. A rejeição adicional de
qualquer handshake que traga `Origin` é a segunda camada.

Os clientes observam o diretório; quando o arquivo muda (núcleo reiniciou),
reconectam imediatamente com o novo endereço e token, sem esperar o backoff.

## Consequências

**Positivas.** Funciona em NAT e em espelhado sem configuração. Token efêmero
sem gerenciamento de segredo. Reconexão rápida após reinício do núcleo. Sem
dependência de rede adicional. Autorização por posse de segredo é simples de
raciocinar e difícil de errar.

**Negativas.** Depende de `/mnt/c` estar montado — se não estiver, o núcleo
funciona mas é indescobrível (situação reportada como `degraded`). É a única
escrita rotineira em 9p, embora aconteça uma vez por inicialização e o custo seja
irrelevante ([R9](../architecture/windows-wsl.md#r9--io-em-mntc-é-ordens-de-grandeza-mais-lento)).
Um processo malicioso rodando **como o mesmo usuário** lê o token — mas esse
processo já teria acesso direto aos arquivos do usuário, então o Zordon não é o
elo fraco nesse cenário.

**Permissões do arquivo.** No lado Windows, ACL restrita ao usuário. O diretório
`.zordon` no perfil não deve ser sincronizado por OneDrive — o instalador
verifica e alerta.
