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
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import zordon.api.event.EventEnvelope;
import zordon.api.trace.Spec;

/**
 * O estado que a janela mostra, sem nenhum nó de tela.
 *
 * <p>Tudo aqui são propriedades observáveis do JavaFX, que funcionam sem toolkit:
 * as regras de estado — offline, sem provider, rascunho preservado — são testadas
 * como classe comum, e a tela só se liga a elas (SPEC-005 §5).
 *
 * <p>Deve ser alterado na thread da interface, como qualquer propriedade ligada a
 * um nó.
 *
 * <p>O que é de uma área só mora num grupo: {@link #connection()},
 * {@link #conversation()}, {@link #voice()}, {@link #security()} e
 * {@link #resources()}. Aqui fica o que atravessa as áreas — a navegação, o
 * diagnóstico e o que muda quando o núcleo entra ou sai.
 */
@Spec("SPEC-005")
public final class DesktopState {

    /** Enquanto não houver host Windows, não há captura — e a tela diz isso. */
    public static final String VOICE_UNAVAILABLE = "Voz indisponível";

    private final Clock clock;
    private final ConnectionState connection = new ConnectionState();
    private final ConversationState conversation = new ConversationState(connection.stateProperty());
    private final VoiceState voice = new VoiceState();
    private final SecurityState security = new SecurityState();
    private final ResourcesState resources = new ResourcesState();
    private final ObjectProperty<Diagnostics> diagnostics = new SimpleObjectProperty<>();
    private final StringProperty diagnosticsError = new SimpleStringProperty("");
    private final ObjectProperty<Destination> destination = new SimpleObjectProperty<>(Destination.VOICE);

    public DesktopState() {
        this(Clock.systemDefaultZone());
    }

    public DesktopState(Clock clock) {
        this.clock = clock;
    }

    public void online(String coreVersion) {
        connection.online(coreVersion, Instant.now(clock));
    }

    /**
     * O núcleo caiu. O rascunho não é tocado: desconexão não pode apagar o que o
     * usuário estava escrevendo ([Layout §7](../../../../../../../docs/specs/ui/desktop-layout.md#7-estados-e-recuperação)).
     */
    public void offline(String reason) {
        connection.offline(reason);
        conversation.turnRunningProperty().set(false);
        voice.clear();
    }

    public void diagnostics(Map<String, Object> result) {
        diagnostics.set(Diagnostics.from(result));
        diagnosticsError.set("");
        connection.lastSyncProperty().set(Instant.now(clock));
    }

    public void diagnosticsFailed(String reason) {
        diagnosticsError.set(reason);
    }

    /** Um evento do núcleo: atualiza sincronização, turno em andamento e consumo. */
    public void accept(EventEnvelope event) {
        DesktopEventApplier.apply(this, event, clock);
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
        return ComposerTarget.forScreen(destination.get(), conversation.sessionIdProperty().get() != null);
    }

    public Optional<String> providerWarning() {
        return Optional.ofNullable(diagnostics.get()).flatMap(Diagnostics::conversationWarning);
    }

    public ConnectionState connection() {
        return connection;
    }

    public ConversationState conversation() {
        return conversation;
    }

    public VoiceState voice() {
        return voice;
    }

    public SecurityState security() {
        return security;
    }

    public ResourcesState resources() {
        return resources;
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
}
