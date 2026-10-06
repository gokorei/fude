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
 * test suite with no host-store dependency whatsoever". That is worth a build check
 * rather than a README sentence, because the failure mode — someone importing
 * `DocId` to make one function convenient — is invisible until someone notices
 * the split has quietly been undone.
 *
 * Three rules, each for a different reason:
 *
 * 1. No host store. The library must not know what a document *is*. `DocId`,
 *    `Frontmatter` and `ParsedMarkdown` are the host's (Tanseki's) domain types.
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

val checkArchitecture = tasks.register("checkArchitecture") {
    group = "verification"
    description = "Fails if commonMain depends on host-store types, java.*, or (in :fude-core) Compose."

    doLast {
        val violations = mutableListOf<String>()

        for (dir in checkedDirs) {
            val root = file(dir)
            if (!root.isDirectory) continue

            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { source ->
                    var inBlockComment = false
                    source.readLines().forEachIndexed { index, rawLine ->
                        var line = rawLine
                        // Strip block comments (single-line span; multi-line tracked).
                        if (inBlockComment) {
                            val end = line.indexOf("*/")
                            if (end < 0) return@forEachIndexed
                            line = line.substring(end + 2)
                            inBlockComment = false
                        }
                        while (true) {
                            val start = line.indexOf("/*")
                            if (start < 0) break
                            val end = line.indexOf("*/", start + 2)
                            if (end < 0) {
                                line = line.substring(0, start)
                                inBlockComment = true
                                break
                            }
                            line = line.substring(0, start) + line.substring(end + 2)
                        }
                        // Strip line comments and string literals so references in
                        // prose or strings do not count as dependencies.
                        line = line.substringBefore("//")
                        line = line.replace(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), "\"\"")
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEachIndexed

                        for (type in forbiddenTypes) {
                            if (Regex("\\b${Regex.escape(type)}\\b").containsMatchIn(line)) {
                                violations += "${source.path}:${index + 1}: forbidden reference to '$type'" +
                                    "\n    Fude must not know what a document is."
                            }
                        }
                        for (prefix in forbiddenImportPrefixes) {
                            if (trimmed.startsWith("import $prefix") ||
                                Regex("\\b${Regex.escape(prefix.dropLast(1))}\\.[A-Za-z]").containsMatchIn(line)
                            ) {
                                violations += "${source.path}:${index + 1}: commonMain must not use '$prefix'" +
                                    "\n    ${rawLine.trim()}" +
                                    "\n    Platform source sets may use platform APIs; commonMain may not."
                            }
                        }
                        if (source.path.startsWith("fude-core/") &&
                            (line.contains("androidx.compose") || line.contains("@Composable"))
                        ) {
                            violations += "${source.path}:${index + 1}: :fude-core must not depend on Compose" +
                                "\n    ${rawLine.trim()}" +
                                "\n    The state model stays portable so it can be tested without a UI toolkit."
                            return@forEachIndexed
                        }
                        // Fully-qualified references bypass the import check by
                        // construction: `dev.fude.core.Delete(...)` compiles with no
                        // import. Treat any FQ use of a forbidden type the same as
                        // an import of it (covered by the word-boundary check above
                        // on every line, not just imports).
                    }
                }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Architecture check FAILED: ${violations.size} violation(s)\n\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("Architecture check passed: no host-store types, no java/javax imports in commonMain, no Compose in :fude-core.")
    }
}

// One publication identity for every module, set here rather than per module.
//
// `:fude` had `group` and `version` inline and `:fude-core` had neither, so the POM that
// `:fude` published declared a dependency on `fude:fude-core:unspecified` — a coordinate
// that resolves to nothing. A transitive dependency is only as good as the identity of the
// module behind it, so the identity belongs where every module inherits it.
allprojects {
    group = "dev.fude"
    version = "0.1.0-SNAPSHOT"
}

// Wired into every module's `check`, so `./gradlew build` and `./gradlew check`
// both enforce it. The root project has no `check` task of its own.
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkArchitecture"))
    }
}

// Binary compatibility, for the two published modules only.
//
// `:fude-demo` is a host application and is never published, so its API changing is
// not a compatibility event. `:fude-core` and `:fude` are, which means a change to
// either is a change to a published coordinate -- and published API in this library is
// expensive to take back, because a downstream that compiled against it has to be
// recompiled for every reason at once.
//
// The gate is `apiCheck`, wired into `check` alongside the architecture check so that
// `./gradlew build` and `./gradlew check` both enforce it. It compares the compiled
// declarations against the checked-in `api/*.api` dumps; `apiDump` regenerates them,
// and regenerating them is a deliberate act rather than something the build does for
// you. A build that silently rewrote its own baseline could not fail.
configure(listOf(":fude-core", ":fude")) {
    plugins.withId("org.jetbrains.kotlinx.binary-compatibility-validator") {
        tasks.named("check") { dependsOn("apiCheck") }
    }
}

// Static analysis, across everything.
//
// The architecture check above is a build script that reads source text, and it exists
// because this codebase has a rule no compiler enforces: `commonMain` may not import
// `java.*`, and `:fude-core` may not import Compose. Those are the two rules a
// contributor breaks by accident, and by the time a compiler sees them it is usually
// too late to explain why they are forbidden.
//
// Detekt covers the rest: the ordinary smells that a compiler does not report. It is
// configured rather than defaulted, because a rule set nobody chose produces noise
// within a week and a noise-filled report gets ignored, which is worse than no report.
