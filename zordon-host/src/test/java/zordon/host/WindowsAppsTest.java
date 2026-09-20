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
package zordon.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;

/** O catálogo de aplicativos do Menu Iniciar (SPEC-016). */
class WindowsAppsTest {

    @TempDir
    Path start;

    @AcceptanceCriteria("SPEC-016/CA-3")
    @Test
    void listaAtalhosSemDesinstaladoresEAbreSoOQueListou() throws Exception {
        Path jetbrains = Files.createDirectories(start.resolve("user/JetBrains"));
        Files.writeString(jetbrains.resolve("IntelliJ IDEA Community Edition.lnk"), "atalho");
        Files.writeString(jetbrains.resolve("Uninstall IntelliJ IDEA.lnk"), "atalho");
        Files.writeString(Files.createDirectories(start.resolve("all")).resolve("Bloco de Notas.lnk"), "atalho");
        Files.writeString(start.resolve("all/leia-me.txt"), "não é atalho");
        List<Path> opened = new CopyOnWriteArrayList<>();
        WindowsApps apps = new WindowsApps(List.of(start.resolve("user"), start.resolve("all"), start.resolve("sumiu")),
                opened::add);

        List<Map<String, Object>> list = apps.list();

        assertThat(list).extracting(app -> app.get("name"))
                .containsExactlyInAnyOrder("IntelliJ IDEA Community Edition", "Bloco de Notas");
        String id = String.valueOf(list.stream().filter(app -> app.get("name").toString().startsWith("IntelliJ"))
                .findFirst().orElseThrow().get("id"));
        Method open = WindowsApps.class.getDeclaredMethod("open", Map.class);
        open.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> ok = (Map<String, Object>) open.invoke(apps, Map.of("id", id));
        assertThat(ok).containsEntry("opened", true).containsEntry("name", "IntelliJ IDEA Community Edition");
        assertThat(opened).singleElement().satisfies(path -> assertThat(path.getFileName().toString())
                .isEqualTo("IntelliJ IDEA Community Edition.lnk"));

        @SuppressWarnings("unchecked")
        Map<String, Object> refused = (Map<String, Object>) open.invoke(apps,
                Map.of("id", "C:\\Windows\\System32\\cmd.exe"));
        assertThat(refused).containsEntry("opened", false).containsEntry("reason", "aplicativo fora do catálogo");
        assertThat(opened).hasSize(1);
    }
}
