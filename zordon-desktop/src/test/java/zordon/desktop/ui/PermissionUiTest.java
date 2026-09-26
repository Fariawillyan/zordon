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

import static org.assertj.core.api.Assertions.assertThat;
import static zordon.desktop.ui.FxTestSupport.onFx;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.SecurityPresentation;
import zordon.desktop.shell.SecurityPresentation.Choice;

/** O diálogo de permissão, os avisos e o lockdown na tela (SPEC-015). */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class PermissionUiTest {

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.start();
    }

    private static Map<String, Object> request(String risk, String origin, boolean perAction, long ttlMs) {
        return Map.of("requestId", "perm-1", "tool", "fs.quarantine",
                "summary", "Mover 43 arquivos de D:\\projeto\\logs para a quarentena", "risk", risk,
                "origin", origin, "targets", List.of("D:\\projeto\\logs\\a.log", "D:\\projeto\\logs\\b.log"),
                "targetCount", 43, "perAction", perAction, "ttlMs", ttlMs);
    }

    @AcceptanceCriteria("SPEC-015/CA-2")
    @Test
    void regrasDoDialogoNegarEhOPadraoRedExigeConferenciaESessaoSoParaYellowPelaJanela() {
        SecurityPresentation.Prompt red = SecurityPresentation.prompt(request("red", "voice", true, 60_000));
        assertThat(red.requiresCheck()).isTrue();
        assertThat(red.offersSession()).isFalse();
        assertThat(red.hiddenTargets()).isEqualTo(41);
        assertThat(red.origin()).contains("voz");
        assertThat(red.seconds()).isEqualTo(60);

        SecurityPresentation.Prompt yellowUi = SecurityPresentation.prompt(request("yellow", "ui", false, 60_000));
        assertThat(yellowUi.requiresCheck()).isFalse();
        assertThat(yellowUi.offersSession()).isTrue();
        assertThat(SecurityPresentation.prompt(request("yellow", "voice", true, 60_000)).offersSession()).isFalse();

        assertThat(SecurityPresentation.answer(Choice.DENY)).isEqualTo("deny");
        assertThat(SecurityPresentation.answer(Choice.SESSION)).isEqualTo("session");
    }

    @AcceptanceCriteria("SPEC-015/CA-2")
    @Test
    void enterNegaERedSoAutorizaDepoisDeConferir() throws Exception {
        PermissionPane red = onFx(() -> new PermissionPane(
                SecurityPresentation.prompt(request("red", "ui", true, 60_000))));
        Stage stage = onFx(() -> {
            Stage window = new Stage();
            window.setScene(FxTestSupport.styledScene(red, 620, 480));
            window.show();
            return window;
        });
        try {
            onFx(() -> {
                Button deny = (Button) red.lookup("#permission-deny");
                Button allow = (Button) red.lookup("#permission-allow");
                assertThat(deny.isDefaultButton()).as("Enter nega").isTrue();
                assertThat(deny.isCancelButton()).as("Esc nega").isTrue();
                assertThat(allow.isDefaultButton()).isFalse();
                assertThat(allow.isDisabled()).as("RED: autorizar só depois de conferir").isTrue();
                assertThat(red.lookup("#permission-session")).as("RED nunca oferece sessão").isNull();
                ((CheckBox) red.lookup("#permission-checked")).setSelected(true);
                assertThat(allow.isDisabled()).isFalse();
                FxTestSupport.save(red.getScene(), Path.of("build/ui-snapshots/permissao-red.png"));
                deny.fire();
                return null;
            });
            assertThat(red.result().get(2, TimeUnit.SECONDS)).isEqualTo(Choice.DENY);
        } finally {
            onFx(() -> { stage.close(); return null; });
        }

        PermissionPane yellow = onFx(() -> new PermissionPane(
                SecurityPresentation.prompt(request("yellow", "ui", false, 60_000))));
        onFx(() -> { ((Button) yellow.lookup("#permission-session")).fire(); return null; });
        assertThat(yellow.result().get(2, TimeUnit.SECONDS)).isEqualTo(Choice.SESSION);
    }

    @AcceptanceCriteria("SPEC-015/CA-1")
    @Test
    void semResponderNoPrazoONegaSozinho() throws Exception {
        PermissionPane pane = onFx(() -> new PermissionPane(
                SecurityPresentation.prompt(request("yellow", "ui", false, 1_000))));
        assertThat(pane.result().get(5, TimeUnit.SECONDS)).isEqualTo(Choice.DENY);
    }

    @AcceptanceCriteria("SPEC-015/CA-7")
    @Test
    void criticalAbreAJanelaEExigeLeituraOBannerMostraWarningEAcima() throws Exception {
        assertThat(SecurityPresentation.critical("critical")).isTrue();
        assertThat(SecurityPresentation.critical("high")).isFalse();
        assertThat(SecurityPresentation.banner("info")).isFalse();
        assertThat(SecurityPresentation.banner("warning")).isTrue();

        Map<String, Object> message = Map.of("messageId", "msg-1", "severity", "critical",
                "title", "A auditoria foi alterada por fora", "actionTaken", "O Zordon entrou em só leitura.",
                "whatHappened", "a", "whySuspicious", "b", "detectedBy", "c", "affectedResource", "d",
                "currentState", "e", "options", List.of("Conferir"));
        DesktopState state = onFx(DesktopState::new);
        String[] read = new String[1];
        CriticalNotice notice = onFx(() -> new CriticalNotice(message, () -> read[0] = "lido"));
        NotificationBar bar = onFx(() -> {
            state.notification(message);
            state.notification(message);   // reenvio na reconexão não duplica
            return new NotificationBar(state, new ShellActions() {
                @Override public void send(String text, zordon.desktop.shell.ComposerTarget target) {}
                @Override public void newConversation() {}
                @Override public void cancelTurn(String turnId) {}
                @Override public void refreshDiagnostics() {}
                @Override public void setVoiceMode(String mode) {}
                @Override public void loadVoiceDevices() {}
                @Override public void selectVoiceDevice(String deviceId) {}
                @Override public void testMicrophone() {}
                @Override public void startListening() {}
                @Override public void stopListening() {}
            });
        });
        onFx(() -> {
            assertThat(state.notifications()).hasSize(1);
            assertThat(bar.isVisible()).isTrue();
            assertThat(bar.text()).contains("A auditoria foi alterada por fora");
            ((Button) notice.lookup("#critical-read")).fire();
            ((Button) bar.lookup("#notification-ack")).fire();
            assertThat(state.notifications()).isEmpty();
            assertThat(bar.isVisible()).isFalse();
            return null;
        });
        assertThat(read[0]).isEqualTo("lido");
    }

    @AcceptanceCriteria("SPEC-015/CA-6")
    @Test
    void lockdownApareceNaPilulaENosAjustes() throws Exception {
        DesktopState state = onFx(DesktopState::new);
        boolean[] asked = new boolean[2];
        ShellActions actions = new ShellActions() {
            @Override public void send(String text, zordon.desktop.shell.ComposerTarget target) {}
            @Override public void newConversation() {}
            @Override public void cancelTurn(String turnId) {}
            @Override public void refreshDiagnostics() {}
            @Override public void setVoiceMode(String mode) {}
            @Override public void loadVoiceDevices() {}
            @Override public void selectVoiceDevice(String deviceId) {}
            @Override public void testMicrophone() {}
            @Override public void startListening() {}
            @Override public void stopListening() {}
            @Override public void pauseZordon() { asked[0] = true; }
            @Override public void resumeZordon() { asked[1] = true; }
        };
        SecurityView settings = onFx(() -> new SecurityView(state, actions));
        VoiceView voice = onFx(() -> new VoiceView(state, actions));
        try {
            onFx(() -> {
                state.online("teste");
                Button toggle = (Button) settings.getContent().lookup("#lockdown-toggle");
                assertThat(toggle.getText()).isEqualTo("Pausar Zordon");
                toggle.fire();
                state.lockdown(true, "pausado pelo usuário na janela");
                assertThat(toggle.getText()).isEqualTo("Retomar Zordon");
                assertThat(voice.pillVisible()).isTrue();
                assertThat(voice.pillText()).isEqualTo(SecurityPresentation.PAUSED);
                toggle.fire();
                return null;
            });
            assertThat(asked).containsExactly(true, true);
        } finally {
            onFx(() -> { voice.close(); return null; });
        }
    }

    @Test
    void servidorMcpQueMudouMostraAprovarEOBotaoPedeAoNucleo() throws Exception {
        DesktopState state = onFx(DesktopState::new);
        java.util.List<String> approved = new java.util.concurrent.CopyOnWriteArrayList<>();
        ShellActions actions = new ShellActions() {
            @Override public void send(String text, zordon.desktop.shell.ComposerTarget target) {}
            @Override public void newConversation() {}
            @Override public void cancelTurn(String turnId) {}
            @Override public void refreshDiagnostics() {}
            @Override public void setVoiceMode(String mode) {}
            @Override public void loadVoiceDevices() {}
            @Override public void selectVoiceDevice(String deviceId) {}
            @Override public void testMicrophone() {}
            @Override public void startListening() {}
            @Override public void stopListening() {}
            @Override public void approveMcp(String server) { approved.add(server); }
        };
        McpView settings = onFx(() -> new McpView(state, actions));
        onFx(() -> {
            state.mcpServers().setAll(
                    Map.of("name", "git", "state", "connected", "tools", java.util.List.of("mcp.git.log"), "drift", false),
                    Map.of("name", "docker", "state", "drift", "tools", java.util.List.of(), "drift", true));
            assertThat(settings.getContent().lookup("#mcp-approve-git")).as("sem mudança, sem botão").isNull();
            ((Button) settings.getContent().lookup("#mcp-approve-docker")).fire();
            return null;
        });
        assertThat(approved).containsExactly("docker");
        assertThat(VoiceSettingsLabels.state("drift")).isEqualTo("mudou, aguardando aprovação");
    }

    @Test
    void memoriaListaOsFatosEOBotaoEsquecePeloNucleo() throws Exception {
        DesktopState state = onFx(DesktopState::new);
        java.util.List<String> forgotten = new java.util.concurrent.CopyOnWriteArrayList<>();
        ShellActions actions = new ShellActions() {
            @Override public void send(String text, zordon.desktop.shell.ComposerTarget target) {}
            @Override public void newConversation() {}
            @Override public void cancelTurn(String turnId) {}
            @Override public void refreshDiagnostics() {}
            @Override public void setVoiceMode(String mode) {}
            @Override public void loadVoiceDevices() {}
            @Override public void selectVoiceDevice(String deviceId) {}
            @Override public void testMicrophone() {}
            @Override public void startListening() {}
            @Override public void stopListening() {}
            @Override public void forgetFact(String factId) { forgotten.add(factId); }
        };
        MemoryView settings = onFx(() -> new MemoryView(state, actions));
        onFx(() -> {
            state.memoryFacts().setAll(Map.of("id", "f_1", "kind", "PREFERENCE", "subject", "respostas",
                    "content", "prefere respostas curtas", "observedAt", "2026-09-19T15:00:00Z"));
            javafx.scene.Node list = settings.getContent().lookup("#memory-list");
            assertThat(((javafx.scene.layout.VBox) list).getChildren()).hasSize(1);
            ((Button) settings.getContent().lookup("#memory-forget-f_1")).fire();
            return null;
        });
        assertThat(forgotten).containsExactly("f_1");
        assertThat(VoiceSettingsLabels.kind("EVENT")).isEqualTo("evento");
    }
}
