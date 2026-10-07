plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.detekt)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":fude"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            // document/window/location for the mount point and the demo harness.
            // The version is the one Compose itself resolves against (see the
            // dependency tree of `org.jetbrains.compose.ui:ui`), not a guess.
            implementation(libs.kotlinx.browser)
        }
    }
}

// Flaky toolchain, recorded rather than worked around: `compileKotlinWasmJs`
// intermittently dies with an instant `OutOfMemoryError` inside the
// `@JsExport` checker (`WasmKlibExportingDeclaration` reading IR) on rebuilds,
// while clean rebuilds of the same sources pass in seconds. Observed three
// times in one session; `incremental = false` on the compile task did not
// help, so it is not incremental state worth disabling — when it strikes,
// `:fude-web:clean` and rebuild. A fresh-checkout CI never sees it, which is
// why it is a comment here rather than a gate anywhere.

// Detekt. The rule set is `detekt.yml` at the repository root, chosen rather than

// defaulted -- a rule set nobody picked produces noise within a week, and a report full
// of findings gets ignored, which is worse than no report because it looks like coverage.
//
// Configured here rather than in the root build script because the root names no detekt
// types and has none on its classpath. detekt.yml is shared; this block is not, and
// there are four copies of it, which is a fair trade for the root compiling.
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files(rootProject.file("detekt.yml")))
    parallel = true

    // Detekt's default source set is the JVM plugin's layout -- src/main/kotlin,
    // src/test/kotlin. This is a multiplatform project, whose sources live in
    // src/wasmJsMain/kotlin, so the default set is *empty* and
    // detekt passes having analysed nothing.
    //
    // That is the most dangerous way for a gate to be wrong: green, cheap, and
    // inspecting no code. It was caught by planting a deliberate violation and checking
    // that it failed, which is the only way to tell a working gate from an absent one.
    source.setFrom(
        files(
            "src/wasmJsMain/kotlin",
        ),
    )
    // Unused code is the rule this codebase most wants. The review that produced the
    // `input/` deletion was entirely about code that compiled, was tested, and was
    // unreachable.
    // Module-local, not one file at the root: detekt analyses each module separately
    // and `detektBaseline` *overwrites* the file it is given, so a shared path would
    // leave whichever module ran last as the only recorded baseline.
    baseline = file("detekt-baseline.xml").takeIf { it.exists() }
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    baseline = file("detekt-baseline.xml")
}

// Detekt 1.23.8 is the latest release, and it bundles a kotlin-compiler-embeddable that
// cannot read a Java 25 version string: on a JDK 25 host it dies with a bare "25.0.4"
// before analysing a single file. The `Detekt` task is not a `JavaExec`, so a Java 17
// launcher cannot be injected either.
//
// So the gate states whether it ran. Silently passing because it analysed nothing is the
// worst outcome available -- it looks like coverage -- so on an unsupported JVM detekt
// is left out of `check` and says so loudly, rather than being wired in and either
// failing the build for an unrelated reason or reporting a green it did not earn.
//
// On CI, and on any developer machine on JDK 17 through 21, this runs and is enforced.
// At most 22, verified by running it there rather than assumed. `isCompatibleWith`
// would be the wrong test: it asks "is this at least 21", which Java 25 satisfies.
val detektCanRun = JavaVersion.current() < JavaVersion.VERSION_23
if (detektCanRun) {
    tasks.named("check") { dependsOn("detekt") }
} else {
    logger.lifecycle(
        "detekt: SKIPPED. This JVM is Java ${JavaVersion.current()}, and detekt " +
            "1.23.8 cannot parse a version string that new -- it fails before reading " +
            "any source. The static-analysis gate did NOT run. Run the build on JDK 17 " +
            "through 22 to enforce it. apiCheck and checkArchitecture are unaffected.",
    )
    // Eager, not `configureEach`: the detekt plugin wires `detekt` into `check`
    // itself, so the disable has to be registered before that graph is built.
    tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detekt") { enabled = false }
}
