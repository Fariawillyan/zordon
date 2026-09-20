package zordon.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

/**
 * Portão de documentação do build (docs/process/definition-of-done.md §4).
 *
 * <p>A tarefa apenas coordena: a regra está em [DocsValidator].
 */
abstract class ValidateDocsTask : DefaultTask() {

    @get:InputDirectory
    abstract val docsDir: DirectoryProperty

    /**
     * Documentos fora de `docs/` — README, CONTRIBUTING, SECURITY. Não têm
     * front-matter, mas apontam para a documentação, e um link podre aqui é o
     * primeiro que um visitante encontra.
     */
    @get:InputFiles
    abstract val rootDocs: ConfigurableFileCollection

    @TaskAction
    fun validate() {
        val report = DocsValidator(
            docsRoot = docsDir.get().asFile,
            rootDocs = rootDocs.files.toList(),
            repositoryRoot = project.rootDir,
        ).validate()

        if (!report.ok) {
            throw GradleException(
                "validateDocs reprovou com ${report.problems.size} problema(s):\n" +
                    report.problems.joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle(
            "validateDocs: ${report.documents} documentos, ${report.internalLinks} links internos, 0 problemas"
        )
    }
}
