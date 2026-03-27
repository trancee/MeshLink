plugins {
    kotlin("multiplatform")
    kotlin("plugin.power-assert")
}

powerAssert {
    functions = listOf(
        "kotlin.assert",
        "kotlin.test.assertTrue",
        "kotlin.test.assertFalse",
        "kotlin.test.assertEquals",
        "kotlin.test.assertNotEquals",
        "kotlin.test.assertNull",
        "kotlin.test.assertNotNull",
        "kotlin.test.assertIs",
        "kotlin.test.assertContentEquals",
    )
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
