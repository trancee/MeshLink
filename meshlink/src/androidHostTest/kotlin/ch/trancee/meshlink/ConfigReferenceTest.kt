package ch.trancee.meshlink

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Generates `docs/reference/configuration.md` and `docs/reference/presets.md` from the Kotlin source
 * (ADR 0006 decision 3): each option's type and KDoc from its declaration, each preset's values from
 * the preset itself. It fails when a committed page differs; `./gradlew updateReference` rewrites them.
 */
class ConfigReferenceTest {
    private val root = File(checkNotNull(System.getProperty("reference.root")) { "reference.root is not set" })
    private val sources = File(root, "meshlink/src/commonMain/kotlin/ch/trancee/meshlink")

    @Test
    fun theConfigurationReferenceMatchesTheSource() = compare("configuration.md", configuration())

    @Test
    fun thePresetReferenceMatchesThePresets() = compare("presets.md", presets())

    private fun compare(
        name: String,
        generated: String,
    ) {
        val page = File(root, "docs/reference/$name")
        if (System.getProperty("reference.update") == "true") page.writeText(generated)
        val committed = if (page.exists()) page.readText() else ""
        assertEquals(generated, committed, "docs/reference/$name is stale: run ./gradlew updateReference")
    }

    private fun configuration(): String {
        val builder = MeshLinkConfigBuilder(Preset.OfflineChat, SecurityProfile.Unsecured)
        val required =
            (runCatching { builder.build() }.exceptionOrNull() as MeshLinkException.InvalidConfig).setting
        val fixed =
            mapOf("meshId" to builder.meshId, "meshKey" to builder.meshKey, "l2capUpgrade" to builder.l2capUpgrade)
        val knobs = Knobs(Preset.OfflineChat.values).all.map { it.key }
        val sections =
            (fixed.keys + knobs).map { key ->
                val (type, doc) = declaration(key)
                val default =
                    when {
                        key == required -> "none; required"
                        key in fixed -> "`${fixed[key]}`"
                        else -> "set by the preset; see [Presets](presets.md)"
                    }
                "## `$key`\n\n- Type: `$type`\n- Default: $default\n\n$doc\n" + alternatives(type)
            }
        val intro =
            listOf(
                "# Configuration",
                "",
                GENERATED,
                "",
                "Every setting of `meshLinkConfig(preset, securityProfile) { … }`. The block sets them; " +
                    "`build()` validates them and throws `MeshLinkException.InvalidConfig`, naming the setting, " +
                    "for the first one out of bounds. `MeshLinkConfig.describe()` prints each effective value " +
                    "with its source: `preset`, `override` or `default`.",
                "",
                "",
            )
        return intro.joinToString("\n") + sections.joinToString("\n")
    }

    private fun presets(): String {
        val presets =
            listOf(
                Preset.OfflineChat,
                Preset.EmergencyBroadcast,
                Preset.ProximityDiscovery,
                Preset.LocalMultiplayer,
                Preset.FieldTeams,
            )
        val text = File(sources, "Preset.kt").readText()
        val documented = PRESET.findAll(text).associate { it.groupValues[2] to markdown(it.groupValues[1]) }
        assertEquals(documented.keys.toList(), presets.map { it.toString() }, "a built-in preset is missing here")
        val custom = markdown(checkNotNull(CUSTOM.find(text)).groupValues[1])
        val keys = Knobs(Preset.OfflineChat.values).all.map { it.key }
        val values = presets.map { preset -> Knobs(preset.values).all.map { "`${it.value}`" } }
        val rows = keys.indices.map { row -> "| `${keys[row]}` | ${values.joinToString(" | ") { it[row] }} |" }
        return listOf(
            "# Presets",
            "",
            GENERATED,
            "",
            "A preset sets every value below; the block of `meshLinkConfig` or `Preset.Custom` overrides any " +
                "of them. A preset is local to a node: nodes with different presets form one mesh, and every " +
                "node relays every other node's frames. No preset sets background behavior. " +
                "See [Configuration](configuration.md) for what each value does.",
            "",
            "## Built-in presets",
            "",
            documented.entries.joinToString("\n") { (name, doc) -> "- `$name`: $doc" },
            "",
            "## Values",
            "",
            "| Setting | ${presets.joinToString(" | ") { "`$it`" }} |",
            "|---|${presets.joinToString("") { "---|" }}",
            rows.joinToString("\n"),
            "",
            "## `Custom`",
            "",
            custom,
            "",
        ).joinToString("\n")
    }

    // The type and KDoc of the option describe() names [key], such as `limits.maxUnicastPayload`.
    private fun declaration(key: String): Pair<String, String> {
        val property = key.substringAfterLast('.')
        val pattern = documented("""public var $property: ([\w.?]+)""")
        val match =
            listOf("MeshLinkConfig.kt", "PresetSettings.kt").firstNotNullOf {
                pattern.find(File(sources, it).readText())
            }
        return match.groupValues[2] to markdown(match.groupValues[1])
    }

    // The documented alternatives declared in [type]'s own file, such as the entries of an enum, each
    // qualified by the enum or sealed type that declares it.
    private fun alternatives(type: String): String {
        val file = File(sources, "$type.kt")
        if (!file.exists()) return ""
        val text = file.readText()
        val entries =
            ALTERNATIVE.findAll(text).map { match ->
                val owner =
                    ENUM
                        .findAll(text.take(match.range.first))
                        .lastOrNull()
                        ?.groupValues
                        ?.get(1) ?: type
                "- `$owner.${match.groupValues[2]}`: ${markdown(match.groupValues[1])}"
            }
        return "\nValues:\n\n${entries.joinToString("\n")}\n"
    }

    private companion object {
        const val GENERATED =
            "<!-- Generated by ConfigReferenceTest from the Kotlin source: " +
                "edit the KDoc, then run ./gradlew updateReference. -->"

        // A KDoc block, its text in group 1.
        const val KDOC = """/\*\*((?:(?!\*/).)*)\*/\s*"""

        // A KDoc block directly before [declaration].
        fun documented(declaration: String) = Regex(KDOC + declaration, RegexOption.DOT_MATCHES_ALL)

        val PRESET = documented("""public data object (\w+)""")
        val CUSTOM = documented("public class Custom")
        val ALTERNATIVE = documented("""(?:public data (?:object|class) )?([A-Z]\w*)\s*[(:,]""")
        val ENUM = Regex("""enum class (\w+)""")
        val SYMBOL = Regex("""\[([A-Za-z_][\w.]*)\](?!\()""")

        // KDoc body to Markdown: drops the leading stars, joins wrapped lines and writes [Symbol] as code.
        fun markdown(kdoc: String): String {
            val lines = kdoc.lines().map { it.trim().removePrefix("*").removePrefix(" ") }.dropWhile { it.isBlank() }
            val blocks = mutableListOf<MutableList<String>>()
            var fenced = false
            var paragraph = true
            for (line in lines.dropLastWhile { it.isBlank() }) {
                when {
                    line.startsWith("```") -> {
                        if (!fenced) blocks += mutableListOf<String>()
                        blocks.last() += line
                        fenced = !fenced
                        paragraph = false
                    }

                    fenced -> {
                        blocks.last() += line
                    }

                    line.isBlank() -> {
                        paragraph = false
                    }

                    paragraph && blocks.isNotEmpty() -> {
                        blocks.last()[0] += " $line"
                    }

                    else -> {
                        blocks += mutableListOf(line)
                        paragraph = true
                    }
                }
            }
            return blocks.joinToString("\n\n") { block ->
                if (block[0].startsWith("```")) block.joinToString("\n") else SYMBOL.replace(block[0], "`$1`")
            }
        }
    }
}
