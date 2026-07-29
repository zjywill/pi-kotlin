package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private data class JitiFixtureCase(
    val name: String,
    val entry: String,
)

fun main(args: Array<String>) {
    val fixtureRoot =
        Path
            .of(args.firstOrNull() ?: "migration/fixtures/extension-jiti-compat")
            .toAbsolutePath()
            .normalize()
    val cases =
        listOf(
            JitiFixtureCase("extensionless", "index.ts"),
            JitiFixtureCase("directory", "index.ts"),
            JitiFixtureCase("interop", "index.ts"),
            JitiFixtureCase("formats", "index.mts"),
            JitiFixtureCase("tsx", "index.tsx"),
            JitiFixtureCase("bare-package", "index.ts"),
            JitiFixtureCase("virtual", "index.ts"),
            JitiFixtureCase("commonjs", "index.cjs"),
            JitiFixtureCase("jsx", "index.tsx"),
        )
    val paths = cases.associate { fixture -> fixture.name to fixtureRoot.resolve(fixture.name).resolve(fixture.entry) }
    val agentDir = Files.createTempDirectory("pi-extension-jiti-oracle")
    val context =
        buildJsonObject {
            put("cwd", fixtureRoot.toString())
            put("mode", "print")
            put("hasUI", false)
            put("projectTrusted", true)
            put("thinkingLevel", "off")
            put("systemPrompt", "")
        }
    val diagnostics = mutableListOf<ExtensionDiagnostic>()
    val host =
        ExtensionHost.start(
            sources =
                paths.map { (_, path) ->
                    ExtensionSource(
                        path,
                        ResourceSourceInfo(path, "local", baseDir = path.parent),
                    )
                },
            agentDir = agentDir,
            cwd = fixtureRoot,
            mode = ExtensionMode.PRINT,
            projectTrusted = true,
            flagValues = emptyMap(),
            context = context,
            onDiagnostic = diagnostics::add,
        )
    val extensionsByCase =
        host
            ?.registrations
            ?.extensions
            .orEmpty()
            .associateBy { it.path.parent.fileName.toString() }
    val commandsByCase =
        host
            ?.registrations
            ?.commands
            .orEmpty()
            .associateBy { it.extensionPath.parent.fileName.toString() }
    val result =
        buildJsonObject {
            cases.forEach { fixture ->
                val loaded = fixture.name in extensionsByCase
                val command = commandsByCase[fixture.name]
                put(
                    fixture.name,
                    buildJsonObject {
                        put("loaded", loaded)
                        val description = command?.description
                        if (description == null) {
                            put("value", JsonNull)
                        } else {
                            put("value", description)
                        }
                    },
                )
            }
        }
    host?.close()
    println(Json.encodeToString(result))
}
