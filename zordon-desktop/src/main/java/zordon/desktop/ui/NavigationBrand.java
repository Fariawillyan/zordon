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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.StrokeLineJoin;

/** A marca no topo da coluna, com a assinatura, como na referência do owner (SPEC-032). */
final class NavigationBrand extends VBox {

    NavigationBrand() {
        super(6);
        Label name = new Label("ZORDON");
        name.getStyleClass().add("nav-brand-name");
        Label tagline = new Label("SEMPRE AO SEU LADO");
        tagline.getStyleClass().add("nav-brand-tag");
        VBox words = new VBox(1, name, tagline);
        words.setAlignment(Pos.CENTER);
        getChildren().addAll(logo(), words);
        setAlignment(Pos.CENTER);
        setPadding(new Insets(0, 0, 18, 0));
    }

    /** O símbolo da imagem: dois triângulos, sem bitmap. */
    private static StackPane logo() {
        Polygon outer = new Polygon(12, 1, 23, 20, 1, 20);
        outer.setFill(Color.TRANSPARENT);
        outer.setStroke(NavigationList.ACTIVE);
        outer.setStrokeWidth(2.2);
        outer.setStrokeLineJoin(StrokeLineJoin.MITER);
        StackPane mark = new StackPane(outer);
        mark.setAccessibleText("Zordon");
        return mark;
    }
}
