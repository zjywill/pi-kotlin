package works.earendil.pi.codingagent

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

internal data class ParsedFrontmatter(
    val values: Map<String, Any?>,
    val body: String,
)

private val frontmatterYaml = Load(LoadSettings.builder().build())

internal fun parseFrontmatter(content: String): ParsedFrontmatter {
    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    if (!normalized.startsWith("---")) {
        return ParsedFrontmatter(emptyMap(), normalized)
    }
    val endIndex = normalized.indexOf("\n---", startIndex = 3)
    if (endIndex < 0) {
        return ParsedFrontmatter(emptyMap(), normalized)
    }
    val yamlText = normalized.substring(4, endIndex)
    val parsed = frontmatterYaml.loadFromString(yamlText)
    val values =
        when (parsed) {
            null -> emptyMap()
            is Map<*, *> ->
                parsed.entries.associate { (key, value) ->
                    require(key is String) { "Frontmatter keys must be strings" }
                    key to value
                }

            else -> error("Frontmatter must be a YAML object")
        }
    return ParsedFrontmatter(
        values = values,
        body = normalized.substring(endIndex + 4).trim(),
    )
}

internal fun stripFrontmatter(content: String): String = parseFrontmatter(content).body
