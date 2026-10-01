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
