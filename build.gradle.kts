// Force minimum secure versions for vulnerable transitive build-classpath dependencies
// brought in by AGP 9.x. All are build-only (not shipped in the library artifact).
// Dependabot alerts: #1 httpclient XSS, #2 commons-lang3 recursion, #3 jdom2 XXE,
//                   #4 jose4j DoS, #5 bcpkix broken crypto, #6 bcprov LDAP injection.
buildscript {
    configurations.all {
        resolutionStrategy.eachDependency {
            when ("${requested.group}:${requested.name}") {
                "org.apache.httpcomponents:httpclient" -> {
                    useVersion("4.5.14")
                    because("CVE-2020-13956: XSS via request URI; fixed in 4.5.13")
                }
                "org.apache.commons:commons-lang3" -> {
                    useVersion("3.18.0")
                    because("CVE-2025-27553: uncontrolled recursion on long inputs; fixed in 3.18.0")
                }
                "org.jdom:jdom2" -> {
                    useVersion("2.0.6.1")
                    because("CVE-2021-33813: XXE injection; fixed in 2.0.6.1")
                }
                "org.bitbucket.b_c:jose4j" -> {
                    useVersion("0.9.6")
                    because("CVE-2023-51775: DoS via compressed JWE content; fixed in 0.9.6")
                }
                "org.bouncycastle:bcpkix-jdk18on" -> {
                    useVersion("1.84")
                    because("CVE-2025-29908: broken/risky cryptographic algorithm; fixed in 1.84")
                }
                "org.bouncycastle:bcprov-jdk18on" -> {
                    useVersion("1.84")
                    because("CVE-2024-34447: LDAP injection vulnerability; fixed in 1.84")
                }
            }
        }
    }
}

// Root build file — plugins declared here with `apply false` so subprojects
// can opt in without the root module pulling in Android/KMP toolchains.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    // AGP 9.0+: com.android.library is no longer compatible with KMP modules.
    // Use the dedicated Android-KMP library plugin instead.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    // Standard Android app plugin for :meshlink-sample test harness.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.bcv) apply false
    alias(libs.plugins.kotlinx.benchmark) apply false
    alias(libs.plugins.kotlin.power.assert) apply false
}
