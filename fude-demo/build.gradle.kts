import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
