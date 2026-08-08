package dev.syndicate.build

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The cross-reference integrity check of docs/00_master_index.md#D00-S5.3 and the required
 * section structure of #D00-S5.4.
 *
 * The blueprints are a contract only while their citations resolve. A dangling
 * `#D06-S4.2` is worse than a missing one: a reader follows it, finds nothing, and
 * concludes the requirement was dropped. This task makes that a build failure the moment
 * it is introduced, which is the only point at which the author still remembers what they
 * meant.
 */
@CacheableTask
abstract class ValidateDocsTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val docsDir: DirectoryProperty

    init {
        group = "verification"
        description = "Validates blueprint section IDs, citations, and structure (D00-S5.3, #D00-S5.4)."
    }

    @TaskAction
    fun validate() {
        val dir = docsDir.get().asFile
        val files = dir.listFiles { f: File -> f.isFile && f.extension == "md" }
            ?.sortedBy { it.name }
            ?: throw GradleException("docs directory not readable: $dir")

        val errors = mutableListOf<String>()
        val declared = LinkedHashMap<String, Declaration>()

        // ---- Pass 1: collect declarations ------------------------------------------
        for (file in files) {
            val docNumber = file.name.take(2)
            if (!docNumber.all { it.isDigit() }) {
                errors += "${file.name}: filename does not start with a two-digit doc number (D00-R11)"
                continue
            }
            forEachProseLine(file) { index, line ->
                val id = ID_DECLARATION.find(line.trim())?.groupValues?.get(1) ?: return@forEachProseLine
                val declaration = Declaration(file.name, index + 1)
                if (!id.startsWith("D$docNumber-")) {
                    errors += "${file.name}:${index + 1}: id '$id' does not match its file's number $docNumber"
                }
                val previous = declared.put(id, declaration)
                if (previous != null) {
                    errors += "${file.name}:${index + 1}: id '$id' already declared at " +
                        "${previous.file}:${previous.line} (D00-R7: ids are globally unique)"
                }
            }
        }

        // ---- Pass 2: resolve citations ---------------------------------------------
        for (file in files) {
            forEachProseLine(file) { index, line ->
                // Skip the declaration comments themselves; they are definitions, not citations.
                if (ID_DECLARATION.containsMatchIn(line.trim())) return@forEachProseLine

                for (match in QUALIFIED_CITATION.findAll(line)) {
                    val fileName = match.groupValues[1]
                    val id = match.groupValues[2]
                    if (id.endsWith(RESERVED_EXAMPLE_SUFFIX)) continue
                    val declaration = declared[id]
                    when {
                        declaration == null ->
                            errors += "${file.name}:${index + 1}: dangling reference '$id'"
                        declaration.file != fileName ->
                            errors += "${file.name}:${index + 1}: '$id' is declared in " +
                                "${declaration.file}, cited as $fileName"
                    }
                }
                // Bare `Dxx-Sy.z` citations (D00-R9). Strip the qualified ones first so a
                // qualified citation is not also counted, and re-reported, as a bare one.
                val withoutQualified = QUALIFIED_CITATION.replace(line, "")
                for (match in BARE_CITATION.findAll(withoutQualified)) {
                    val id = match.groupValues[1]
                    if (id.endsWith(RESERVED_EXAMPLE_SUFFIX)) continue
                    if (id !in declared) {
                        errors += "${file.name}:${index + 1}: dangling reference '$id'"
                    }
                }
            }
        }

        // ---- Pass 3: one file per doc number, required sections ---------------------
        for (docNumber in 0..14) {
            val prefix = "%02d".format(docNumber)
            val matching = files.filter { it.name.startsWith("${prefix}_") }
            if (matching.size != 1) {
                errors += "expected exactly one docs/${prefix}_*.md, found ${matching.size} (D00-S5.3)"
                continue
            }
            // D00 is the master index and is exempt: R23 scopes the required nine to D01-D14.
            if (docNumber > 0) {
                errors += checkRequiredSections(matching.single())
            }
        }

        if (errors.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Blueprint validation failed (${errors.size} problem(s)):")
                    errors.forEach { appendLine("  $it") }
                },
            )
        }
        logger.lifecycle("validateDocs: ${declared.size} section ids across ${files.size} documents, all citations resolve")
    }

    /**
     * D00-R24: the nine required titles must appear as an ordered *subsequence* of the
     * top-level headings — extra sections between them are legal, missing or reordered
     * ones are not — and numbering must run 1..n with no gaps.
     */
    private fun checkRequiredSections(file: File): List<String> {
        val errors = mutableListOf<String>()
        val headings = file.readLines()
            .mapNotNull { TOP_LEVEL_HEADING.find(it.trim()) }
            .map { it.groupValues[1].toInt() to it.groupValues[2].trim() }

        var cursor = 0
        for (required in REQUIRED_SECTIONS) {
            var found = false
            while (cursor < headings.size) {
                val title = headings[cursor].second
                cursor += 1
                if (title.contains(required, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            if (!found) {
                errors += "${file.name}: missing or out-of-order required section '$required' (D00-R23)"
            }
        }

        headings.forEachIndexed { index, (number, title) ->
            if (number != index + 1) {
                errors += "${file.name}: section '$title' is numbered $number, expected ${index + 1} (D00-R24)"
            }
        }
        return errors
    }

    /**
     * Visits the lines of a markdown file that are prose, skipping fenced code blocks.
     *
     * D00-R7 requires this: a fenced block showing the ID syntax contains a literal
     * `<!-- D06-S4.2 -->` that is an illustration, not a second declaration of that
     * section. Without the skip, every document that documents the convention collides
     * with the document that follows it.
     */
    private fun forEachProseLine(file: File, action: (Int, String) -> Unit) {
        var inFence = false
        file.readLines().forEachIndexed { index, line ->
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                return@forEachIndexed
            }
            if (!inFence) {
                action(index, line)
            }
        }
    }

    private data class Declaration(val file: String, val line: Int)

    private companion object {
        val ID_DECLARATION = Regex("""^<!--\s*(D\d\d-S[\d.]+)\s*-->""")
        val QUALIFIED_CITATION = Regex("""docs/(\d\d_[a-z0-9_]+\.md)#(D\d\d-S[\d.]+)""")
        val BARE_CITATION = Regex("""\b(D\d\d-S[\d.]+)\b""")
        /**
         * A top-level heading, optionally prefixed by its stable-ID comment — D00-R6 puts
         * the comment on the same line as the header, not the line above it.
         */
        val TOP_LEVEL_HEADING = Regex("""^(?:<!--[^>]*-->)?##\s+(\d+)\.\s+(.+)$""")

        /**
         * D00-R7 reserves section path `99.9` so a document can write a citation that is
         * *required* not to resolve (T-D00-2, T-D13-4) without that example becoming a
         * real dangling reference.
         */
        const val RESERVED_EXAMPLE_SUFFIX = "-S99.9"

        val REQUIRED_SECTIONS = listOf(
            "Purpose",
            "Scope",
            "Dependencies",
            "Data Contracts",
            "Logic & Algorithms",
            "Acceptance Criteria",
            "Edge Cases & Failure Modes",
            "Test Cases",
            "Cross-References",
        )
    }
}
