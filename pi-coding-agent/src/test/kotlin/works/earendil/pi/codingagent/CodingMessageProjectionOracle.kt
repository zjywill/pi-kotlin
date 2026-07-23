package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import works.earendil.pi.ai.Message

private val projectionOracleJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

fun main(args: Array<String>) {
    val fixture = Path.of(requireNotNull(args.firstOrNull()) { "Fixture path is required" })
    val messages =
        projectionOracleJson.decodeFromString(
            ListSerializer(Message.serializer()),
            Files.readString(fixture),
        )
    println(
        projectionOracleJson.encodeToString(
            ListSerializer(Message.serializer()),
            convertCodingMessagesToLlm(messages),
        ),
    )
}
