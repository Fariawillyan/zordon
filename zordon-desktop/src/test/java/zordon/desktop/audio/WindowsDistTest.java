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
package zordon.desktop.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

/** O que {@code :zordon-desktop:windowsDist} entrega ao Windows (SPEC-008 v2). */
class WindowsDistTest {

    private static final Path DIST = Path.of(System.getProperty("zordon.windowsDist", "build/windows-dist"));

    @AcceptanceCriteria("SPEC-008/CA-10")
    @Test
    void levaOJavafxDoWindowsENenhumNativoDeLinux() throws IOException {
        try (Stream<Path> files = Files.walk(DIST)) {
            List<String> names = files.map(path -> path.getFileName().toString()).toList();
            assertThat(names)
                    .contains("javafx-base-25.0.4-win.jar", "javafx-graphics-25.0.4-win.jar", "javafx-controls-25.0.4-win.jar")
                    .noneMatch(name -> name.endsWith("-linux.jar"));
        }
    }

    @AcceptanceCriteria("SPEC-008/CA-10")
    @Test
    void oLancadorUsaJavawComOJavafxNoModulePathEJarsQueExistem() throws IOException {
        List<String> args = Files.readAllLines(DIST.resolve("zordon-desktop.args"));
        String modulePath = value(args, "--module-path ");
        String classpath = value(args, "-cp ");

        assertThat(modulePath.split(";")).allSatisfy(jar -> assertThat(jar).endsWith("-win.jar"));
        assertThat(args).contains("--add-modules javafx.controls").last().isEqualTo("zordon.desktop.ZordonDesktop");
        assertThat(Arrays.stream((modulePath + ";" + classpath).split(";")).map(DIST::resolve))
                .allSatisfy(jar -> assertThat(jar).exists());
        assertThat(Files.readString(DIST.resolve("zordon-desktop.cmd"))).contains("javaw @zordon-desktop.args");
        assertThat(Files.readAllLines(DIST.resolve("zordon-audio-check.args")))
                .last().isEqualTo("zordon.desktop.audio.PlaybackCheck");
    }

    private static String value(List<String> args, String prefix) {
        return args.stream().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow()
                .substring(prefix.length());
    }
}
