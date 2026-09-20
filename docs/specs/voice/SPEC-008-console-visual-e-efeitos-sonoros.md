---
document: spec-008
module: voice
section: spec
version: 2
updatedAt: 2026-09-18
securityLevel: public
tags: [spec,voz,desktop,audio,canvas,efeitos,windows,reproducao]
specId: SPEC-008
---

# SPEC-008 — Console visual e efeitos sonoros

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | Codex (v1) · Claude Code (v2: reprodução comprovada) |
| **Revisores** | Revisão local de arquitetura e testes; revisão humana pendente |
| **Marco** | M2 |
| **Supera** | Complementa SPEC-006; direção visual da tela de Voz substitui as restrições decorativas do design system §2 |

## 1. Objetivo

Transformar a tela de Voz em um console ciano inspirado na referência fornecida
pelo usuário, com efeitos sonoros reproduzíveis e visualização do áudio de saída.

## 2. Problema

A tela atual apresenta cartões de diagnóstico sem identidade sonora nem ação
para experimentar sons. O usuário solicitou a cena holográfica e efeitos reais,
com uma SPEC para manter o escopo rastreável. A implementação está autorizada
pelo pedido desta sessão; a autorização não equivale a revisão humana concluída.

**v2 — os efeitos nunca tocaram.** A v1 foi implementada e testada com saída
falsa, e o desktop roda hoje no WSLg, onde não há áudio
([R6](../../architecture/windows-wsl.md#r6--não-há-áudio-confiável-dentro-do-wsl)).
Medido em 2026-09-18:

| Onde o Java roda | Saídas encontradas | 0,5 s de silêncio |
|---|---|---|
| WSL (desktop atual) | nenhuma | falha ao abrir a linha |
| Windows (JDK 25 do usuário) | Primary Sound Driver, Alto-falantes (Realtek), LG ULTRAWIDE, Logi USB Headset | posição 22.050 de 22.050 frames em 568 ms |

Além disso, "tocou" era inferido de a linha aceitar a escrita, o que não prova
que o som saiu. O desktop é, pela arquitetura, uma aplicação Windows
([Componentes §5](../../architecture/components.md#5-empacotamento)); rodar lá é o
que torna os efeitos funcionais, e a posição do dispositivo é o que prova.

## 3. Escopo

- Modos existentes no topo, com resumos honestos de microfone e motor.
- Console com esfera de partículas, anéis, símbolo Zordon, ondas e grade em
  perspectiva desenhados em Canvas JavaFX e adaptados à largura disponível.
- Seis sinais sintetizados: ativação, escuta, processamento, resposta, conclusão
  e alerta. Botão Testar som, seletor de sinal, parar, volume e silenciar.
- Processamento PCM real: eco, reverberação, filtro suave e abertura estéreo.
- Ajustes de feedback automático e movimento reduzido; valores mantidos durante
  a sessão. Feedback automático desligado inicialmente.
- Medidor RMS e ondas derivados das amostras PCM na posição de reprodução;
  decoração estática em repouso, sem alegar captar o microfone.
- Detalhes de captura, dispositivo e transcrição preservados abaixo do console.

Acrescentado na v2, para garantir que os efeitos funcionam:

- **O desktop roda no Windows.** `:zordon-desktop:windowsDist` monta uma cópia
  executável no Windows, com os jars do JavaFX para Windows e um lançador que usa
  `javaw.exe` (sem console). `packaging/windows/install-desktop.sh`, rodado no
  WSL, compila, copia para `%LOCALAPPDATA%\Programs\Zordon\desktop` e cria o
  atalho "Zordon" no menu Iniciar. Exige Java 25 no Windows, como o host
  ([SPEC-007](../host/SPEC-007-host-do-windows.md)).
- **Reprodução comprovada.** Um som só conta como tocado quando, depois do
  `drain`, a posição do dispositivo chegou ao total de frames renderizados e o
  tempo decorrido foi de pelo menos 80% da duração. Uma saída que aceita bytes
  sem avançar, ou que os engole instantaneamente, é falha.
- **O painel diz onde tocou.** Antes do teste, o nome da saída padrão do
  Windows; depois, "Tocou em <saída> · <duração>". No WSL sem saída, a mensagem
  diz que a janela roda no WSL e manda abrir pelo Windows, em vez de uma falha
  genérica.
- **Verificação no Windows.** `:zordon-desktop:verifyWindowsAudio` roda, no Java
  do Windows, os seis sinais com volume zero (mesma duração, sem incomodar ninguém)
  pelo mesmo `SoundPlayer` da produção e reprova se algum não for comprovado.
  Fica fora do `verifyAll` porque a CI não tem Windows; a execução é registrada
  nas evidências.

## 4. Não escopo

Captura, STT, TTS, mudança de timbre de voz e efeitos aplicados ao microfone
dependem do host/sidecar da SPEC-006. Estes efeitos são feedback de interface,
reproduzidos na saída padrão do desktop. Sem persistência ou novo protocolo.

Também fora (v2): escolher a saída de áudio (usa o padrão do Windows, e o painel
diz qual é); tocar os efeitos pelo host, que exigiria frames binários; áudio no
WSLg, que continua sem suporte; instalador MSI com JRE embutido.

## 5. Arquitetura

Somente `zordon-desktop`: síntese e análise puras em `audio`, reprodução via
Java Sound `SourceDataLine` fora da thread JavaFX, painel e Canvas em `ui`.
Não depende do núcleo. Mantém [SPEC-006](SPEC-006-tela-e-estado-da-voz.md)
e [ADR-0009](../../adr/ADR-0009-captura-windows-inferencia-wsl.md): reprodução
de feedback local não constitui captura nem substitui o host Windows.

## 6. Fluxo

0. O usuário abre o Zordon pelo atalho do Windows; o desktop conecta ao núcleo
   pelo `%USERPROFILE%\.zordon\endpoint.json`.
1. Abrir Voz mostra estado real e console em repouso, sem emitir áudio.
2. Selecionar sinal/efeitos e Testar som sintetiza PCM estéreo de 44.100 Hz.
3. A linha de saída toca o PCM; Canvas consulta posição/amostras a até 30 fps.
   Ao fim, o player confere posição e tempo e informa "tocou" ou a falha.
4. Parar, silenciar, navegar para outra tela ou fechar a janela interrompe a saída.
5. Ajustes de DSP valem no próximo som; volume atua também no som atual.
6. Se habilitado, feedback reage apenas a transições reais de atividade com
   host conectado, motor pronto e captura confirmada; snapshot inicial é silencioso.

## 7. Interfaces

`SoundSynthesizer`: sinais, opções, renderização de PCM, RMS e forma de onda.
`SoundPlayer`: reproduzir/parar, volume, posição, estado e falha de saída; na v2,
`lastOutcome()` com `Played(saída, frames, duração)` ou `Failed(motivo)`, e
`outputName()` com a saída padrão.
`PlaybackCheck` (v2): `main` da verificação no Windows; código de saída 0 só se
os seis sinais forem comprovados.
`VoiceEffectsPane`: controles e ciclo de vida. `VoiceVisualizer`: Canvas.
Áudio sempre limitado; uma reprodução por vez, sem fila crescente.

## 8. Eventos

Consome mudanças no snapshot `VOICE_STATE` já exposto por `DesktopState`.
Não publica eventos. Não interpreta animação como `VOICE_LEVEL` do microfone.

## 9. Dados

PCM curto apenas em memória. Ajustes duram a sessão do desktop. Não armazena
gravações, transcrições, preferências em disco nem acessa dispositivos de entrada.

## 10. Segurança

Efeito: áudio local escolhido pelo usuário, risco baixo. Sem mudança de risco
por argumento, sem rede, segredos, caminhos sensíveis ou conteúdo externo.
Feedback automático exige opção explícita, exibida em Ajustes. Volume inicial
35%, limitado a 100%; síntese com headroom e envelopes para evitar clipping.

## 11. Permissões

Usuário do desktop testa sons e habilita feedback. Nenhuma capacidade de captura
é declarada, nenhum método `audio.setCaptureEnabled` é chamado pelo painel.

## 12. Observabilidade

Painel informa repouso, reprodução, silêncio ou falha de saída. Metadados
descrevem o formato PCM local, não a configuração do microfone. Falhas aparecem
com texto acionável e permitem nova tentativa.

v2: o painel mostra a saída usada e o resultado comprovado do último teste. O
log registra cada teste com saída, frames e duração (INFO) e falhas com motivo
(WARN).

## 13. Casos de erro

| Falha | Comportamento |
|---|---|
| Sem mixer/saída ocupada | Interromper e mostrar que a saída de áudio está indisponível |
| Núcleo desconectado | Modos indisponíveis; teste local continua utilizável |
| Parar durante abertura/escrita | Invalidar reprodução anterior e fechar linha |
| Cliques repetidos | Substituir som atual sem acumular fila |
| Volume zero/silêncio | PCM emitido zerado e medidor zerado |
| Ocultar painel/janela | Parar som e timer; reabrir não reinicia reprodução |
| Desktop no WSL, sem saída | "Esta janela roda no WSL, que não tem saída de áudio. Abra o Zordon pelo atalho do Windows." |
| Saída aceita bytes e a posição não avança | Falha: "a saída aceitou o áudio, mas não o reproduziu" |
| Saída consome tudo instantaneamente | Falha: tempo abaixo de 80% da duração não prova reprodução |
| Som tocou, mas no dispositivo errado | O painel nomeia a saída padrão do Windows; trocar é nas configurações de som do Windows |
| Java 25 ausente no Windows | `install-desktop.sh` para e mostra como instalar |

## 14. Testes

Testes unitários de PCM, envelopes, DSP, volume e ciclo de vida com saída falsa.
Testes JavaFX com display para controles, preservação de estado, dimensões,
captura indisponível e screenshots em `zordon-desktop/build/ui-snapshots`.
Build, Spotless, testes e validação de documentação/rastreabilidade do Gradle.
Audição e latência do dispositivo físico precisam de verificação humana.

### Evidências de implementação (2026-09-18)

- `./gradlew verifyAll :zordon-desktop:assemble`: aprovado; 229 testes sem
  falhas ou testes ignorados, incluindo 17 novos casos desta entrega.
- `validateDocs` e `traceability`: aprovados, sete critérios desta SPEC ligados
  a testes. Sem nova dependência externa.
- Capturas de repouso, ajustes e reprodução em
  `zordon-desktop/build/ui-snapshots/voice-*.png`. A captura de reprodução usa
  uma saída controlada de teste, com as mesmas amostras PCM da produção.
- Amostra dos seis sinais, na ordem do seletor, em
  `zordon-desktop/build/audio-preview/zordon-feedback.wav` (artefato de build,
  não versionado), com os efeitos padrão: eco, reverberação e estéreo.
- Neste WSL, `AudioSystem.getMixerInfo()` retornou lista vazia e a tentativa
  de abrir PCM estéreo falhou por ausência de saída compatível. A falha é
  tratada na interface; não houve confirmação auditiva do dispositivo físico.
- Implementação e validações automáticas concluídas. O status permanece
  `IMPLEMENTING` até a revisão humana e a confirmação auditiva no desktop
  com saída configurada; não representa pendência de captura/STT/TTS.

Próximo aceite manual: abrir Voz, testar os seis sinais com e sem cada efeito,
verificar volume/silêncio, sair da tela durante reprodução e confirmar que o
som para. O host e o motor de voz continuam sendo entregas separadas.

### Evidências da v2 (2026-09-18)

- `:zordon-desktop:verifyWindowsAudio` no JDK 25 do Windows: os seis sinais
  comprovados em "Primary Sound Driver" (a saída padrão do Windows). Cada um levou
  de 1,89 s a 1,91 s para 1,85 s de áudio (2,41 s para os 2,35 s de
  Processamento): a placa tocou em tempo real.
- `packaging/windows/install-desktop.sh`: instalou em
  `%LOCALAPPDATA%\Programs\Zordon\desktop`, criou o atalho "Zordon" e repetiu a
  verificação a partir da pasta instalada, com o mesmo resultado.
- O desktop no Java do Windows conectou ao núcleo pelo segundo endereço do
  `endpoint.json` (IP do WSL), depois de `127.0.0.1` ser recusado.
- Testes: `PlaybackProofTest` (posição parada, consumo instantâneo, tempo real,
  verificação), `WindowsDistTest` e `VoiceEffectsStatusTest`.
- Aceite humano em 2026-09-18: o owner ouviu os seis sinais no desktop do
  Windows.

### Testes da v2

- Unidade: `SoundPlayer` com saídas falsas que param a posição, engolem o áudio
  na hora, falham ao abrir ou tocam em tempo real.
- Build: o conteúdo de `windowsDist` (jars do JavaFX para Windows, lançador com
  `javaw.exe`).
- Windows: `verifyWindowsAudio` no Java do usuário, com o resultado nas
  evidências.
- **Aceite humano, obrigatório para `DONE`:** o usuário abre o Zordon pelo atalho
  do Windows, toca os seis sinais e confirma que ouviu cada um, com e sem os
  efeitos, na saída que o painel nomeia.

## 15. Critérios de aceite

> **`CA-1` refeito pela [SPEC-010](../ui/SPEC-010-shell-compacto-centrado-na-voz.md)**
> (2026-09-18): o console ocupa a janela, sem título de cartão nem rodapé, com os
> textos e posições da imagem de referência; os modos foram para os Ajustes. Em
> repouso o "NÍVEL RMS" fica apagado: aceso sem som seria um nível inventado.

- `CA-1` Console e modos cabem em 800 e 1200 px, sem rolagem horizontal;
  Canvas mostra esfera, ondas e identidade Zordon, com controles acessíveis.
- `CA-2` Os seis sinais geram PCM finito, limitado e não silencioso, com
  início/fim suaves, 44.100 Hz e dois canais.
- `CA-3` Eco, reverberação, filtro e estéreo alteram amostras de fato;
  reverberação/eco prolongam o sinal e estéreo diferencia canais.
- `CA-4` Volume zero gera saída e medidor zerados; volume intermediário
  reduz amplitude; RMS/onda usam a posição informada pela linha de áudio.
- `CA-5` Parar/substituir/ocultar fecha a reprodução e encerra animação;
  falha de saída é visível e permite repetir o teste.
- `CA-6` Sem host/motor o teste local permanece disponível e a tela preserva
  captura desconhecida, motivo e indisponibilidade do teste de microfone.
- `CA-7` Snapshot inicial não toca áudio; feedback automático desligado não
  toca áudio; quando habilitado só reage a atividade real confirmada.
- `CA-8` Um som só é informado como tocado quando, depois do `drain`, a posição
  da saída chegou ao total de frames e o tempo decorrido foi de pelo menos 80% da
  duração; posição parada ou consumo instantâneo são falha com motivo.
- `CA-9` O painel nomeia a saída antes do teste e informa "Tocou em <saída>"
  com a duração depois; sem saída no WSL, a mensagem manda abrir pelo Windows e
  é diferente da de saída ocupada.
- `CA-10` `windowsDist` contém o JavaFX para Windows, nenhum nativo de JavaFX
  para Linux e um lançador com `javaw.exe` e o JavaFX no module path.
- `CA-11` `PlaybackCheck` toca os seis sinais pelo `SoundPlayer` de produção e
  termina com código diferente de zero se algum não for comprovado.

## 16. Impacto em outros módulos

Nenhuma mudança em core/API/ZWP. Atualizar índice de SPECs e registrar exceção
visual da tela Voz no design system. Preservar trabalho preexistente no shell.

v2: `zordon-desktop/build.gradle.kts` (`windowsDist`, `verifyWindowsAudio`),
`packaging/windows/install-desktop.sh`,
[Primeiros passos](../../operations/quickstart.md) com o desktop no Windows.

## 17. Dependências

SPEC-005 (shell), SPEC-006 (voz), JavaFX Canvas e Java Sound do JDK existente.
Não adiciona dependências externas nem assets raster.
