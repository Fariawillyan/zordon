---
document: adr-0009
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,captura,windows,inferencia,wsl]
specId: null
---

# ADR-0009 — Captura de áudio no Windows, inferência no WSL

**Status:** Aceito · 2026-09-17

## Contexto

O pipeline de voz tem cinco estágios: captura, VAD, wake word, STT e TTS. O
núcleo está no WSL, que **não tem acesso confiável a dispositivo de áudio**
([R6](../architecture/windows-wsl.md#r6--não-há-áudio-confiável-dentro-do-wsl)) —
o WSLg oferece PulseAudio, mas com latência variável, sem controle de dispositivo
e quebrando sem sessão gráfica.

Logo, a captura acontece no Windows. A pergunta é **onde ficam os outros quatro
estágios**, e ela é interessante porque envolve um trade-off de banda contra
complexidade.

## Alternativas

**A. Tudo no Windows.** Captura, VAD e wake word no `zordon-host` com ONNX
Runtime para Java; só o áudio do comando (pós-wake-word) vai para o WSL. Menos
tráfego, e o wake word continua funcionando se o link cair. Mas: duplica a stack
de ML em duas linguagens e dois lugares; atualizar um modelo exige mexer nos dois
lados; sem CUDA fácil no Windows dentro do host Java; e o argumento do "link
caído" é vazio, porque se o núcleo está fora, detectar a wake word não serve para
nada.

**B. Captura e VAD no Windows, wake word e STT no WSL.** Meio-termo que reduz
tráfego em ~90% (só manda quando há fala). Mas mantém a duplicação de stack para
ganhar banda que não é escassa.

**C. Captura no Windows, todo o resto no WSL.** Áudio bruto contínuo vai para o
núcleo; VAD, wake word, STT e TTS vivem no sidecar Python.

## Decisão

**Alternativa C**, com uma porta de ruído simples (RMS) no host como otimização
opcional.

O número que decide: PCM 16 kHz mono 16-bit = **32 KB/s**. Em loopback local,
com sub-milissegundo de latência, isso é irrelevante — é menos do que a UI gasta
mandando métricas. A complexidade que a alternativa A adiciona (duas stacks de
ML, dois lugares para atualizar modelo, dois conjuntos de bugs de inferência)
custa muito mais do que 32 KB/s.

Benefícios concretos de concentrar no WSL:

- **Um lugar para a stack de voz.** Trocar faster-whisper por outra coisa é
  mexer em um arquivo Python.
- **CUDA funciona.** GPU no WSL2 usa o driver do Windows via `/usr/lib/wsl/lib`,
  sem instalação na distro. STT em GPU é 3–4× mais rápido.
- **Um lugar para carregar e descarregar modelo**, com a política de memória de
  [R10](../architecture/windows-wsl.md#r10--vmmem-cresce-e-não-devolve).
- **`zordon-host` fica pequeno.** Quanto menor o processo com poder no Windows,
  menor a superfície a auditar.

## Consequências

**Positivas.** Uma stack de voz, num lugar, na linguagem certa. GPU disponível.
Host mínimo. Atualização de modelo sem tocar em Java.

**Negativas.** Fluxo contínuo de áudio entre processos enquanto a escuta está
ativa. Se o link WSL cair, não há wake word (aceitável: sem núcleo, não há
resposta). O ciclo de vida do áudio depende do ZWP estar saudável.

**Privacidade — o ponto mais importante.** Áudio bruto trafega entre dois
processos **na mesma máquina** e nunca sai dela. O requisito do briefing ("não
enviar áudio continuamente para uma API externa") é atendido integralmente: o
wake word é local, o VAD é local, o STT é local por padrão. STT em nuvem existe
como opção explícita, com aviso.

**Controles obrigatórios que derivam disso:**

- `voice.setMode off` desliga a captura **no host**, não filtra no núcleo. A
  diferença entre "não estamos ouvindo" e "estamos ouvindo e descartando" é toda
  a diferença, e o LED de microfone do Windows precisa refletir isso.
- Buffer de pré-roll fica em RAM, sobrescrito continuamente, nunca em disco.
- Nenhum áudio é persistido, nem para depuração, sem ação explícita do usuário.

**Controle de fluxo é nosso.** Sem HTTP/2, o crédito de frames é implementado no
ZWP ([ZWP §7](../api/zwp-protocol.md#controle-de-fluxo)). A política é
**descartar o mais antigo**: áudio velho não tem valor, latência tem.
