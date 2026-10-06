import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.detekt)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":fude"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.kotlinx.coroutines.swing)
        }
        // The demo is a *host*, and the host's half of the extension point is the part
        // that ships untested: `CalloutSyntax` implements `recogniseBlocks`, which no
        // production code called until recently, so the demo's callouts silently did
        // nothing while the demo's own README presented them as the worked example.
        // This source set is what makes that impossible to reintroduce.
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.fude.demo.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "FudeSpike"
            packageVersion = "1.0.0"
        }
    }
}

// `run` is a JavaExec, so `-Dfude.demo.file=...` on the Gradle command line
// would otherwise be read by the Gradle daemon and never reach the demo.
tasks.withType<JavaExec>().configureEach {
    providers.systemProperty("fude.demo.file").orNull?.let {
        systemProperty("fude.demo.file", it)
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
