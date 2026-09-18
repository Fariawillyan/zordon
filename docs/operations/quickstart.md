---
document: ops-quickstart
module: operations
section: quickstart
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [instalacao,primeiros-passos,wsl,chave,guia]
specId: null
---

# Primeiros passos

Do zero ao Zordon respondendo, em uns 15 minutos. Este guia é o caminho curto; o
[runbook de instalação](install.md) explica cada decisão por trás dele.

## O que você terá no fim

```text
 Windows                                 WSL (Ubuntu)
 ┌──────────────────────┐                ┌──────────────────────────────┐
 │ janela do Zordon     │ ◄── ZWP ────►  │ zordon-core  (serviço)       │
 │ (via WSLg)           │   localhost    │   sobe sozinho, fica no ar   │
 └──────────────────────┘                └──────────────────────────────┘
          ▲
          └── tarefa agendada: liga o WSL quando você entra no Windows
```

O núcleo roda como serviço no WSL: fechar a janela não desliga nada. A janela é
só uma forma de conversar com ele.

## 1. Pré-requisitos

Confira cada item. Todos os comandos são no terminal do **Ubuntu (WSL)**, salvo
quando dito o contrário.

| Precisa de | Como conferir | Se faltar |
|---|---|---|
| Windows 11 com WSL 2 | `wsl.exe --version` (no PowerShell) | `wsl --install` no PowerShell como administrador |
| Ubuntu com **systemd** | `ps -p 1 -o comm=` responde `systemd` | Veja [Se o systemd não estiver ativo](#se-o-systemd-não-estiver-ativo) |
| **JDK 25** | `java -version` mostra `25` | Veja [Instalar o JDK 25](#instalar-o-jdk-25) |
| `git` e `curl` | `git --version && curl --version` | `sudo apt install -y git curl` |
| **Um modelo para conversar** | — | Veja [4. Escolher o modelo](#4-escolher-o-modelo): API paga ou modelo local gratuito |

### Instalar o JDK 25

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25.0.2-tem
java -version        # deve mostrar 25
```

### Se o systemd não estiver ativo

```bash
printf '[boot]\nsystemd=true\n' | sudo tee -a /etc/wsl.conf
```

Depois, **no PowerShell**: `wsl --shutdown`. Abra o Ubuntu de novo e confira com
`ps -p 1 -o comm=`.

## 2. Baixar

```bash
git clone https://github.com/Fariawillyan/zordon.git ~/zordon
cd ~/zordon
```

## 3. Instalar o núcleo

```bash
packaging/wsl/install.sh
```

O instalador vai:

1. conferir o systemd e o Java 25;
2. compilar o núcleo (a primeira vez demora alguns minutos: ele baixa o Gradle e
   as dependências);
3. descobrir o seu perfil do Windows **perguntando ao Windows** — o seu usuário
   do WSL e o do Windows podem ter nomes diferentes;
4. perguntar **de qual provider é a sua chave** (`anthropic`, `openai`) e pedi-la.
   Ela não aparece na tela enquanto você cola, e é validada antes de ser
   gravada. Vai usar um modelo local? Aperte Enter e pule;
5. deixar um `~/.zordon/config.toml` de exemplo, todo comentado, se você ainda
   não tiver um;
6. pedir a sua **senha do sudo**, para instalar o serviço;
7. esperar o núcleo ficar pronto e mostrar os endereços dele.

Deu certo quando a última linha antes das instruções finais for:

```text
==> núcleo pronto: ws://127.0.0.1:8777/zwp/v1 ws://172.x.x.x:8777/zwp/v1
```

## 4. Escolher o modelo

O Zordon não pertence a nenhum fornecedor: você escolhe quem responde em
`~/.zordon/config.toml`. O instalador deixou lá um exemplo comentado com cada
opção. Descomente o bloco do que você tem e reinicie o núcleo com
`sudo systemctl restart zordon`.

| Você tem | O que fazer |
|---|---|
| API da **Anthropic** com crédito | Nada: é o padrão. Só a chave: `packaging/wsl/set-api-key.sh anthropic` |
| API da **OpenAI** com crédito | Descomente o bloco `openai` e o papel `conversation` apontando para ele; `packaging/wsl/set-api-key.sh openai` |
| **Nada pago** | Modelo local com Ollama — veja abaixo. Gratuito, e a conversa não sai da sua máquina |
| OpenRouter, Groq, DeepSeek, LM Studio… | Mesmo molde do bloco `openrouter` do exemplo; `set-api-key.sh --env NOME_DA_VARIAVEL` |
| Assinatura do **Claude** ou do **ChatGPT** | Ainda não: é a próxima fase. A assinatura não inclui a API — são produtos separados |

**Modelo local, de graça:**

```bash
sudo systemctl start ollama
ollama pull qwen2.5:7b          # ~4,7 GB; em máquina mais fraca, qwen2.5:3b (~2 GB)
```

E no `config.toml`, descomente o bloco `ollama` e os papéis, apontando a conversa
para ele — ou deixe o Ollama como **reserva** (`fallback`) da API paga: se a
conta ficar sem crédito, ele responde no lugar, e a tela avisa que foi ele.

Duas regras do arquivo, que evitam dor de cabeça:

- **A chave nunca vai no `config.toml`.** Ele guarda o nome da variável
  (`api_key = "env:OPENAI_API_KEY"`); o valor vai para `~/.zordon/secrets.env`
  pelo `set-api-key.sh`. Uma chave colada no arquivo é recusada — de propósito,
  para você poder colar o arquivo num pedido de ajuda sem vazar nada.
- **O nome do modelo é o do seu provider.** O Zordon não adivinha; confira na
  documentação ou no painel de quem fornece.

## 5. Abrir a janela

```bash
./gradlew :zordon-desktop:run
```

O Windows 11 mostra a janela direto na sua área de trabalho (é o WSLg). Na barra
de baixo deve aparecer **CORE ONLINE** em verde.

Teste as duas conversas:

| Digite | O que acontece | Custo |
|---|---|---|
| `que horas são?` | Resposta imediata, calculada na sua máquina | Nenhum |
| `em uma frase, o que é o WSL2?` | A resposta aparece enquanto é escrita, com tokens e custo no rodapé | Frações de centavo |

A aba **Logs** mostra cada passo do que você acabou de fazer.

## 6. Subir sozinho quando o Windows iniciar

Uma vez só. **No PowerShell do Windows** (não precisa ser administrador):

```powershell
powershell -ExecutionPolicy Bypass -File "\\wsl.localhost\Ubuntu\home\<seu-usuario-wsl>\zordon\packaging\windows\register-tasks.ps1"
```

O instalador imprime esse comando já com o caminho certo para a sua máquina.

**Obrigatório também:** sem isto o WSL desliga a distro **15 segundos** depois
do boot, e o núcleo cai junto — medido, não teórico. Abra
`%USERPROFILE%\.wslconfig` no Bloco de Notas e garanta estas linhas (mantenha o
que já houver no arquivo):

```ini
[wsl2]
vmIdleTimeout=-1

[general]
instanceIdleTimeout=-1
```

Depois, no PowerShell: `wsl --shutdown`. Isso fecha todos os terminais do WSL.

**O teste que prova que funcionou:** reinicie o Windows, não abra nenhum
terminal, abra o Ubuntu só para rodar a janela
(`cd ~/zordon && ./gradlew :zordon-desktop:run`) e veja CORE ONLINE sem ter
iniciado o núcleo à mão.

## Depois de instalado

| Quero… | Comando |
|---|---|
| Trocar o modelo ou o provider | Editar `~/.zordon/config.toml` e `sudo systemctl restart zordon` |
| Trocar a chave de um provider | `packaging/wsl/set-api-key.sh anthropic` (ou `openai`, ou `--env NOME`) |
| Conferir se as chaves valem | `packaging/wsl/set-api-key.sh --check` |
| Ver quais providers estão configurados | `journalctl -u zordon -g provider -n 10` |
| Ver se o núcleo está no ar | `systemctl status zordon` |
| Ver o que o núcleo está fazendo | `journalctl -u zordon -f` |
| Reiniciar o núcleo | `sudo systemctl restart zordon` |
| Atualizar para uma versão nova | `git pull && packaging/wsl/install.sh` |
| Parar e não subir mais sozinho | `sudo systemctl disable --now zordon` |

Reinstalar é seguro: o instalador sobrescreve o que é dele, preserva a sua chave e
nunca apaga arquivos.

## Problemas comuns

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| Janela mostra **NÚCLEO OFFLINE** | Serviço parado ou com erro | `systemctl status zordon`; o motivo está em `journalctl -u zordon -n 50` |
| "A API recusou a chave" | Chave errada, incompleta ou revogada | `packaging/wsl/set-api-key.sh <provider>` com uma chave nova |
| "A conta da API está sem crédito" | Chave certa, conta sem saldo | Comprar crédito no console do provider — ou configurar o Ollama como reserva |
| "provider 'x' indisponível: defina NOME" | A chave daquele provider não foi gravada | `packaging/wsl/set-api-key.sh --env NOME` (ou `anthropic`/`openai`) |
| "Nada respondendo em http://127.0.0.1:11434" | O Ollama está parado | `sudo systemctl start ollama` |
| "model '…' not found" | O modelo não foi baixado no Ollama | `ollama pull <modelo>` |
| "config.toml não guarda segredo" | Uma chave foi colada no `config.toml` | Troque por `api_key = "env:NOME"` e grave a chave com `set-api-key.sh` |
| "aponta para o provider 'x', que não existe" | Nome do provider digitado diferente nos dois lugares | O `provider` do papel precisa ser igual ao nome em `[ai.providers.<nome>]` |
| `UnsupportedClassVersionError` no log | O serviço está usando um Java antigo | Rode o `install.sh` de novo: ele fixa o Java 25 na unit |
| `Start request repeated too quickly` | Falhas seguidas acionaram o limitador do systemd | Corrija a causa e rode o `install.sh` de novo; ele limpa o limitador |
| A janela não abre | WSLg indisponível | `echo $DISPLAY` deve mostrar `:0`; atualize o WSL com `wsl --update` no PowerShell |
| Sem ícone na bandeja | Normal no WSLg, que não oferece bandeja | Nada a fazer; o ícone existe quando o app roda no Windows nativo |
| Núcleo cai segundos depois do boot | O WSL desligou a distro por ociosidade | `instanceIdleTimeout=-1` e `vmIdleTimeout=-1` no `.wslconfig` (passo 6) |

## O que ainda não existe

Para não procurar o que não está lá: este é o marco **M1** do
[roadmap](../roadmap.md). Já há conversa por texto, histórico, logs e reconexão
automática, com o modelo que você escolher. **Ainda não há** assinatura do Claude
ou do ChatGPT como provider (próxima fase), voz (M2), ações no computador como abrir programas
(M3), ferramentas MCP (M4), agentes e memória de longo prazo (M5), nem instalador
`.msi` para Windows — por isso a janela roda a partir do WSL.

Se algo neste guia não bater com o que você viu, é defeito do guia: abra uma
issue dizendo em qual passo.
