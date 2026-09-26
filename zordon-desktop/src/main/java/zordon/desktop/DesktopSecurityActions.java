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
package zordon.desktop;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.SecurityPresentation;
import zordon.desktop.ui.CriticalNotice;
import zordon.desktop.ui.OppressorPane;
import zordon.desktop.ui.PermissionPane;
import zordon.desktop.ui.ShellSecurityActions;

/**
 * A segurança pedida pela tela — avisos, só leitura, OPPRESSOR MODE, quarentena,
 * achados e disjuntores — e os diálogos que o núcleo abre na janela: a
 * permissão, o aviso crítico e a senha mestre.
 */
final class DesktopSecurityActions implements ShellSecurityActions {

    private static final Logger log = LoggerFactory.getLogger(ZordonDesktop.class);

    private final DesktopContext context;
    private final DesktopState state;

    DesktopSecurityActions(DesktopContext context) {
        this.context = context;
        this.state = context.state();
    }

    @Override
    public void acknowledge(String messageId) {
        context.request("notify.acknowledge", Map.of("messageId", messageId))
                .exceptionally(failure -> {
                    log.warn("confirmação do aviso {} não chegou ao núcleo: {}", messageId, DesktopContext.rootMessage(failure));
                    return null;
                });
    }

    @Override
    public void pauseZordon() {
        context.request("security.lockdown", Map.of("reason", "pausado pelo usuário na janela"))
                .thenAccept(result -> Platform.runLater(() -> state.security().lockdown(true, String.valueOf(
                        result.getOrDefault("reason", "")))))
                .exceptionally(context::reportFailure);
    }

    @Override
    public void resumeZordon() {
        context.request("security.resume", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.security().lockdown(false, "")))
                .exceptionally(context::reportFailure);
    }

    @Override
    public void enterOppressor(char[] password) {
        // O núcleo confere e publica OPPRESSOR_ENTERED; o estado vem por ali.
        // A senha vira String só para caber no JSON e some do vetor aqui.
        Map<String, Object> params = Map.of("password", secret(password));
        context.request("security.oppressor.enter", params)
                .thenAccept(ignored -> {})
                .exceptionally(this::oppressorFailure);
    }

    @Override
    public void exitOppressor() {
        context.request("security.oppressor.exit", Map.of())
                .thenAccept(ignored -> {})
                .exceptionally(this::oppressorFailure);
    }

    @Override
    public void setOppressorPassword(char[] current, char[] next) {
        Map<String, Object> params = Map.of("current", secret(current), "next", secret(next));
        context.request("security.oppressor.password", params)
                .thenAccept(result -> Platform.runLater(() -> state.security().oppressorStatus(
                        state.security().oppressorProperty().get(), true)))
                .exceptionally(this::oppressorFailure);
    }

    /**
     * Falha do OPPRESSOR MODE: aparece na própria seção, não na conversa.
     *
     * <p>Mandar para o chat escondia o erro de quem estava na tela de
     * Segurança: a ação não acontecia e nada dizia por quê. Senha errada, senha
     * ausente e método que o núcleo não conhece chegam todos por aqui.
     */
    private Void oppressorFailure(Throwable failure) {
        Platform.runLater(() -> state.security().oppressorFailed(DesktopContext.rootMessage(failure)));
        return null;
    }

    /**
     * Abre o pedido da senha mestre, vindo da voz (SPEC-036 CA-9).
     *
     * <p>Um vetor vazio é desistência — e também é o que chega se a janela for
     * fechada na cruz. Não vale a pena pedir ao núcleo nesse caso.
     */
    void promptOppressor() {
        if (state.security().oppressorProperty().get()) {
            return;
        }
        context.showWindowNow();
        OppressorPane.show(context.stage(), DesktopContext.stylesheet()).thenAccept(password -> {
            if (password.length > 0) {
                enterOppressor(password);
            }
        });
    }

    /** Uma senha do controle vira String para o JSON e é apagada do vetor. */
    private static String secret(char[] value) {
        if (value == null) {
            return "";
        }
        String text = new String(value);
        Arrays.fill(value, '\0');
        return text;
    }

    @Override
    public void loadQuarantine() {
        context.request("security.quarantine.list", Map.of())
                .thenAccept(result -> Platform.runLater(() -> {
                    state.security().quarantine().clear();
                    if (result.get("items") instanceof List<?> items) {
                        items.stream().filter(Map.class::isInstance).forEach(item -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) item;
                            state.security().quarantine().add(typed);
                        });
                    }
                }))
                .exceptionally(failure -> null);
    }

    @Override
    public void restoreQuarantine(String vaultId) {
        state.security().quarantineNoticeProperty().set("Restaurando…");
        context.request("security.quarantine.restore", Map.of("vaultId", vaultId))
                .thenAccept(result -> Platform.runLater(() -> {
                    state.security().quarantineNoticeProperty().set(String.valueOf(result.getOrDefault("text", "")));
                    loadQuarantine();
                }))
                .exceptionally(failure -> {
                    Platform.runLater(() -> state.security().quarantineNoticeProperty().set(DesktopContext.rootMessage(failure)));
                    return null;
                });
    }

    @Override
    public void loadFindings() {
        context.request("security.findings", Map.of("limit", 20))
                .thenAccept(result -> Platform.runLater(() -> {
                    state.security().findings().clear();
                    if (result.get("findings") instanceof List<?> findings) {
                        findings.stream().filter(Map.class::isInstance).forEach(finding -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) finding;
                            if (typed.get("acknowledgedAt") == null) {
                                state.security().findings().add(typed);
                            }
                        });
                    }
                }))
                .exceptionally(failure -> null);
        context.request("security.breakers", Map.of())
                .thenAccept(result -> Platform.runLater(() -> {
                    state.security().breakers().clear();
                    if (result.get("breakers") instanceof List<?> breakers) {
                        breakers.stream().filter(Map.class::isInstance).forEach(breaker -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) breaker;
                            state.security().breakers().add(typed);
                        });
                    }
                }))
                .exceptionally(failure -> null);
    }

    @Override
    public void releaseBreaker(String subject, String mode) {
        context.request("security.breakerRelease", Map.of("subject", subject, "mode", mode))
                .thenAccept(result -> loadFindings())
                .exceptionally(context::reportFailure);
    }

    @Override
    public void acknowledgeFinding(String findingId) {
        context.request("security.findingAcknowledge", Map.of("findingId", findingId))
                .thenAccept(result -> loadFindings())
                .exceptionally(context::reportFailure);
    }

    @Override
    public void loadSecurityEvents() {
        context.fill("security.events", "events", state.security().securityEvents());
    }

    /**
     * {@code ui.requestPermission} (SPEC-015 CA-1, CA-2): a janela vem para a
     * frente e o diálogo espera a decisão. Qualquer falha aqui é negar.
     */
    Map<String, Object> requestPermission(Map<String, Object> params) {
        SecurityPresentation.Prompt prompt = SecurityPresentation.prompt(params);
        CompletableFuture<String> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                context.showWindowNow();
                PermissionPane.show(context.stage(), prompt, DesktopContext.stylesheet())
                        .whenComplete((approval, failure) -> answer.complete(failure == null ? approval : "deny"));
            } catch (RuntimeException e) {
                log.warn("diálogo de permissão falhou: {}", e.toString());
                answer.complete("deny");
            }
        });
        try {
            return Map.of("approval", answer.get(prompt.seconds() + 5L, TimeUnit.SECONDS));
        } catch (Exception e) {
            return Map.of("approval", "deny");
        }
    }

    /** Um aviso chegou: CRITICAL abre a janela e pede confirmação de leitura (SPEC-015 CA-7). */
    void notified(Map<String, Object> message) {
        state.security().notification(message);
        if (SecurityPresentation.critical(String.valueOf(message.get("severity")))) {
            context.showWindowNow();
            CriticalNotice.show(context.stage(), message, DesktopContext.stylesheet(), id -> {
                state.security().acknowledged(id);
                acknowledge(id);
            });
        }
    }

    /** O kill switch e o OPPRESSOR MODE como o núcleo os vê agora ({@code security.status}). */
    void loadStatus() {
        context.request("security.status", Map.of()).thenAccept(result -> Platform.runLater(() -> {
            if (result.get("lockdown") instanceof Map<?, ?> lockdown) {
                state.security().lockdown(Boolean.TRUE.equals(lockdown.get("active")),
                        String.valueOf(lockdown.get("reason") == null ? "" : lockdown.get("reason")));
            }
            if (result.get("oppressor") instanceof Map<?, ?> oppressor) {
                state.security().oppressorStatus(Boolean.TRUE.equals(oppressor.get("active")),
                        Boolean.TRUE.equals(oppressor.get("configured")));
            }
        })).exceptionally(failure -> null);
    }
}
