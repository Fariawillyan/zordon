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

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Window;

/** Avisa quando um nó sai da tela: deixou a cena, ou a janela dele foi escondida. */
final class ShowingGuard {

    private final Runnable onHidden;
    private final ChangeListener<Boolean> showingListener;
    private final ChangeListener<Window> windowListener;

    private ShowingGuard(Runnable onHidden) {
        this.onHidden = onHidden;
        this.showingListener = (obs, before, now) -> {
            if (!now) onHidden.run();
        };
        this.windowListener = (obs, before, now) -> observeWindow(before, now);
    }

    static void install(Node node, Runnable onHidden) {
        ShowingGuard guard = new ShowingGuard(onHidden);
        node.sceneProperty().addListener((obs, before, now) -> guard.observeScene(before, now));
    }

    private void observeScene(Scene before, Scene now) {
        if (before != null) {
            before.windowProperty().removeListener(windowListener);
            observeWindow(before.getWindow(), null);
        }
        if (now != null) {
            now.windowProperty().addListener(windowListener);
            observeWindow(null, now.getWindow());
        } else onHidden.run();
    }

    private void observeWindow(Window before, Window now) {
        if (before != null) before.showingProperty().removeListener(showingListener);
        if (now != null) now.showingProperty().addListener(showingListener);
    }
}
