---
document: spec-036
module: security
section: spec
version: 1
updatedAt: 2026-09-23
securityLevel: restricted
tags: [spec,seguranca,permissao,oppressor,senha-mestre,execucao-direta,m3]
specId: SPEC-036
---

# SPEC-036 — OPPRESSOR MODE: execução direta sob senha mestre

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | SecurityAgent |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M3 |
| **Supera** | — |

## 1. Objetivo

Um modo em que o Zordon executa a ordem do usuário sem intermediação: a senha
mestre é a autorização, e depois dela o motor de permissão sai do caminho.

## 2. Problema

O modelo de confiança do Zordon é de usuário único e local
([Visão §8](../../vision.md)). Mesmo assim, toda ação acima de GREEN pede
confirmação — e quem confirma é a mesma pessoa que deu a ordem. Em uma sessão
de trabalho longa, isso é fricção que não compra segurança: a pessoa clica
"sim" dezenas de vezes sem ler, o que é pior do que não perguntar, porque
treina o reflexo de aprovar.

O que falta é uma forma de dizer "eu já decidi, por esta sessão" uma única vez,
com uma prova de que é o dono falando, e que valha para o processo inteiro em
vez de para uma ferramenta de cada vez.

## 3. Escopo

- Senha mestre: cadastro, troca e verificação.
- Entrada e saída do modo, com estado em memória e eventos.
- Desvio no `Gatekeeper`: com o modo ativo, a ação é liberada sem avaliação.
- Indicação permanente e inequívoca na janela enquanto o modo está ativo.
- Registro em auditoria de toda ação liberada pelo modo.
- Pedido por voz: a fala abre o diálogo da senha na tela; nunca ativa sozinha.

## 4. Não escopo

- **Alterar o comportamento do modelo de IA.** O modo muda o que o Zordon
  *executa*, não o que ele *pensa*. Não existe aqui instrução de sistema que
  peça ao modelo para não recusar: seria ineficaz, e o provedor de IA tem
  política própria sobre isso ([ADR-0026](../../adr/ADR-0026-provider-agnostico.md)).
- Segunda camada de autorização dentro do modo. A senha é a única porta.
- Elevação de privilégio no sistema operacional. O modo usa o que o processo
  do Zordon já tem; não chama `sudo` nem pede mais que isso.
- Persistência do modo entre reinícios (ver §5).

## 5. Arquitetura

O desvio fica no `Gatekeeper`, que a [SPEC-016](SPEC-016-execucao-mediada-ferramentas-e-ponte-windows.md)
define como o único caminho de uma ação até a execução. É isso que torna o modo
real em vez de decorativo: não há como uma ferramenta escapar dele, porque não
há outro caminho.

```
ação → Gatekeeper.authorize
         ├── modo ativo e sem lockdown → Allow direto, auditoria, executa
         └── caso contrário            → PermissionEngine.evaluate (fluxo normal)
```

Três decisões estruturais:

**O estado não sobrevive ao reinício.** O `LockdownService` persiste porque o
seguro dele é continuar fechado. Aqui o seguro é acordar fechado: um núcleo que
reiniciou por queda, atualização ou supervisor não deve voltar com o motor de
permissão desligado sem ninguém ter digitado nada. Só o hash da senha vai a
disco.

**O lockdown vence o modo.** É a única coisa que o modo não atravessa. Um kill
switch que um modo desliga não é um kill switch; e ele é acionado sozinho pela
defesa quando a cadeia da auditoria aparece quebrada
([Modelo §8](../../security/model.md)), que é exatamente quando a senha digitada
minutos antes deixou de provar alguma coisa. Sair do lockdown continua sendo
ação na tela ([SPEC-015](SPEC-015-pedido-de-permissao-notificacoes-e-kill-switch.md) CA-6),
e é uma chamada só.

**A auditoria não é etapa de aprovação.** `AuditLog.begin` grava a intenção e a
execução segue na mesma linha, sem esperar confirmação de escrita.

## 6. Fluxo

1. O usuário cadastra a senha mestre uma vez (`security.oppressor.password`).
2. Para entrar, envia a senha (`security.oppressor.enter`). O núcleo deriva e
   compara em tempo constante.
3. Conferindo, o estado vira ativo, `OPPRESSOR_ENTERED` é publicado e a janela
   muda para o tema vermelho com o indicador permanente.
4. Cada ação seguinte passa pelo `Gatekeeper` e é liberada sem avaliação,
   gravada em auditoria com `by = oppressor`.
5. Ao sair (`security.oppressor.exit`) ou ao reiniciar o núcleo, o
   comportamento normal volta imediatamente e `OPPRESSOR_EXITED` é publicado.

Pela voz o caminho é o mesmo, com um passo a mais na frente:

1. A transcrição da escuta passa por `OppressorPhrase` **antes** de virar turno.
   A leitura é determinística e não passa pelo modelo: uma fala que mexe no
   motor de permissão não pode depender de interpretação.
2. Reconhecido um pedido de entrada, o núcleo publica `OPPRESSOR_PROMPT` e a
   fala **não** vira turno. A janela vem para a frente e pede a senha.
3. Digitada a senha, a janela chama `security.oppressor.enter` — o mesmo
   caminho do item 2 acima. Cancelar o diálogo não pede nada ao núcleo.
4. Reconhecido um pedido de saída, o modo desliga na hora, sem senha.

## 7. Interfaces

| Método ZWP | Parâmetros | Resposta |
|---|---|---|
| `security.oppressor.enter` | `password` | estado do modo |
| `security.oppressor.exit` | — | estado do modo |
| `security.oppressor.password` | `current`, `next` | `{configured}` |
| `security.status` | — | passa a incluir `oppressor` |

`enter` responde `ERR_PERMISSION_DENIED` com senha errada ou sem senha
cadastrada. `password` responde o mesmo se a atual não confere.

## 8. Eventos

| Evento | Carga |
|---|---|
| `OPPRESSOR_ENTERED` | `{since, trigger}` |
| `OPPRESSOR_EXITED` | `{by}` |
| `OPPRESSOR_PROMPT` | `{heard}` — a voz pediu; a janela deve pedir a senha |

`trigger` e `by` são o tipo do cliente ZWP que pediu, nunca a senha. `heard` é
a transcrição que casou, para o log dizer o que acionou o diálogo.

## 9. Dados

`<home>/state/oppressor.hash`, com permissão de dono apenas (0600), contendo
uma linha no formato `pbkdf2$<iterações>$<sal>$<chave>`, sal e chave em Base64
sem preenchimento. A senha em claro não é gravada em lugar nenhum.

O estado ativo/inativo vive só em memória.

## 10. Segurança

O que o modo protege: que outra pessoa o ative. A senha é derivada com
PBKDF2-HMAC-SHA256 e 600 000 iterações, sal por senha, comparação em tempo
constante. O custo das iterações é também o que limita força bruta pela porta
da frente — cada tentativa errada custa o mesmo trabalho.

**O que o modo não protege, e é preciso dizer com todas as letras:** enquanto
ativo, o teto de risco por origem do [ADR-0030](../../adr/ADR-0030-origem-da-ordem.md)
não vale. Ordens de origem `voice`, `automation`, `agent` e `autonomous`
executam como se fossem do usuário na janela. A consequência concreta está em
[Modelo §6](../../security/model.md#6-prompt-injection): uma instrução plantada
em qualquer conteúdo que o Zordon leia — página, e-mail, README, saída de
ferramenta — passa a valer como ordem, com as permissões do processo e sem
confirmação.

Isso é consequência aceita e registrada no [ADR-0041](../../adr/ADR-0041-oppressor-mode.md),
não um descuido. A mitigação disponível é operacional: o modo é para sessões
curtas e supervisionadas, e a indicação vermelha permanente existe para que
ninguém o esqueça ligado.

**A voz é assimétrica, de propósito.** Entrar por voz só abre o diálogo da
senha; quem autoriza é quem digita, na máquina. É a regra do ADR-0030 para
tudo que a voz aciona: qualquer pessoa na sala — ou um vídeo tocando — diz a
frase, e ninguém digita a senha sem estar ali. Sair por voz desliga direto,
sem senha, porque dificultar o caminho de volta não protege nada.

## 11. Permissões

O modo usa as permissões que o processo do Zordon já tem — arquivos, processos,
comandos, rede, aplicações — e nada além. Não há elevação no sistema
operacional. O que muda é que o Zordon deixa de impor limites próprios acima
dos do sistema.

Entrar e sair não exige sessão de desktop: a senha é a autorização, e exigir
também a janela seria a segunda camada que o modo não deve ter.

## 12. Observabilidade

- Entrada, saída e recusa por senha errada saem em `WARN` no diário do sistema.
- Cada ação liberada sai em `WARN` com `callId`, ferramenta e a marca do modo.
- A auditoria grava toda ação com `by = oppressor`, distinguível de `policy`,
  `user` e `timeout`.
- `security.status` expõe o estado a qualquer cliente conectado.
- A falha aparece na própria seção da janela, não na conversa: uma ação de
  segurança que não acontece precisa dizer por quê onde ela foi pedida.

## 13. Casos de erro

| Situação | Comportamento |
|---|---|
| Senha errada | `ERR_PERMISSION_DENIED`; modo não entra; `WARN` no log |
| Sem senha cadastrada | `ERR_PERMISSION_DENIED` com motivo próprio |
| Arquivo de hash ilegível | Tratado como "não confere": o modo não abre |
| Senha nova menor que 12 caracteres | `ERR_INVALID_ARGUMENT` no cadastro |
| Lockdown ativo | O modo não desvia; vale a política de só leitura |
| Núcleo reinicia com o modo ativo | Volta desligado, sem aviso de erro |
| Diálogo da voz cancelado ou fechado | Nada é pedido ao núcleo; o modo não entra |
| Núcleo sem o método (build antigo) | A seção mostra a falha; nada muda de estado |

## 14. Testes

- Derivação e verificação da senha, incluindo hash malformado e senha curta.
- Entrar, sair, entrar de novo; estado e eventos em cada transição.
- Bypass no `Gatekeeper`: ação RED que seria negada passa com o modo ativo.
- Lockdown ativo com o modo ativo: a ação continua negada.
- Reinício: o modo volta desligado.
- A senha é zerada do vetor após o uso, inclusive quando está errada.
- Leitura da fala: entrar, sair, e o que não é pedido de modo vira turno;
  acento, pontuação e caixa não mudam o resultado.

## 15. Critérios de aceite

- `CA-1` Dada uma senha mestre cadastrada, quando ela é enviada corretamente,
  então o modo entra, `OPPRESSOR_ENTERED` é publicado e `security.status`
  passa a informar `active: true`.
- `CA-2` Dada uma senha incorreta ou ausente, então o modo não entra e a
  resposta é `ERR_PERMISSION_DENIED`.
- `CA-3` Dado o modo ativo, quando uma ação que o motor negaria ou mandaria
  confirmar é pedida, então ela é liberada sem avaliação e gravada em
  auditoria com `by = oppressor`.
- `CA-4` Dado o modo ativo e o Zordon em lockdown, então a ação acima de GREEN
  continua negada: o lockdown não é atravessado.
- `CA-5` Dado o modo ativo, quando o núcleo reinicia, então ele volta com o
  modo desligado.
- `CA-6` Dado o modo ativo, quando o usuário sai, então o comportamento normal
  volta na ação seguinte e `OPPRESSOR_EXITED` é publicado.
- `CA-7` Dada a senha mestre em qualquer caminho, então ela não aparece em log,
  evento, auditoria nem resposta de método.
- `CA-8` Dado o modo ativo, então a janela mostra o tema vermelho e o indicador
  permanente `OPPRESSOR MODE`, sem depender de o usuário estar em alguma aba.
- `CA-9` Dada uma fala que pede o modo, então ela não vira turno: o núcleo
  publica `OPPRESSOR_PROMPT`, a janela pede a senha e só a senha digitada
  ativa. Dada uma fala que pede para sair, então o modo desliga sem senha.

## 16. Impacto em outros módulos

- `zordon-security`: `Gatekeeper` ganha o desvio; `MasterPassword` é novo.
- `zordon-core`: `OppressorService` e `OppressorPhrase` novos; `ZordonCore` o
  instancia antes do `Gatekeeper` e filtra a fala antes do turno;
  `SecurityMethods` ganha três métodos.
- `zordon-api`: três valores novos em `EventType`.
- `zordon-desktop`: `DesktopState` ganha as propriedades; o núcleo visual ganha
  o tema vermelho; `OppressorPane` é o diálogo da senha; `LogEntry` ganha o
  resumo dos três eventos.

## 17. Dependências

- [SPEC-014](SPEC-014-auditoria-validador-e-motor-de-permissao.md) — auditoria e motor de permissão.
- [SPEC-015](SPEC-015-pedido-de-permissao-notificacoes-e-kill-switch.md) — lockdown e kill switch.
- [SPEC-016](SPEC-016-execucao-mediada-ferramentas-e-ponte-windows.md) — o `Gatekeeper` como caminho único.
- [ADR-0030](../../adr/ADR-0030-origem-da-ordem.md) — o teto por origem que o modo suspende.
- [ADR-0041](../../adr/ADR-0041-oppressor-mode.md) — a decisão e o risco aceito.
