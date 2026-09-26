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

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** As peças das telas de ajustes: textos, linhas com botões, cartões e listas com rolagem. */
final class SettingsRows {

    private SettingsRows() {
    }

    static Label muted(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("muted");
        return label;
    }

    static Label warning(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("warning-text");
        return label;
    }
    /** Listas extensas têm rolagem própria, sem empurrar todos os outros controles. */
    static ScrollPane boundedList(VBox list, double height) {
        ScrollPane scroll = new ScrollPane(list) {
            @Override
            public Orientation getContentBias() {
                return Orientation.HORIZONTAL;
            }

            @Override
            protected double computePrefHeight(double width) {
                double available = width < 0 ? list.prefWidth(-1) : Math.max(1, width - 16);
                return Math.min(height, list.prefHeight(available) + 2);
            }
        };
        scroll.getStyleClass().add("settings-list");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollBarPolicy.NEVER);
        scroll.setMinHeight(0);
        scroll.setMaxHeight(height);
        return scroll;
    }

    static VBox section(String title, VBox body, Button refresh) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        refresh.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(10, heading, spacer, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(8, header, body);
        card.getStyleClass().add("card");
        return card;
    }

    static HBox row(Label line, Button... buttons) {
        line.setMinWidth(0);
        line.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(line, Priority.ALWAYS);
        HBox row = new HBox(10, line);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        for (Button button : buttons) {
            button.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(button);
        }
        return row;
    }
}
