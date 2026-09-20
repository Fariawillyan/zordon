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
package zordon.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import zordon.api.security.Effect;
import zordon.api.trace.AcceptanceCriteria;

/** O núcleo de confiança como ele é, verificado no build (SPEC-014 CA-9). */
class SecurityArchitectureTest {

    private static final JavaClasses ZORDON = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("zordon");

    @AcceptanceCriteria("SPEC-014/CA-9")
    @Test
    void processoSoNaSegurancaNenhumaExclusaoESegurancaNaoConheceONucleo() {
        assertThat(ZORDON.contain("zordon.security.DefaultPermissionEngine"))
                .as("o módulo de segurança está no classpath analisado").isTrue();

        noClasses().that().resideOutsideOfPackage("zordon.security..")
                .should().dependOnClassesThat().areAssignableTo(ProcessBuilder.class)
                .check(ZORDON);
        noClasses().that().resideOutsideOfPackage("zordon.defense.vault..")
                .should().callMethod(Files.class, "delete", java.nio.file.Path.class)
                .orShould().callMethod(Files.class, "deleteIfExists", java.nio.file.Path.class)
                .orShould().callMethod(File.class, "delete")
                .check(ZORDON);
        noClasses().that().resideInAPackage("zordon.security..")
                .should().dependOnClassesThat().resideInAnyPackage("zordon.core..", "zordon.ai..", "zordon.zwp..")
                .because("o núcleo de confiança não depende de quem ele protege")
                .check(ZORDON);

        assertThat(Arrays.stream(Effect.values()).map(effect -> effect.name().toLowerCase(Locale.ROOT)))
                .as("não existe efeito de exclusão (ADR-0015)")
                .noneMatch(name -> name.contains("delete") || name.contains("remove") || name.contains("erase"));
    }
}
