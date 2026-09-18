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
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import zordon.api.trace.AcceptanceCriteria;
import java.io.File;
import java.nio.file.Files;
import zordon.api.trace.AcceptanceCriteria;

/**
 * As invariantes arquiteturais, verificadas no build
 * (docs/architecture/components.md §4).
 *
 * <p>Estas regras não são estilo. São a diferença entre o produto documentado e um
 * parecido com ele: sem elas, em três meses existe um {@code ProcessBuilder}
 * escondido numa Skill e o Permission Engine virou decoração.
 *
 * <p>Regras que dependem de módulos ainda não criados entram junto com eles — ver
 * SPEC-002 §4.
 */
@AnalyzeClasses(packages = "zordon", importOptions = ImportOption.DoNotIncludeTests.class)
@AcceptanceCriteria("SPEC-002/CA-13")
class ArchitectureTest {

    @ArchTest
    static final ArchRule contratoCompartilhadoNaoDependeDeNada = noClasses()
            .that()
            .resideInAPackage("zordon.api..")
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages("zordon.api..", "java..")
            .because("zordon-api é o contrato que Windows e WSL compartilham; "
                    + "uma dependência aqui é imposta aos dois lados");

    @ArchTest
    static final ArchRule clientesNaoConhecemONucleo = noClasses()
            .that()
            .resideInAnyPackage("zordon.desktop..", "zordon.host..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("zordon.core..", "zordon.ai..", "zordon.security..", "zordon.defense..")
            .because("o desktop e o host falam com o núcleo pelo protocolo, não por chamada de método");

    @ArchTest
    static final ArchRule nenhumaExecucaoDeProcessoForaDeSeguranca = noClasses()
            .that()
            .resideOutsideOfPackage("zordon.security..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(ProcessBuilder.class)
            .because("execução de processo passa pelo ProcessRunner mediado (ADR-0007)");

    @ArchTest
    static final ArchRule nenhumRuntimeExec = noClasses()
            .should()
            .callMethod(Runtime.class, "exec", String.class)
            .because("não existe execução por string de comando neste sistema (ADR-0007)");

    @ArchTest
    static final ArchRule nenhumaExclusaoDeArquivo = noClasses()
            .that()
            .resideOutsideOfPackage("zordon.defense.vault..")
            .should()
            .callMethod(Files.class, "delete", java.nio.file.Path.class)
            .orShould()
            .callMethod(Files.class, "deleteIfExists", java.nio.file.Path.class)
            .orShould()
            .callMethod(File.class, "delete")
            .because("o Zordon não apaga: a operação destrutiva mais forte é a quarentena, "
                    + "que é reversível (ADR-0015)");

    @ArchTest
    static final ArchRule chamadaNativaContida = noClasses()
            .that()
            .resideOutsideOfPackage("zordon.core.platform..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.lang.foreign..")
            .because("código nativo contorna toda a mediação da JVM; ele vive em um lugar só, "
                    + "e esse lugar é revisável");

    @ArchTest
    @AcceptanceCriteria("SPEC-004/CA-11")
    static final ArchRule nucleoNaoConheceFornecedorDeModelo = noClasses()
            .that()
            .resideInAPackage("zordon.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("zordon.ai.anthropic..", "zordon.ai.openai..", "com.anthropic..")
            .because("o núcleo pede um provider ao registro e não sabe quem responde; trocar de modelo "
                    + "é editar config.toml, não recompilar (ADR-0026)");

    @ArchTest
    @AcceptanceCriteria("SPEC-004/CA-11")
    static final ArchRule sdkDeFornecedorSoNoSeuAdaptador = noClasses()
            .that()
            .resideOutsideOfPackage("zordon.ai.anthropic..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.anthropic..")
            .because("um tipo do SDK fora do adaptador é o primeiro passo para o núcleo voltar a "
                    + "pertencer a um fornecedor (ADR-0026)");

    @ArchTest
    @AcceptanceCriteria("SPEC-004/CA-11")
    static final ArchRule adaptadoresNaoSeConhecem = noClasses()
            .that()
            .resideInAPackage("zordon.ai.anthropic..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("zordon.ai.openai..")
            .orShould()
            .dependOnClassesThat()
            .resideInAPackage("zordon.ai.registry..")
            .because("um adaptador fala com um fornecedor; quem escolhe entre eles é o registro");

    @ArchTest
    static final ArchRule capacidadesNaoSeConhecem = slices()
            .matching("zordon.(*)..")
            .namingSlices("módulo $1")
            .should()
            .beFreeOfCycles()
            .because("dependência circular entre módulos dissolve as fronteiras onde a segurança mora");
}
