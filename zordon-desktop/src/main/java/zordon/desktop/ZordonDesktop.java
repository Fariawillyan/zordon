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

import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.ui.Fonts;
import zordon.desktop.ui.ZordonShell;

/**
 * A janela do Zordon: liga a conexão com o núcleo ao estado, e o estado à tela.
 *
 * <p>Fechá-la não encerra nada: o núcleo é um serviço e continua trabalhando. A
 * interface é uma projeção do fluxo de eventos, não a dona do estado.
 *
 * <p>Aqui fica o ciclo de vida da janela e da bandeja. O que a tela pede ao
 * núcleo está em {@link DesktopActions}; a conexão, em {@link DesktopSession}; e
 * os eventos, em {@link DesktopEvents}.
 */
public final class ZordonDesktop extends Application {

    static final String VERSION = "0.1.0";

    private final DesktopState state = new DesktopState();
    private final DesktopContext context = new DesktopContext(state);
    private final DesktopActions actions = new DesktopActions(context);
    private final DesktopEvents events = new DesktopEvents(context, actions);
    private final UiEventPump pump = new UiEventPump(events::apply);
    private final DesktopSession session = new DesktopSession(context, actions, pump);

    private ZordonShell shell;
    private Optional<ZordonTray> tray = Optional.empty();
    private Stage stage;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        Fonts.load();
        this.shell = new ZordonShell(state, actions);
        context.attach(primary, shell);

        // Janela compacta da SPEC-010: o console de voz é o conteúdo.
        Scene scene = new Scene(shell, 960, 720);
        scene.getStylesheets().add(DesktopContext.stylesheet());
        primary.setTitle("Zordon");
        for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
            primary.getIcons().add(new Image(ZordonDesktop.class.getResource(
                    "/zordon/desktop/icons/zordon-" + size + ".png").toExternalForm()));
        }
        primary.setMinWidth(720);
        primary.setMinHeight(560);
        primary.setScene(scene);

        // A janela some para a bandeja; o processo continua para receber eventos.
        Platform.setImplicitExit(false);
        primary.setOnCloseRequest(event -> {
            if (tray.isPresent()) {
                event.consume();
                primary.hide();
            } else {
                shutdown();
            }
        });
        primary.show();

        tray = ZordonTray.install(this::showWindow, this::shutdown, () -> Platform.runLater(() -> {
            if (state.security().lockdownProperty().get()) {
                actions.security().resumeZordon();
            } else {
                actions.security().pauseZordon();
            }
        }));
        state.security().lockdownProperty().addListener((observable, before, now) -> tray.ifPresent(icon -> icon.lockdown(now)));
        state.connection().stateProperty().addListener((observable, before, now) -> tray.ifPresent(icon -> icon.show(
                switch (now) {
                    case ONLINE -> TrayState.ONLINE;
                    case CONNECTING -> TrayState.DEGRADED;
                    case OFFLINE -> TrayState.OFFLINE;
                })));
        pump.start();
        session.connect();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void showWindow() {
        Platform.runLater(() -> {
            stage.show();
            stage.toFront();
            stage.requestFocus();
        });
    }

    private void shutdown() {
        shell.close();
        pump.stop();
        if (context.connection() != null) {
            context.connection().close();
        }
        tray.ifPresent(ZordonTray::remove);
        Platform.exit();
    }
}
