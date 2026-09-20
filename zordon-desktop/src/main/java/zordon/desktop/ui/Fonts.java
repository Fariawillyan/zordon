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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carrega as fontes empacotadas. Sem download em runtime
 * ([Design system §4](../../../../../../../docs/specs/ui/design-system.md#4-tipografia-medidas-e-densidade)).
 */
public final class Fonts {

    /** Os arquivos em {@code resources/zordon/desktop/fonts}, com SHA-256 em {@code CHECKSUMS}. */
    public static final List<String> FILES = List.of(
            "Inter-Regular.ttf", "Inter-Medium.ttf", "Inter-SemiBold.ttf", "Inter-Bold.ttf",
            "JetBrainsMono-Regular.ttf");

    private static final Logger log = LoggerFactory.getLogger(Fonts.class);

    private Fonts() {}

    /**
     * @return as famílias carregadas. Falha em uma fonte não impede a janela: o
     *     CSS cai para Segoe UI e Consolas.
     */
    public static List<String> load() {
        List<String> families = new ArrayList<>();
        for (String file : FILES) {
            try (InputStream stream = Fonts.class.getResourceAsStream("/zordon/desktop/fonts/" + file)) {
                Font font = stream == null ? null : Font.loadFont(stream, 14);
                if (font == null) {
                    log.warn("fonte {} não carregou; usando a alternativa do sistema", file);
                } else {
                    families.add(font.getName());
                }
            } catch (IOException e) {
                log.warn("fonte {} ilegível: {}", file, e.getMessage());
            }
        }
        return families;
    }
}
