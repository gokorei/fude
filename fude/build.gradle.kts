plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

group = "dev.fude"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
        optIn.add("androidx.compose.ui.text.ExperimentalTextApi")
    }

    jvm()

    android {
        namespace = "dev.fude.editor"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

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
            implementation(libs.jetbrains.markdown)
        }
        val jvmTest by getting {
            dependencies {
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
