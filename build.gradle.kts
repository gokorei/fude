plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

/**
 * Fails the build if `commonMain` reaches for anything it must not.
 *
 * The test for the extension point is "the library builds and passes its full
 * test suite with no Opal dependency whatsoever". That is worth a build check
 * rather than a README sentence, because the failure mode — someone importing
 * `DocId` to make one function convenient — is invisible until someone notices
 * the split has quietly been undone.
 *
 * Three rules, each for a different reason:
 *
 * 1. No Opal. The library must not know what a document *is*. `DocId`,
 *    `Frontmatter` and `ParsedMarkdown` are Opal's domain types.
 * 2. No `java.*` or `javax.*`. `commonMain` runs on every target, so anything
 *    JVM-only breaks the moment a native or web target is added. This is also
 *    why grapheme segmentation in `Graphemes` is hand-written instead of
 *    delegating to `java.text.BreakIterator`.
 * 3. No Compose in `:fude-core`. The state model stays portable so it can be
 *    tested headlessly on every target without a UI toolkit. Compose belongs in
 *    `:fude`, where rendering lives.
 *
 * Only `commonMain` is checked. Platform source sets may use their own platform's
 * APIs — a clipboard implementation has to.
 */
val forbiddenTypes = listOf(
    "DocId",
    "Frontmatter",
    "FrontmatterCodec",
    "ParsedMarkdown",
    "core.domain",
    "gokorei.tanseki",
)

val forbiddenImportPrefixes = listOf("java.", "javax.")

val checkedDirs = listOf("fude-core/src/commonMain", "fude/src/commonMain")

val checkArchitecture by tasks.registering {
    group = "verification"
    description = "Fails if commonMain depends on Opal, java.*, or (in :fude-core) Compose."

    doLast {
        val violations = mutableListOf<String>()

        for (dir in checkedDirs) {
            val root = file(dir)
            if (!root.isDirectory) continue

            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { source ->
                    source.readLines().forEachIndexed { index, rawLine ->
                        val line = rawLine.trim()
                        if (!line.startsWith("import ") && !line.startsWith("package ")) {
                            return@forEachIndexed
                        }

                        for (type in forbiddenTypes) {
                            if (line.contains(type)) {
                                violations += "${source.path}:${index + 1}: forbidden reference to '$type'" +
                                    "\n    Fude must not know what a document is."
                            }
                        }
                        for (prefix in forbiddenImportPrefixes) {
                            if (line.startsWith("import $prefix")) {
                                violations += "${source.path}:${index + 1}: commonMain must not import '$prefix'" +
                                    "\n    $line" +
                                    "\n    Platform source sets may use platform APIs; commonMain may not."
                            }
                        }
                        if (source.path.startsWith("fude-core/") && line.contains("androidx.compose")) {
                            violations += "${source.path}:${index + 1}: :fude-core must not depend on Compose" +
                                "\n    $line" +
                                "\n    The state model stays portable so it can be tested without a UI toolkit."
                        }
                    }
                }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Architecture check FAILED: ${violations.size} violation(s)\n\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("Architecture check passed: no Opal types, no java/javax imports in commonMain, no Compose in :fude-core.")
    }
}

// Wired into every module's `check`, so `./gradlew build` and `./gradlew check`
// both enforce it. The root project has no `check` task of its own.
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkArchitecture"))
    }
}
