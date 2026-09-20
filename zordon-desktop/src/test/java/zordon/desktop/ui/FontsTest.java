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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import zordon.api.trace.AcceptanceCriteria;

/** As fontes empacotadas são as registradas e carregam sem rede. */
class FontsTest {

    @AcceptanceCriteria("SPEC-005/CA-13")
    @Test
    void cadaFonteConfereComOChecksumRegistrado() throws Exception {
        Map<String, String> expected = checksums();

        assertThat(expected.keySet()).containsExactlyInAnyOrderElementsOf(Fonts.FILES);
        for (String file : Fonts.FILES) {
            try (InputStream stream = resource(file)) {
                String actual = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
                assertThat(actual).as(file).isEqualTo(expected.get(file));
            }
        }
    }

    @AcceptanceCriteria("SPEC-005/CA-13")
    @Test
    void asLicencasAcompanhamAsFontes() {
        assertThat(Fonts.class.getResource("/zordon/desktop/fonts/OFL-Inter.txt")).isNotNull();
        assertThat(Fonts.class.getResource("/zordon/desktop/fonts/OFL-JetBrainsMono.txt")).isNotNull();
    }

    @AcceptanceCriteria("SPEC-005/CA-13")
    @EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
    @Test
    void todasAsFontesCarregam() throws Exception {
        FxTestSupport.start();

        assertThat(FxTestSupport.onFx(Fonts::load))
                .hasSize(Fonts.FILES.size())
                .anyMatch(name -> name.startsWith("Inter"))
                .anyMatch(name -> name.startsWith("JetBrains Mono"));
    }

    private static Map<String, String> checksums() throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource("CHECKSUMS"), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.trim().split("\\s+"))
                    .collect(Collectors.toMap(parts -> parts[1].replace("*", ""), parts -> parts[0]));
        }
    }

    private static InputStream resource(String file) {
        return FontsTest.class.getResourceAsStream("/zordon/desktop/fonts/" + file);
    }
}
