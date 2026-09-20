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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

/**
 * O contrato das tarefas agendadas (SPEC-007 §3, ADR-0027). A CI não tem Windows
 * para registrá-las; o que dá para garantir aqui é que o script as declara como
 * a SPEC pede.
 */
class RegisterTasksContractTest {

    private static final Path SCRIPT = Path.of(System.getProperty("zordon.repoRoot", ".."))
            .resolve("packaging/windows/register-tasks.ps1");

    @AcceptanceCriteria("SPEC-007/CA-8")
    @Test
    void registraOHostNoLogonComJavawEReinicioEmFalha() throws IOException {
        String script = normalized();

        assertThat(script)
                .contains("New-ScheduledTaskTrigger -AtLogOn")
                .contains("Register-ZordonTask -Name 'Zordon Host'")
                .contains("New-ScheduledTaskAction -Execute $Javaw -Argument '@zordon-host.args' -WorkingDirectory $HostDir")
                .contains("-RestartOnFailure")
                .contains("$options.RestartCount = 999");
    }

    @AcceptanceCriteria("SPEC-007/CA-8")
    @Test
    void oWslBootRepeteACadaCincoMinutosSemInstanciasParalelas() throws IOException {
        String script = normalized();

        assertThat(script)
                .contains("Register-ZordonTask -Name 'Zordon WSL Boot'")
                .contains("-Argument \"-d $Distro --exec /bin/true\"")
                .contains("-RepeatEvery (New-TimeSpan -Minutes 5)")
                .contains("MultipleInstances = 'IgnoreNew'");
    }

    /** Sem quebras de linha com crase e sem alinhamento: o contrato é o conteúdo. */
    private static String normalized() throws IOException {
        return Files.readString(SCRIPT).replace("`\r\n", " ").replace("`\n", " ").replaceAll("[ \\t]+", " ");
    }
}
