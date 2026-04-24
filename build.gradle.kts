// Root build file — plugins declared here with `apply false` so subprojects
// can opt in without the root module pulling in Android/KMP toolchains.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    // AGP 9.0+: com.android.library is no longer compatible with KMP modules.
    // Use the dedicated Android-KMP library plugin instead.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.bcv) apply false
    alias(libs.plugins.kotlinx.benchmark) apply false
    alias(libs.plugins.kotlin.power.assert) apply false
}
