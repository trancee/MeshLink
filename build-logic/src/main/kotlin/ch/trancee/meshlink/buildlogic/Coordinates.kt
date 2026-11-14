package ch.trancee.meshlink.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project

/** The groupId of every MeshLink artifact (ADR 0011, 2026-11-13 amendment "coordinates and names"). */
internal const val MESHLINK_GROUP = "ch.trancee.meshlink"

/** The one version of every MeshLink artifact (ADR 0019 decision 1), from the root `meshlink.version` property. */
internal fun Project.meshLinkVersion(): String =
    providers.gradleProperty(VERSION_PROPERTY).orNull
        ?: throw GradleException("Set $VERSION_PROPERTY in the root gradle.properties")

private const val VERSION_PROPERTY = "meshlink.version"
