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
package zordon.desktop.ui;

import java.util.List;
import java.util.Map;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import zordon.desktop.shell.DesktopState;

/**
 * Os cartões da Segurança: o kill switch com os achados e os disjuntores, os
 * avisos e a quarentena. Vieram da aba "Segurança" dos Ajustes.
 */
final class SecurityCards {

    private SecurityCards() {
    }

    /** O kill switch (SPEC-015 CA-6): pausar é um clique; retomar também, mas só daqui ou da bandeja. */
    static Node security(DesktopState state, ShellActions actions) {
        Label status = new Label();
        status.setWrapText(true);
        Button toggle = new Button();
        toggle.setId("lockdown-toggle");
        Runnable render = () -> {
            boolean paused = state.security().lockdownProperty().get();
            String reason = state.security().lockdownReasonProperty().get();
            status.setText(paused
                    ? "Pausado: só leitura. Nada que escreve, executa ou envia roda até você retomar."
                            + (reason.isBlank() ? "" : " Motivo: " + reason + ".")
                    : "Ativo. \"Pausar Zordon\" põe tudo em só leitura na hora; a voz continua respondendo.");
            toggle.setText(paused ? "Retomar Zordon" : "Pausar Zordon");
            toggle.getStyleClass().setAll("button", paused ? "button-primary" : "button-danger");
            toggle.setOnAction(event -> {
                if (state.security().lockdownProperty().get()) {
                    actions.security().resumeZordon();
                } else {
                    actions.security().pauseZordon();
                }
            });
        };
        state.security().lockdownProperty().addListener((observable, before, now) -> render.run());
        state.security().lockdownReasonProperty().addListener((observable, before, now) -> render.run());
        render.run();
        VBox list = new VBox(8);
        list.setId("finding-list");
        Button refresh = new Button("Atualizar");
        refresh.setId("finding-refresh");
        refresh.setOnAction(event -> actions.security().loadFindings());
        Runnable findings = () -> {
            list.getChildren().clear();
            if (state.security().findings().isEmpty()) {
                list.getChildren().add(SettingsRows.muted("Nada estranho até agora."));
            }
            for (Map<String, Object> finding : state.security().findings()) {
                String id = String.valueOf(finding.get("findingId"));
                Label line = new Label(String.valueOf(finding.get("severity")).toUpperCase(java.util.Locale.ROOT)
                        + " · " + finding.get("title") + "\n" + finding.get("rationale"));
                line.setWrapText(true);
                Button seen = new Button("Entendi");
                seen.setId("finding-ack-" + id);
                seen.setOnAction(event -> actions.security().acknowledgeFinding(id));
                HBox row = SettingsRows.row(line, seen);
                list.getChildren().add(row);
            }
        };
        state.security().findings().addListener((ListChangeListener<Map<String, Object>>) change -> findings.run());
        findings.run();
        VBox open = new VBox(8);
        open.setId("breaker-list");
        Runnable breakers = () -> {
            open.getChildren().clear();
            for (Map<String, Object> breaker : state.security().breakers()) {
                String subject = String.valueOf(breaker.get("subject"));
                Label line = new Label(subject + " · " + VoiceSettingsLabels.breaker(String.valueOf(breaker.get("state"))) + " · "
                        + breaker.get("reason"));
                line.setWrapText(true);
                Button supervised = new Button("Liberar supervisionado");
                supervised.setId("breaker-supervised-" + subject);
                supervised.setOnAction(event -> actions.security().releaseBreaker(subject, "supervised"));
                Button closed = new Button("Liberar");
                closed.setId("breaker-closed-" + subject);
                closed.setOnAction(event -> actions.security().releaseBreaker(subject, "closed"));
                HBox row = SettingsRows.row(line, supervised, closed);
                open.getChildren().add(row);
            }
        };
        state.security().breakers().addListener((ListChangeListener<Map<String, Object>>) change -> breakers.run());
        breakers.run();
        Label hint = SettingsRows.muted("O que a defesa achou: cada item diz por que foi considerado suspeito. Disjuntor aberto"
                + " só fecha por decisão sua.");
        return SettingsRows.section("Proteção", new VBox(8, status, toggle, hint, open, SettingsRows.boundedList(list, 260)), refresh);
    }

    static Node notifications(DesktopState state, ShellActions actions) {
        VBox list = new VBox(6);
        list.setId("notification-list");
        Runnable render = () -> {
            list.getChildren().clear();
            if (state.security().notifications().isEmpty()) list.getChildren().add(SettingsRows.muted("Nenhum aviso pendente."));
            for (Map<String, Object> message : state.security().notifications()) {
                String id = String.valueOf(message.get("messageId"));
                Label title = new Label(String.valueOf(message.getOrDefault("title", "Aviso")));
                title.getStyleClass().add("card-title");
                title.setWrapText(true);
                VBox body = new VBox(5, title);
                for (String key : List.of("whatHappened", "whySuspicious", "detectedBy", "affectedResource",
                        "actionTaken", "currentState")) {
                    Object value = message.get(key);
                    if (value != null && !value.toString().isBlank()) body.getChildren().add(SettingsRows.muted(value.toString()));
                }
                Button read = new Button("Entendi");
                read.setId("notification-read-" + id);
                read.setOnAction(event -> {
                    state.security().acknowledged(id);
                    actions.security().acknowledge(id);
                });
                body.getChildren().add(read);
                body.getStyleClass().add("settings-row");
                list.getChildren().add(body);
            }
        };
        state.security().notifications().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        return Cards.section("Avisos", SettingsRows.boundedList(list, 230));
    }

    /** A quarentena (SPEC-017): o que foi "apagado" continua aqui até ser restaurado. */
    static Node quarantine(DesktopState state, ShellActions actions) {
        VBox list = new VBox(8);
        list.setId("quarantine-list");
        Label notice = new Label();
        notice.setWrapText(true);
        notice.textProperty().bind(state.security().quarantineNoticeProperty());
        notice.visibleProperty().bind(notice.textProperty().isNotEmpty());
        notice.managedProperty().bind(notice.visibleProperty());
        Button refresh = new Button("Atualizar");
        refresh.setId("quarantine-refresh");
        refresh.setOnAction(event -> actions.security().loadQuarantine());
        Runnable render = () -> {
            list.getChildren().clear();
            if (state.security().quarantine().isEmpty()) {
                list.getChildren().add(SettingsRows.muted("Nada na quarentena."));
            }
            for (Map<String, Object> item : state.security().quarantine()) {
                boolean restored = Boolean.TRUE.equals(item.get("restored"));
                Label line = new Label(item.get("root") + " · " + item.get("files") + " arquivos · "
                        + String.valueOf(item.get("ts")).replace('T', ' ').replaceAll("\\..*", "")
                        + (restored ? " · restaurado" : ""));
                line.setWrapText(true);
                Button restore = new Button("Restaurar");
                restore.setDisable(restored);
                String id = String.valueOf(item.get("vaultId"));
                restore.setId("restore-" + id);
                restore.setOnAction(event -> actions.security().restoreQuarantine(id));
                HBox row = SettingsRows.row(line, restore);
                list.getChildren().add(row);
            }
        };
        state.security().quarantine().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        Label hint = SettingsRows.muted("O Zordon não apaga: o que você mandou para a quarentena fica guardado e volta com um clique.");
        return SettingsRows.section("Quarentena", new VBox(8, hint, list, notice), refresh);
    }
}
