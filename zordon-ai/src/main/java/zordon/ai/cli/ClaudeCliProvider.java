/*
 * Copyright 2026 Willyan Faria
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zordon.ai.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import zordon.ai.AiException;
import zordon.ai.AiMessage;
import zordon.ai.AiProvider;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.AiStreamListener;
import zordon.ai.ContentBlock;
import zordon.ai.Money;
import zordon.ai.ProviderInfo;
import zordon.ai.StopReason;
import zordon.api.trace.Spec;

/**
 * O {@code claude} CLI como provider, com a assinatura do usuário (ADR-0039,
 * SPEC-018). É um agente; aqui ele roda sem nenhuma ferramenta, sem memória de
 * sessão e com o system prompt do Zordon. O prompt vai pela entrada padrão.
 */
@Spec("SPEC-018")
public final class ClaudeCliProvider implements AiProvider {

    public static final Duration TIMEOUT = Duration.ofSeconds(120);
    /** Sem estas, o CLI teria ferramentas ou memória próprias: o provider não roda (SPEC-018 §10). */
    static final List<String> REQUIRED = List.of("-p", "--output-format", "json", "--disallowed-tools", "*",
            "--max-turns", "1", "--no-session-persistence");

    private final String id;
    private final CliRunner runner;

    public ClaudeCliProvider(String id, CliRunner runner) {
        this.id = Objects.requireNonNull(id, "id");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public ProviderInfo info() {
        return new ProviderInfo(id, Set.of(), List.of("sonnet", "opus", "haiku"), false);
    }

    /** O argv, sem o prompt: ele vai pela entrada padrão. */
    static List<String> argv(AiRequest request) {
        List<String> argv = new ArrayList<>();
        argv.add("claude");
        argv.addAll(REQUIRED);
        argv.add("--model");
        argv.add(request.model());
        argv.add("--system-prompt");
        String base = request.systemPrompt().isBlank() ? "Você é o Zordon." : request.systemPrompt();
        argv.add(request.tools().isEmpty() ? base : base + "\n\n" + CliTools.instructions(request.tools()));
        return List.copyOf(argv);
    }

    /** A conversa em texto, como vai pela entrada padrão. */
    static String transcript(List<AiMessage> messages) {
        return CliTranscript.of(messages);
    }

    @Override
    public AiResponse chat(AiRequest request) {
        List<String> argv = argv(request);
        if (!argv.containsAll(REQUIRED)) {
            throw new AiException(AiException.Kind.INVALID_REQUEST, "chamada ao claude sem as travas obrigatórias");
        }
        long started = System.nanoTime();
        CliRunner.Result result;
        try {
            result = runner.run(argv, transcript(request.messages()), TIMEOUT);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException(AiException.Kind.UNAVAILABLE, "o claude não pôde rodar: " + e.getMessage(), e);
        }
        if (result.timedOut()) {
            throw new AiException(AiException.Kind.TIMEOUT, "o claude não respondeu em " + TIMEOUT.toSeconds() + " s");
        }
        JsonNode out = CliOutput.parse(result);
        String text = out.path("result").asText("");
        CliOutput.refuseIfError(out, text);
        JsonNode usage = out.path("usage");
        List<ContentBlock.ToolUse> calls = request.tools().isEmpty() ? List.of() : CliTools.calls(text);
        String visible = CliTools.visible(text);
        List<ContentBlock> content = new ArrayList<>();
        if (!visible.isBlank()) {
            content.add(new ContentBlock.Text(visible));
        }
        content.addAll(calls);
        // Assinatura: não há cobrança por chamada. O CLI informa um custo nocional; não é dinheiro gasto.
        return new AiResponse(content, calls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE,
                CliOutput.usage(usage), Money.ZERO, request.model(), Duration.ofNanos(System.nanoTime() - started), null,
                usage.isMissingNode());
    }

    /** Sem streaming de tokens: o texto chega inteiro e sai como um fragmento só (SPEC-018 §3). */
    @Override
    public AiStream stream(AiRequest request, AiStreamListener listener) {
        return CliStream.start(() -> chat(request), listener);
    }

    /** Estimativa: o CLI não conta tokens antes de gerar. */
    @Override
    public long countTokens(AiRequest request) {
        return (request.systemPrompt().length() + CliTranscript.of(request.messages()).length()) / 4;
    }
}
