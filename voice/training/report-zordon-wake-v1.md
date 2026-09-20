# Relatório do treino — zordon-wake-v1

Gerado por `voice/training/train_wake.py` em 2026-09-19 (SPEC-013, ADR-0038).

## Dados

- Treino: 121677 janelas positivas; MLS 28.5 h; negativos sintéticos 5.8 h.
- Teste: MLS 10.0 h de locutores fora do treino; negativos sintéticos da voz cadu 0.97 h; positivos da voz cadu e de 10% dos locutores LibriTTS.

## Resultado no limiar escolhido

- Limiar de operação: **0.99**. Limiar estrito (o menor sem disparo na validação): 0.9999; validação no limiar de operação: 71.6% de 4479 clipes.
- Acerto no teste: **71.2%** de 4998 clipes (detecção até 600 ms depois da palavra).
- Falsos disparos no MLS de teste: **7** em 10.0 h (5.59 a cada 8 h).
- Falsos disparos nos negativos sintéticos de teste (palavras parecidas, narração): **7**.

## Curva

| Limiar | Acerto | Por voz | Falsos (MLS) | Falsos (sintéticos) |
|---|---|---|---|---|
| 0.5 | 91.5% | libritts_r-medium 92%, libritts_r-medium-pt 92%, cadu-medium 91% | 180 | 90 |
| 0.7 | 89.1% | libritts_r-medium 88%, libritts_r-medium-pt 89%, cadu-medium 89% | 103 | 66 |
| 0.9 | 84.1% | libritts_r-medium 81%, libritts_r-medium-pt 85%, cadu-medium 84% | 43 | 29 |
| 0.95 | 80.6% | libritts_r-medium 78%, libritts_r-medium-pt 81%, cadu-medium 81% | 26 | 19 |
| 0.98 | 75.2% | libritts_r-medium 71%, libritts_r-medium-pt 76%, cadu-medium 76% | 16 | 13 |
| 0.99 | 71.2% | libritts_r-medium 67%, libritts_r-medium-pt 72%, cadu-medium 71% | 7 | 7 |
| 0.9999 | 42.3% | libritts_r-medium 40%, libritts_r-medium-pt 45%, cadu-medium 39% | 0 | 0 |

O teste de campo (8 h de fala ambiente com o modo `wake`) é do owner (SPEC-013 CA-14).
