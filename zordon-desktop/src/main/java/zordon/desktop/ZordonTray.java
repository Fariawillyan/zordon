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

import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ícone de bandeja.
 *
 * <p>Fechar a janela não encerra o processo, e o rótulo do último item é
 * "Encerrar interface", não "Sair": o usuário precisa entender que o Zordon
 * continua rodando ([UI §5](../../../../../docs/specs/ui/design.md#5-system-tray)).
 *
 * <p>O ícone é desenhado em código. Um asset bitmap seria uma dependência visual a
 * manter e a licenciar, e o design system pede o contrário.
 */
final class ZordonTray {

    private static final Logger log = LoggerFactory.getLogger(ZordonTray.class);
    private static final int SIZE = 16;

    private final TrayIcon icon;
    private final MenuItem pause;

    private ZordonTray(TrayIcon icon, MenuItem pause) {
        this.icon = icon;
        this.pause = pause;
    }

    /**
     * @return vazio quando o ambiente não tem bandeja — o aplicativo continua
     *     inteiro, só sem o ícone. Não é motivo para não abrir.
     */
    static Optional<ZordonTray> install(Runnable onOpen, Runnable onQuit, Runnable onTogglePause) {
        if (!SystemTray.isSupported()) {
            log.info("bandeja do sistema indisponível neste ambiente");
            return Optional.empty();
        }
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Abrir Zordon");
            open.addActionListener(event -> onOpen.run());
            // O kill switch mora aqui também: é o botão que se aperta quando algo parece errado (Segurança §8).
            MenuItem pause = new MenuItem("Pausar Zordon");
            pause.addActionListener(event -> onTogglePause.run());
            MenuItem quit = new MenuItem("Encerrar interface");
            quit.addActionListener(event -> onQuit.run());
            menu.add(open);
            menu.add(pause);
            menu.addSeparator();
            menu.add(quit);

            TrayIcon icon = new TrayIcon(render(TrayState.OFFLINE), TrayState.OFFLINE.tooltip(), menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(event -> onOpen.run());
            SystemTray.getSystemTray().add(icon);
            return Optional.of(new ZordonTray(icon, pause));
        } catch (AWTException | RuntimeException e) {
            log.warn("não foi possível instalar o ícone da bandeja: {}", e.toString());
            return Optional.empty();
        }
    }

    void show(TrayState state) {
        icon.setImage(render(state));
        icon.setToolTip(state.tooltip());
    }

    void lockdown(boolean active) {
        pause.setLabel(active ? "Retomar Zordon" : "Pausar Zordon");
    }

    void remove() {
        SystemTray.getSystemTray().remove(icon);
    }

    private static BufferedImage render(TrayState state) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(state.color());
        graphics.fillOval(1, 1, SIZE - 2, SIZE - 2);
        graphics.dispose();
        return image;
    }
}
