package dev.syndicate.build

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Asserts no source file under `src/` is excluded by `.gitignore`.
 *
 * This exists because the failure it catches has already happened twice, in the same place,
 * for the same reason. `.gitignore` carried an unanchored `build/`, which git matches at any
 * depth; `build-logic`'s task classes live in package `dev.syndicate.build`, so the whole
 * directory was ignored. `git add -A` skips ignored files silently and `git status` does not
 * list them, so the working tree built perfectly and a clean clone failed to configure at
 * all. Nothing in the build could see it — the files were right there on disk.
 *
 * The check runs against the *working tree*, which is where the mistake is made and where it
 * is still cheap to fix. On CI an ignored file is simply absent, so by then the symptom is a
 * compile error somewhere unrelated to the cause.
 */
abstract class SourceTrackingCheckTask : DefaultTask() {

    /** Source roots to walk. Ignored files under these are the failure condition. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Repository root, for running git and for reporting relative paths. */
    @get:Input
    abstract val repositoryRoot: Property<String>

    init {
        group = "verification"
        description = "Asserts no file under src/ is excluded by .gitignore (DISC-005)."
    }

    @TaskAction
    fun check() {
        val root = File(repositoryRoot.get())
        // Deduplicated: the configured roots deliberately overlap (a module's `src` and the
        // tree that found it), and reporting the same file three times buries the signal.
        val files = sources.files
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter(File::isFile).toList() }
            .distinctBy { it.absolutePath }
            .sortedBy { it.invariantSeparatorsPath }
        if (files.isEmpty()) {
            return
        }

        val ignored = try {
            gitCheckIgnore(root, files)
        } catch (e: Exception) {
            // A source tarball, or a machine without git. Not being able to run the check is
            // not the same as the check failing, and failing here would break a legitimate
            // build for a reason the user cannot act on.
            logger.warn("SKIPPED ${path} — could not run `git check-ignore`: ${e.message}")
            return
        }

        if (ignored.isNotEmpty()) {
            throw GradleException(
                buildString {
                    val unique = ignored.distinct()
                    appendLine("${unique.size} source file(s) under src/ are excluded by .gitignore.")
                    appendLine("They will never be committed, and a clean clone will not build:")
                    ignored.distinct().sorted().forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Most likely an unanchored pattern matching a directory name at any depth")
                    appendLine("(a bare `build/` also matches a `build` package). Anchor it with a leading")
                    appendLine("slash, then `git add` the files. See DISC-005.")
                },
            )
        }
    }

    /**
     * Runs `git check-ignore --stdin`, which reports the subset of the given paths that are
     * ignored. Exit 0 means some were ignored, 1 means none were, anything else is an error.
     */
    private fun gitCheckIgnore(root: File, files: List<File>): List<String> {
        val process = ProcessBuilder("git", "check-ignore", "--stdin")
            .directory(root)
            .redirectErrorStream(false)
            .start()

        process.outputStream.bufferedWriter().use { writer ->
            files.forEach { writer.appendLine(it.absolutePath) }
        }
        val ignored = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit > 1) {
            throw IllegalStateException("git check-ignore exited $exit: ${errors.trim()}")
        }
        return ignored.lineSequence()
            .filter { it.isNotBlank() }
            .map { File(it).relativeToOrSelf(root).invariantSeparatorsPath }
            .toList()
    }
}
