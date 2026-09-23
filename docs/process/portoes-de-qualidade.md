---
document: portoes-de-qualidade
module: process
section: standards
version: 1
updatedAt: 2026-09-23
securityLevel: public
tags: [checkstyle,cobertura,complexidade,definition-of-done,pendencias]
specId: null
---

# Portões de qualidade

Estado do trabalho que liga os limites do
[§5 dos padrões de código](code-standards.md#5-complexidade) ao build.

## 1. Por que isto existe

A [Definition of Done](definition-of-done.md) afirma que cada portão tem "um sinal
objetivo, não uma opinião". Dois deles não tinham:

- **"Clean Code validado"** — o `§5` define oito limites e marca metade como
  bloqueantes, mas nenhuma ferramenta os media. Três classes já haviam passado do
  teto de 700 linhas sem ninguém notar.
- **"Cobertura mínima do módulo atingida"** — o Jacoco gerava relatório e não
  reprovava nada.

Uma auditoria de 2026-09-23 levantou os dois. O owner decidiu aplicar os limites
reais, sem supressão, e seguir o processo completo do repositório.

## 2. O que já vale

O Checkstyle roda no `check` de cada módulo e **reprova o build**:

- [`config/checkstyle/checkstyle.xml`](../../config/checkstyle/checkstyle.xml) —
  os tetos que o `§5` marca como bloqueantes;
- [`config/checkstyle/checkstyle-strict.xml`](../../config/checkstyle/checkstyle-strict.xml) —
  os mesmos 30% mais apertados, para `zordon-security` e `zordon-defense`.

Só o teto rígido entra. O limite menor da tabela dispara revisão humana e continua
sendo julgamento, porque o próprio `§5` diz que ultrapassá-lo "exige justificativa
escrita, não é proibição automática".

Das 147 violações que o portão encontrou na primeira medição, **113 continuam
abertas** e o build da branch `chore/portoes-de-qualidade` está vermelho por causa
delas. As 34 fechadas saíram com os testes verdes em cada passo.

## 3. O que falta

Em ordem de dificuldade:

| Pendência | Tamanho | Observação |
|---|---|---|
| Portão de cobertura (`violationRules` do Jacoco) | — | **Não começou.** É metade do motivo deste trabalho |
| SPEC e ADR desta mudança | — | O owner escolheu processo completo |
| `ParameterNumber` | 20 | Mecânico: construtores de montagem pedem objeto de parâmetro |
| `MethodCount`, `FileLength`, `CyclomaticComplexity` | 7 | `VoiceService` e `DesktopState` pedem desenho, não extração |
| `RecordComponentNumber` | 35 | **Não está no `§5`**: foi acrescentada por analogia. Vale reabrir |
| `ClassFanOutComplexity` | 51 | Pior caso é o `ZordonCore` com 101 — e ele é o composition root |

## 4. Duas armadilhas já encontradas

**O total de violações não mede progresso.** Dividir uma classe grande aumenta o
fan-out, porque passam a existir mais classes se referenciando; dividir um método
aumenta o tamanho do arquivo, por causa das assinaturas e do javadoc. As duas
coisas aconteceram, e o número subiu enquanto o código melhorava.

**Dividir construtor custa imutabilidade.** Um método auxiliar não pode atribuir
campo `final` em Java. Partir o construtor de 195 linhas do `ZordonCore` exigiu que
32 campos deixassem de ser `final`. Eles são escritos uma vez, durante a construção,
e o motivo está registrado no próprio código — mas a garantia do compilador se
perdeu.

## 5. Estado

**Pausado em 2026-09-23**, a pedido do owner, sem previsão de retomada. O portão do
Checkstyle fica de pé: código novo já nasce dentro do limite.
