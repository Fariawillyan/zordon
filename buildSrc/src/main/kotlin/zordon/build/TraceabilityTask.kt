package zordon.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Publica o índice de rastreabilidade e reprova referência órfã.
 *
 * <p>A tarefa apenas coordena: a regra está em [TraceabilityIndexer].
 */
abstract class TraceabilityTask : DefaultTask() {

    @get:InputDirectory
    abstract val docsDir: DirectoryProperty

    /**
     * Fontes dos módulos e do build-logic. Declaradas como arquivos, e não como o
     * diretório do projeto: o diretório inteiro contém saídas de outras tarefas, e o
     * Gradle recusa essa sobreposição — com razão, porque o resultado dependeria da
     * ordem de execução.
     */
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val index: RegularFileProperty

    @TaskAction
    fun build() {
        val indexer = TraceabilityIndexer(
            docsRoot = docsDir.get().asFile,
            sources = sources.files.toList(),
            repositoryRoot = project.rootDir,
        )
        val report = indexer.index()
        index.get().asFile.apply { parentFile.mkdirs() }.writeText(indexer.toJson(report))

        if (!report.ok) {
            throw GradleException(
                "traceability reprovou com ${report.problems.size} problema(s):\n" +
                    report.problems.joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle(
            "traceability: ${report.specs.size} SPECs, " +
                "${report.implementations.values.sumOf { it.size }} ligações de código, " +
                "${report.validations.size} critérios com teste"
        )
    }
}
