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
package zordon.desktop.shell;

import java.util.Map;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * A segurança como a janela a mostra: o kill switch, o OPPRESSOR MODE, os avisos,
 * a quarentena, os disjuntores, os achados e o que a defesa fez.
 */
public final class SecurityState {

    /** Kill switch (SPEC-015): o núcleo está em só leitura. */
    private final BooleanProperty lockdown = new SimpleBooleanProperty(false);
    private final StringProperty lockdownReason = new SimpleStringProperty("");
    /** OPPRESSOR MODE ativo no núcleo (SPEC-036): a janela inteira muda enquanto durar. */
    private final BooleanProperty oppressor = new SimpleBooleanProperty(false);
    /** Se há senha mestre cadastrada: sem ela, não há como ativar o modo. */
    private final BooleanProperty oppressorConfigured = new SimpleBooleanProperty(false);
    /**
     * A última falha do OPPRESSOR MODE, para a própria seção mostrar.
     *
     * <p>Sem isto o erro ia para a conversa, e quem está na tela de Segurança
     * via a ação não acontecer sem nenhuma explicação.
     */
    private final StringProperty oppressorError = new SimpleStringProperty("");
    /** Itens da quarentena, do mais novo para o mais antigo (SPEC-017). */
    private final ObservableList<Map<String, Object>> quarantine = FXCollections.observableArrayList();
    private final StringProperty quarantineNotice = new SimpleStringProperty("");
    /** Disjuntores abertos ou em prova (SPEC-027). */
    private final ObservableList<Map<String, Object>> breakers = FXCollections.observableArrayList();
    /** Achados abertos da defesa (SPEC-026). */
    private final ObservableList<Map<String, Object>> findings = FXCollections.observableArrayList();
    /** Os últimos eventos de segurança: o que foi feito, e por quê (SPEC-027). */
    private final ObservableList<Map<String, Object>> securityEvents = FXCollections.observableArrayList();
    /** Avisos do Zordon ainda não confirmados, do mais antigo para o mais novo. */
    private final ObservableList<Map<String, Object>> notifications = FXCollections.observableArrayList();

    public void lockdown(boolean active, String reason) {
        lockdown.set(active);
        lockdownReason.set(reason == null ? "" : reason);
    }

    public BooleanProperty lockdownProperty() {
        return lockdown;
    }

    public StringProperty lockdownReasonProperty() {
        return lockdownReason;
    }

    public BooleanProperty oppressorProperty() {
        return oppressor;
    }

    public BooleanProperty oppressorConfiguredProperty() {
        return oppressorConfigured;
    }

    public StringProperty oppressorErrorProperty() {
        return oppressorError;
    }

    public void oppressorFailed(String reason) {
        oppressorError.set(reason == null ? "" : reason);
    }

    /** Aplica o {@code oppressor} de {@code security.status}: ativo e se tem senha. */
    public void oppressorStatus(boolean active, boolean configured) {
        oppressor.set(active);
        oppressorConfigured.set(configured);
        oppressorError.set("");
    }

    /** Um aviso novo ou reenviado; o mesmo {@code messageId} não entra duas vezes. */
    public void notification(Map<String, Object> message) {
        Object id = message.get("messageId");
        if (id == null) {
            return;
        }
        for (int i = 0; i < notifications.size(); i++) {
            if (id.equals(notifications.get(i).get("messageId"))) {
                notifications.set(i, Map.copyOf(message));
                return;
            }
        }
        notifications.add(Map.copyOf(message));
    }

    public void acknowledged(String messageId) {
        notifications.removeIf(message -> messageId.equals(message.get("messageId")));
    }

    public ObservableList<Map<String, Object>> notifications() {
        return notifications;
    }

    public ObservableList<Map<String, Object>> quarantine() {
        return quarantine;
    }

    public StringProperty quarantineNoticeProperty() {
        return quarantineNotice;
    }

    public ObservableList<Map<String, Object>> breakers() {
        return breakers;
    }

    public ObservableList<Map<String, Object>> findings() {
        return findings;
    }

    public ObservableList<Map<String, Object>> securityEvents() {
        return securityEvents;
    }
}
