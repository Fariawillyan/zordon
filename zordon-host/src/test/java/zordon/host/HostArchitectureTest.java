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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.File;
import java.nio.file.Files;
import zordon.api.trace.AcceptanceCriteria;

/**
 * As regras de docs/architecture/components.md §4 que valem para o host,
 * verificadas no próprio módulo: o ArchUnit do núcleo não enxerga estas classes.
 */
@AnalyzeClasses(packages = "zordon.host", importOptions = ImportOption.DoNotIncludeTests.class)
@AcceptanceCriteria("SPEC-007/CA-7")
class HostArchitectureTest {

    @ArchTest
    static final ArchRule naoConheceONucleo = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage("zordon.core..", "zordon.ai..", "zordon.security..", "zordon.defense..")
            .because("o host fala com o núcleo pelo protocolo, não por chamada de método");

    @ArchTest
    static final ArchRule naoExecutaProcesso = noClasses()
            .should().dependOnClassesThat().areAssignableTo(ProcessBuilder.class)
            .orShould().callMethod(Runtime.class, "exec", String.class)
            .because("o supervisor do WSL é o agendador do Windows, não o host (ADR-0027)");

    @ArchTest
    static final ArchRule naoApaga = noClasses()
            .should().callMethod(Files.class, "delete", java.nio.file.Path.class)
            .orShould().callMethod(Files.class, "deleteIfExists", java.nio.file.Path.class)
            .orShould().callMethod(File.class, "delete")
            .because("o Zordon não apaga (ADR-0015)");

    @ArchTest
    static final ArchRule semCodigoNativo = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("java.lang.foreign..", "com.sun.jna..")
            .because("neste marco o host não precisa de código nativo; quando precisar, ele vive em um lugar só");

    @ArchTest
    @AcceptanceCriteria("SPEC-007/CA-6")
    static final ArchRule audioNaoVaiParaDisco = noClasses()
            .should().dependOnClassesThat().areAssignableTo(java.io.OutputStream.class)
            .orShould().dependOnClassesThat().areAssignableTo(java.io.Writer.class)
            .orShould().dependOnClassesThat().areAssignableTo(java.io.RandomAccessFile.class)
            .orShould().callMethod(Files.class, "write", java.nio.file.Path.class, byte[].class,
                    java.nio.file.OpenOption[].class)
            .because("nenhum byte de áudio vai para disco (SPEC-007 §5); o log é do logback, fora deste pacote");
}
