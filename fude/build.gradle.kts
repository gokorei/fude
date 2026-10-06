plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.detekt)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    // Publication is `publishToMavenLocal` and nothing else, deliberately.
    //
    // There is no `publishing { repositories { ... } }` block, and adding one would be
    // a decision rather than a fix: it names a remote, and naming a remote is the
    // maintainer's call. The plugin is applied because `:fude` is consumed as a
    // published artifact -- a host application resolves it from the local repository -- so the
    // publication tasks have to exist and produce a POM with resolvable coordinates.
    // `366e528` fixed those coordinates.
    //
    // Stating the absence here rather than leaving it implicit: an inert publish setup
    // is worse than none, because it looks intentional.
    `maven-publish`
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
        optIn.add("androidx.compose.ui.text.ExperimentalTextApi")
    }

    jvm()

    // JVM-only target set: no android()/ios()/js()/wasmJs() targets are declared,
    // so there is no android{} block, no google() repo need beyond Compose
    // artifacts, and CI builds JVM only (see .github/workflows/ci.yml).

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":fude-core"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val jvmTest by getting {
            dependencies {
                // The reference parser, for the differential conformance check.
                // jvmTest rather than commonTest because org.jetbrains:markdown
                // publishes no common klib, so it cannot resolve into a source set
                // that every target compiles.
                implementation(libs.jetbrains.markdown)
                implementation(libs.compose.ui.test.junit4)
                implementation(libs.compose.desktop.currentOs)
                implementation(libs.junit.jupiter)
                // Skiko ships its native library per-OS. Without it,
                // org.jetbrains.skia.Surface fails to initialise with an
                // ExceptionInInitializerError that says nothing useful — so the
                // render tests fail on any machine that is not the one they were
                // written on, including CI.
                //
                // All of them are on the classpath rather than one per-OS: the
                // native loader picks the right one at runtime, and a build that
                // has to branch on the host OS is a build that silently skips
                // tests on an unrecognised one.
                runtimeOnly(libs.skiko.awt.runtime.macos.arm64)
                runtimeOnly(libs.skiko.awt.runtime.macos.x64)
                runtimeOnly(libs.skiko.awt.runtime.linux.x64)
                runtimeOnly(libs.skiko.awt.runtime.linux.arm64)
                runtimeOnly(libs.skiko.awt.runtime.windows.x64)
            }
        }
    }
}

// Detekt. The rule set is `detekt.yml` at the repository root, chosen rather than
// defaulted -- a rule set nobody picked produces noise within a week, and a report full
// of findings gets ignored, which is worse than no report because it looks like coverage.
//
// Configured here rather than in the root build script because the root names no detekt
// types and has none on its classpath. detekt.yml is shared; this block is not, and
// there are three copies of it, which is a fair trade for the root compiling.
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files(rootProject.file("detekt.yml")))
    parallel = true

    // Detekt's default source set is the JVM plugin's layout -- src/main/kotlin,
    // src/test/kotlin. This is a multiplatform project, whose sources live in
    // src/commonMain/kotlin and src/jvmMain/kotlin, so the default set is *empty* and
    // detekt passes having analysed nothing.
    //
    // That is the most dangerous way for a gate to be wrong: green, cheap, and
    // inspecting no code. It was caught by planting a deliberate violation and checking
    // that it failed, which is the only way to tell a working gate from an absent one.
    source.setFrom(
        files(
            "src/commonMain/kotlin",
            "src/jvmMain/kotlin",
            "src/commonTest/kotlin",
            "src/jvmTest/kotlin",
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


