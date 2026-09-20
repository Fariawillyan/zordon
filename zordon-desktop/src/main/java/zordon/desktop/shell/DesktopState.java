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

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import zordon.api.event.EventEnvelope;
import zordon.api.trace.Spec;
import zordon.zwp.CoreConnection;

/**
 * O estado que a janela mostra, sem nenhum nó de tela.
 *
 * <p>Tudo aqui são propriedades observáveis do JavaFX, que funcionam sem toolkit:
 * as regras de estado — offline, sem provider, rascunho preservado — são testadas
 * como classe comum, e a tela só se liga a elas (SPEC-005 §5).
 *
 * <p>Deve ser alterado na thread da interface, como qualquer propriedade ligada a
 * um nó.
 */
@Spec("SPEC-005")
public final class DesktopState {

    /** Enquanto não houver host Windows, não há captura — e a tela diz isso. */
    public static final String VOICE_UNAVAILABLE = "Voz indisponível";
    static final int TRANSCRIPTIONS_KEPT = 20;

    private final Clock clock;
    private final ObjectProperty<CoreConnection.State> connection =
            new SimpleObjectProperty<>(CoreConnection.State.CONNECTING);
    private final StringProperty connectionDetail = new SimpleStringProperty("procurando o núcleo");
    private final ObjectProperty<Instant> lastSync = new SimpleObjectProperty<>();
    private final IntegerProperty reconnections = new SimpleIntegerProperty();
    private final ObjectProperty<Diagnostics> diagnostics = new SimpleObjectProperty<>();
    private final StringProperty diagnosticsError = new SimpleStringProperty("");
    private final ObjectProperty<Destination> destination = new SimpleObjectProperty<>(Destination.VOICE);
    private final StringProperty sessionId = new SimpleStringProperty();
    private final ObjectProperty<TurnSummary> lastTurn = new SimpleObjectProperty<>();
    private final ObjectProperty<SessionUsage> usage = new SimpleObjectProperty<>(SessionUsage.NONE);
    private final BooleanProperty turnRunning = new SimpleBooleanProperty();
    private final StringProperty currentTurnId = new SimpleStringProperty();
    private final StringProperty draft = new SimpleStringProperty("");
    private final ObjectProperty<VoiceStatus> voice = new SimpleObjectProperty<>();
    /** Último {@code VOICE_LEVEL} em dBFS: {@code [rms, pico]}. */
    private final ObjectProperty<double[]> voiceLevel = new SimpleObjectProperty<>();
    /** Estado visual do núcleo, de {@code ACTIVITY_STATE} (SPEC-012). */
    private final StringProperty activity = new SimpleStringProperty("idle");
    /**
     * Modo técnico (SPEC-012 CA-8): desligado por padrão. Voice-first — detalhes
     * técnicos só aparecem quando o usuário pede (ADR-0029).
     */
    private final StringProperty voiceError = new SimpleStringProperty("");
    /** Kill switch (SPEC-015): o núcleo está em só leitura. */
    private final BooleanProperty lockdown = new SimpleBooleanProperty(false);
    private final StringProperty lockdownReason = new SimpleStringProperty("");
    /** Itens da quarentena, do mais novo para o mais antigo (SPEC-017). */
    private final javafx.collections.ObservableList<Map<String, Object>> quarantine =
            javafx.collections.FXCollections.observableArrayList();
    private final StringProperty quarantineNotice = new SimpleStringProperty("");
    /** Disjuntores abertos ou em prova (SPEC-027). */
    private final javafx.collections.ObservableList<Map<String, Object>> breakers =
            javafx.collections.FXCollections.observableArrayList();
    /** Achados abertos da defesa (SPEC-026). */
    private final javafx.collections.ObservableList<Map<String, Object>> findings =
            javafx.collections.FXCollections.observableArrayList();
    /** Tarefas recentes, cada uma com as etapas em {@code steps} (SPEC-023). */
    private final javafx.collections.ObservableList<Map<String, Object>> tasks =
            javafx.collections.FXCollections.observableArrayList();
    /** Os agentes e os arquivos que não viraram agente (SPEC-022). */
    private final javafx.collections.ObservableList<Map<String, Object>> agents =
            javafx.collections.FXCollections.observableArrayList();
    /** O que o Zordon lembra, do mais novo para o mais antigo (SPEC-021). */
    private final javafx.collections.ObservableList<Map<String, Object>> memoryFacts =
            javafx.collections.FXCollections.observableArrayList();
    private final javafx.collections.ObservableList<Map<String, Object>> automations =
            javafx.collections.FXCollections.observableArrayList();
    private final javafx.collections.ObservableList<Map<String, Object>> automationProposals =
            javafx.collections.FXCollections.observableArrayList();

    public javafx.collections.ObservableList<Map<String, Object>> automations() { return automations; }
    public javafx.collections.ObservableList<Map<String, Object>> automationProposals() { return automationProposals; }

    /** As ferramentas que o modelo pode pedir, com risco e efeitos (SPEC-019). */
    private final javafx.collections.ObservableList<Map<String, Object>> skills =
            javafx.collections.FXCollections.observableArrayList();
    /** Os últimos eventos de segurança: o que foi feito, e por quê (SPEC-027). */
    private final javafx.collections.ObservableList<Map<String, Object>> securityEvents =
            javafx.collections.FXCollections.observableArrayList();
    /** {@code rag.status} (SPEC-028), {@code usage.summary} (SPEC-029) e {@code system.metrics} (SPEC-024). */
    private final ObjectProperty<Map<String, Object>> knowledge = new SimpleObjectProperty<>(Map.of());
    private final ObjectProperty<Map<String, Object>> usageSummary = new SimpleObjectProperty<>(Map.of());
    private final ObjectProperty<Map<String, Object>> systemMetrics = new SimpleObjectProperty<>(Map.of());

    public javafx.collections.ObservableList<Map<String, Object>> skills() { return skills; }

    public javafx.collections.ObservableList<Map<String, Object>> securityEvents() { return securityEvents; }

    public ObjectProperty<Map<String, Object>> knowledgeProperty() { return knowledge; }

    public ObjectProperty<Map<String, Object>> usageSummaryProperty() { return usageSummary; }

    public ObjectProperty<Map<String, Object>> systemMetricsProperty() { return systemMetrics; }

    /** Servidores MCP declarados e o estado de cada um (SPEC-020). */
    private final javafx.collections.ObservableList<Map<String, Object>> mcpServers =
            javafx.collections.FXCollections.observableArrayList();
    /** Avisos do Zordon ainda não confirmados, do mais antigo para o mais novo. */
    private final javafx.collections.ObservableList<Map<String, Object>> notifications =
            javafx.collections.FXCollections.observableArrayList();
    private final ObservableList<VoiceDevice> voiceDevices = FXCollections.observableArrayList();
    private final ObservableList<String> transcriptions = FXCollections.observableArrayList();
    private final StringBinding composerBlockedReason;
    private final StringBinding coreLabel;
    private boolean everConnected;

    public DesktopState() {
        this(Clock.systemDefaultZone());
    }

    public DesktopState(Clock clock) {
        this.clock = clock;
        this.composerBlockedReason = Bindings.createStringBinding(
                () -> switch (connection.get()) {
                    case ONLINE -> "";
                    case CONNECTING -> "Aguardando o núcleo…";
                    case OFFLINE -> "Núcleo offline — reconectando. O rascunho fica guardado.";
                },
                connection);
        this.coreLabel = Bindings.createStringBinding(
                () -> switch (connection.get()) {
                    case ONLINE -> "Núcleo conectado";
                    case CONNECTING -> "Conectando…";
                    case OFFLINE -> "Núcleo offline";
                },
                connection);
    }

    public void online(String coreVersion) {
        everConnected = true;
        connection.set(CoreConnection.State.ONLINE);
        connectionDetail.set("WSL · núcleo " + coreVersion);
        lastSync.set(Instant.now(clock));
    }

    /**
     * O núcleo caiu. O rascunho não é tocado: desconexão não pode apagar o que o
     * usuário estava escrevendo ([Layout §7](../../../../../../../docs/specs/ui/desktop-layout.md#7-estados-e-recuperação)).
     */
    public void offline(String reason) {
        if (everConnected && connection.get() != CoreConnection.State.OFFLINE) {
            reconnections.set(reconnections.get() + 1);
        }
        connection.set(CoreConnection.State.OFFLINE);
        connectionDetail.set(reason);
        turnRunning.set(false);
        // Sem núcleo, o estado da voz é desconhecido; não mostrar o último como atual.
        voice.set(null);
        voiceDevices.clear();
    }

    /** Snapshot de {@code voice.status}, {@code voice.setMode} ou {@code VOICE_STATE}. */
    public void voice(Map<String, Object> snapshot) {
        voice.set(VoiceStatus.from(snapshot));
        voiceError.set("");
    }

    public void voiceFailed(String reason) {
        voiceError.set(reason);
    }

    /** Resposta de {@code voice.devices}. */
    public void voiceDevices(Map<String, Object> result) {
        voiceDevices.setAll(VoiceDevice.listFrom(result));
    }

    public void diagnostics(Map<String, Object> result) {
        diagnostics.set(Diagnostics.from(result));
        diagnosticsError.set("");
        lastSync.set(Instant.now(clock));
    }

    public void diagnosticsFailed(String reason) {
        diagnosticsError.set(reason);
    }

    /** Um evento do núcleo: atualiza sincronização, turno em andamento e consumo. */
    public void accept(EventEnvelope event) {
        lastSync.set(Instant.now(clock));
        Map<String, Object> payload = event.payload();
        switch (event.type()) {
            case USER_COMMAND -> {
                turnRunning.set(true);
                currentTurnId.set(String.valueOf(payload.get("turnId")));
            }
            case AI_THINKING -> turnRunning.set(true);
            case AI_ERROR -> turnRunning.set(false);
            case VOICE_STATE -> voice(payload);
            case ACTIVITY_STATE -> activity.set(String.valueOf(payload.getOrDefault("state", "idle")));
            case VOICE_LEVEL -> voiceLevel.set(new double[] {
                number(payload.get("rms")), number(payload.get("peak"))});
            case LOCKDOWN_ENTERED -> lockdown(true, String.valueOf(payload.getOrDefault("reason", "")));
            case LOCKDOWN_EXITED -> lockdown(false, "");
            case SECURITY_NOTIFICATION -> notification(payload);
            case VOICE_STOPPED -> {
                transcriptions.addFirst(java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                                .format(java.time.LocalTime.now(clock))
                        + " · " + (payload.get("text") instanceof String text ? text
                                : "(" + payload.getOrDefault("outcome", "sem texto") + ")")
                        + " · confiança " + payload.getOrDefault("confidence", "?"));
                if (transcriptions.size() > TRANSCRIPTIONS_KEPT) {
                    transcriptions.remove(TRANSCRIPTIONS_KEPT, transcriptions.size());
                }
            }
            case AI_RESPONSE -> TurnSummary.fromResponse(payload).ifPresent(turn -> {
                turnRunning.set(false);
                lastTurn.set(turn);
                usage.set(usage.get().plus(turn));
            });
            default -> { }
        }
    }

    /**
     * Navega. Destino indisponível não abre: devolve falso, e quem chamou mostra o
     * motivo.
     */
    public boolean select(Destination target) {
        if (!target.isAvailable()) {
            return false;
        }
        destination.set(target);
        return true;
    }

    public ComposerTarget composerTarget() {
        return ComposerTarget.forScreen(destination.get(), sessionId.get() != null);
    }

    public Optional<String> providerWarning() {
        return Optional.ofNullable(diagnostics.get()).flatMap(Diagnostics::conversationWarning);
    }

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

    public javafx.collections.ObservableList<Map<String, Object>> quarantine() {
        return quarantine;
    }

    public StringProperty quarantineNoticeProperty() {
        return quarantineNotice;
    }

    public javafx.collections.ObservableList<Map<String, Object>> breakers() {
        return breakers;
    }

    public javafx.collections.ObservableList<Map<String, Object>> findings() {
        return findings;
    }

    public javafx.collections.ObservableList<Map<String, Object>> tasks() {
        return tasks;
    }

    public javafx.collections.ObservableList<Map<String, Object>> agents() {
        return agents;
    }

    public javafx.collections.ObservableList<Map<String, Object>> memoryFacts() {
        return memoryFacts;
    }

    public javafx.collections.ObservableList<Map<String, Object>> mcpServers() {
        return mcpServers;
    }

    public void acknowledged(String messageId) {
        notifications.removeIf(message -> messageId.equals(message.get("messageId")));
    }

    public javafx.collections.ObservableList<Map<String, Object>> notifications() {
        return notifications;
    }

    public ObjectProperty<VoiceStatus> voiceProperty() {
        return voice;
    }

    public StringProperty activityProperty() {
        return activity;
    }

    public ObjectProperty<double[]> voiceLevelProperty() {
        return voiceLevel;
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : -90;
    }

    public StringProperty voiceErrorProperty() {
        return voiceError;
    }

    public ObservableList<VoiceDevice> voiceDevices() {
        return voiceDevices;
    }

    public ObservableList<String> transcriptions() {
        return transcriptions;
    }

    public ObjectProperty<CoreConnection.State> connectionProperty() {
        return connection;
    }

    public StringProperty connectionDetailProperty() {
        return connectionDetail;
    }

    public ObjectProperty<Instant> lastSyncProperty() {
        return lastSync;
    }

    public IntegerProperty reconnectionsProperty() {
        return reconnections;
    }

    public ObjectProperty<Diagnostics> diagnosticsProperty() {
        return diagnostics;
    }

    public StringProperty diagnosticsErrorProperty() {
        return diagnosticsError;
    }

    public ObjectProperty<Destination> destinationProperty() {
        return destination;
    }

    public StringProperty sessionIdProperty() {
        return sessionId;
    }

    public ObjectProperty<TurnSummary> lastTurnProperty() {
        return lastTurn;
    }

    public ObjectProperty<SessionUsage> usageProperty() {
        return usage;
    }

    public BooleanProperty turnRunningProperty() {
        return turnRunning;
    }

    /** O turno em andamento — é ele que "Cancelar tarefa" cancela. */
    public StringProperty currentTurnIdProperty() {
        return currentTurnId;
    }

    public StringProperty draftProperty() {
        return draft;
    }

    public StringBinding composerBlockedReason() {
        return composerBlockedReason;
    }

    public StringBinding coreLabel() {
        return coreLabel;
    }
}
