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
                runtimeOnly(libs.skiko.awt.runtime.macos.arm64)
            }
        }
    }
}
