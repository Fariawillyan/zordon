---
document: spec-034
module: voice
section: spec
version: 1
updatedAt: 2026-09-20
securityLevel: public
tags: [spec,voz,tts,markdown,palavra-de-ativacao,limiar]
specId: SPEC-034
---

# SPEC-034 — Fala limpa e limiar ajustável

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `VoiceAgent` |
| **Revisores** | ArchitectureAgent |
| **Marco** | M8 |

Relatado pelo owner em 2026-09-20: "porque o zordon eh lerdo? preciso falar o
nome dele varias vezes para ele entender... e quando ele responde, ele lê todas
as palavras até os asteriscos... ele não resume de forma inteligente?"

## 1. Objetivo

Duas queixas, duas causas distintas: a voz lia a resposta crua, com marcação, e o
detector da palavra de ativação não tinha como ser afrouxado por quem usa.

## 2. Problema

**A fala lia tudo.** O `ActivityInterpreter` mandava ao motor de voz o texto
integral da resposta do modelo:

```java
String answer = text(payload.get("text"));
out.add(new Say(new Narration(answer, Narration.Priority.HIGH, "resultado")));
```

Markdown incluso — `**`, `##`, crases, itens de lista, tabelas e blocos de
código —, sem teto de tamanho. Três linhas abaixo, no mesmo arquivo, o alerta de
segurança já fazia o certo, com o comentário "a voz diz o resumo e aponta para a
tela; nunca lê a mensagem inteira". A regra existia
([Comunicação §3](../../security/communication.md)) e o caminho mais usado a
violava.

**O limiar era imutável.** Ele vinha gravado no `.npz` (0,99) e nada o
sobrescrevia. Quem achasse o detector surdo demais não tinha manopla: só
retreinar o modelo.

## 3. Escopo

**`SpokenAnswer`** transforma resposta em fala:

- tira a marcação — ênfase, títulos, listas, citações, tabelas, linhas
  horizontais, código em linha e links (fica o texto, não a URL);
- **remove blocos de código** sem acrescentar uma frase artificial sobre a tela;
- fala a resposta natural completa, sem truncar por quantidade de caracteres.

**Limiar ajustável** por `ZORDON_WAKE_THRESHOLD`, lido pelo motor de voz:

- sem a variável, vale o gravado no modelo;
- fora de `[0,50 ; 0,9999]` é recusado com aviso no log, e o do modelo prevalece;
- a troca fica registrada no log, para o comportamento nunca mudar em silêncio.

**A curva medida** (validação: 4 998 clipes positivos, 4,93 h de áudio negativo,
modelo publicado `zordon-wake-v1`):

| Limiar | Acerto | Disparos falsos (4,93 h) |
|---|---|---|
| 0,999 | 56,9% | 2 |
| **0,99 (padrão)** | **71,6%** | **15** |
| 0,98 | 75,5% | 20 |
| 0,95 | 80,0% | 36 |
| 0,90 | 83,5% | 60 |
| 0,80 | 86,5% | 89 |
| 0,50 | 91,2% | 200 |

Estes negativos de validação incluem sintéticos escolhidos para confundir; no
conjunto de teste (10 h de fala real) o mesmo limiar 0,99 deu 7 falsos, não 15.
A **forma** da curva é o que vale: cada ponto de acerto custa cada vez mais
disparo falso, e depois de 0,90 o custo dispara.

## 4. Não escopo

- Retreinar o modelo. O teto de acerto é do classificador, não do limiar; 91% a
  0,50 vem junto de 200 falsos, o que é inutilizável.
- Resumir com o modelo (pedir ao LLM uma versão falada): custa um turno a mais e
  latência; o corte por frase resolve a queixa sem isso.
- Expor o limiar na tela. Por variável de ambiente basta para quem quer ajustar.

## 5. Arquitetura

```text
AI_RESPONSE ─► ActivityInterpreter ─► SpokenAnswer.of ─► Narration ─► SpeechPlayer
                                       (tira marcação,
                                        preserva o texto completo)

__main__.py (ZORDON_WAKE_THRESHOLD) ─► VoiceServer.wake_threshold ─► Stream
                                        (valida faixa, loga a troca)
```

## 6. Fluxo

O usuário pergunta por voz e o modelo responde com uma lista em Markdown e um
bloco de código.
1. `SpokenAnswer` tira `**`, `##` e os marcadores de lista.
2. O bloco de código sai do texto falado.
3. A voz diz todo o texto natural restante, sem "O resto está na tela".

## 7. Interfaces

```java
public final class SpokenAnswer {
    public static String of(String answer);   // "" quando não há nada a dizer
}
```

```python
class VoiceServer:
    MIN_THRESHOLD = 0.50
    MAX_THRESHOLD = 0.9999
    def __init__(self, models, socket_path, wake_threshold=None): ...
    @property
    def wake_threshold(self): ...
```

## 8. Eventos

Nenhum novo. O `VOICE_NARRATION` passa a carregar o texto natural completo, já limpo.

## 9. Dados

Nenhum. O texto completo continua na tela e no histórico da conversa.

## 10. Segurança

- A voz de conversa não corta a resposta: o texto natural integral também fica
  na Conversa e no trace. Alertas de segurança continuam sendo resumidos.
- Baixar o limiar aumenta disparo falso, não poder: uma ativação falsa abre a
  escuta, e tudo o que vier depois continua passando pelo motor de permissão.
- O limiar em vigor é registrado no log na inicialização.

## 11. Permissões

Nenhuma nova.

## 12. Observabilidade

O log do motor de voz diz o limiar em vigor quando ele difere do modelo, e avisa
quando um valor fora da faixa foi recusado.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Resposta só com código | Nada é falado; comandos não são ditados como conversa |
| Resposta vazia ou só marcação | Nada é falado |
| `ZORDON_WAKE_THRESHOLD` não numérico | O motor não sobe; o erro aponta a variável |
| Limiar fora da faixa | Aviso no log e o do modelo prevalece |

## 14. Testes

- A marcação não é falada, e o link vira o texto, não a URL.
- Resposta longa é falada inteira depois da limpeza de Markdown.
- Bloco de código não é ditado.
- O limiar do usuário entra em vigor; valor absurdo é recusado.

### Evidências (2026-09-20)

- `SpokenAnswerTest`: marcação removida (CA-1), resposta longa falada inteira
  (CA-2), código não ditado sem mensagem artificial (CA-3), resposta curta falada inteira,
  entrada vazia, e tabela não lida célula a célula.
- `test_server.py` (3 testes novos): sem ajuste vale o do modelo, ajuste válido
  entra em vigor, e `0.0`, `1.0` ou negativo são recusados.
- A curva da §3 foi medida com o modelo publicado sobre as features de validação
  já em disco, sem retreinar.

## 15. Critérios de aceite

- `CA-1` Dada uma resposta com Markdown, então nada da marcação é falado, e o
  texto de um link é dito sem a URL.
- `CA-2` Dada uma resposta longa, então a voz diz todo o texto natural, sem
  truncar nem apontar para a tela.
- `CA-3` Dada uma resposta com bloco de código, então o código não é ditado e
  nenhuma mensagem artificial sobre a tela é acrescentada.
- `CA-4` Dado `ZORDON_WAKE_THRESHOLD` válido, então ele entra em vigor e o log
  diz isso; dado um valor fora da faixa, então o do modelo prevalece com aviso.

## 16. Impacto em outros módulos

- `zordon-core`: `SpokenAnswer` novo, `ActivityInterpreter` passando por ele.
- `voice`: `VoiceServer` com limiar ajustável, `__main__` lendo a variável.
- `packaging`: a unit do motor documenta a variável, comentada.

## 17. Dependências

- [Comunicação §3](../../security/communication.md) ·
  [SPEC-013](SPEC-013-palavra-de-ativacao-e-conversa-sem-clique.md) ·
  [ADR-0029](../../adr/ADR-0029-voice-first.md)
