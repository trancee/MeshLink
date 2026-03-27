plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "io.meshlink.sample.main"
            }
        }
    }
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "io.meshlink.sample.main"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":meshlink"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
    }
}
