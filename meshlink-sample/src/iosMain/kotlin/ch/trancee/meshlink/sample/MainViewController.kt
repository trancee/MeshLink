package ch.trancee.meshlink.sample

import androidx.compose.ui.window.ComposeUIViewController

/**
 * iOS entry point for the MeshLink reference app.
 *
 * Called from the Xcode project's SampleApp.swift via the Kotlin framework.
 * Returns a UIViewController backed by the shared [App] composable.
 *
 * Note: iOS native compilation only runs on macOS; on Linux this file is parsed
 * but not compiled (kotlin.native.ignoreDisabledTargets=true in gradle.properties).
 */
fun MainViewController() = ComposeUIViewController { App() }
