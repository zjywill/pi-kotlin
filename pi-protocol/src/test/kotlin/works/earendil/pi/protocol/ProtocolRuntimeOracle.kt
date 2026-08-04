package works.earendil.pi.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    val clientHello =
        buildJsonObject {
            put("type", "hello")
            put("version", PROTOCOL_VERSION)
        }
    val request =
        buildJsonObject {
            put("type", "request")
            put("id", "request-1")
            put("request", buildJsonObject { put("command", "list") })
        }
    val serverHello =
        buildJsonObject {
            put("type", "hello")
            put("version", PROTOCOL_VERSION)
            put("connectionId", "connection-1")
            put(
                "snapshot",
                buildJsonObject {
                    put("serverId", "server-1")
                    put("protocolVersion", PROTOCOL_VERSION)
                    put("revision", 0)
                    put("sessions", JsonArray(emptyList()))
                    put("models", JsonArray(emptyList()))
                },
            )
        }
    val wire = encodeClientMessage(clientHello) + encodeClientMessage(request)
    val decoder = ClientMessageDecoder()
    val decoded =
        buildList {
            wire.forEach { byte ->
                addAll(decoder.push(byteArrayOf(byte)))
            }
            decoder.end()
        }
    val cyclic = linkedMapOf<String, Any?>()
    cyclic["self"] = cyclic
    val byteRoundTrip = decodeCbor(encodeCbor(byteArrayOf(0, 1, -1))) as ByteArray

    println(
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("version", PROTOCOL_VERSION)
                put(
                    "cbor",
                    buildJsonObject {
                        put("null", encodeCbor(null).hex())
                        put("false", encodeCbor(false).hex())
                        put("true", encodeCbor(true).hex())
                        put(
                            "integers",
                            jsonStrings(
                                listOf(
                                    -9_007_199_254_740_991L,
                                    -24,
                                    -1,
                                    0,
                                    23,
                                    24,
                                    255,
                                    256,
                                    9_007_199_254_740_991L,
                                ).map { encodeCbor(it).hex() },
                            ),
                        )
                        put(
                            "floats",
                            jsonStrings(
                                listOf(
                                    encodeCbor(-0.0).hex(),
                                    encodeCbor(1.5).hex(),
                                    encodeCbor(Double.MIN_VALUE).hex(),
                                ),
                            ),
                        )
                        put("text", encodeCbor("hello \u4e16\u754c").hex())
                        put("bytes", encodeCbor(byteArrayOf(0, 1, -1)).hex())
                        put("array", encodeCbor(listOf(1, "two", true, null)).hex())
                        put(
                            "object",
                            encodeCbor(
                                linkedMapOf(
                                    "alpha" to 1,
                                    "beta" to listOf("two", false),
                                ),
                            ).hex(),
                        )
                        put(
                            "byteRoundTrip",
                            buildJsonArray {
                                byteRoundTrip.forEach { add(JsonPrimitive(it.toInt() and 0xff)) }
                            },
                        )
                    },
                )
                put(
                    "protocol",
                    buildJsonObject {
                        put("clientHello", encodeClientMessage(clientHello).hex())
                        put("request", encodeClientMessage(request).hex())
                        put("serverHello", encodeServerMessage(serverHello).hex())
                        put("incrementalDecoded", JsonArray(decoded))
                        put(
                            "supportedVersions",
                            buildJsonArray {
                                listOf<Number>(1, 2, 2.5, Double.NaN).forEach {
                                    add(JsonPrimitive(isSupportedProtocolVersion(it)))
                                }
                            },
                        )
                    },
                )
                put(
                    "rejections",
                    buildJsonObject {
                        put("cycle", rejects { encodeCbor(cyclic) })
                        put("trailingCbor", rejects { decodeCbor(byteArrayOf(0xf6.toByte(), 0xf6.toByte())) })
                        put(
                            "credentialField",
                            rejects {
                                parseClientMessage(
                                    JsonObject(clientHello + ("token" to JsonPrimitive("secret"))),
                                )
                            },
                        )
                        put(
                            "extraHelloField",
                            rejects {
                                parseClientMessage(
                                    JsonObject(clientHello + ("extra" to JsonPrimitive(true))),
                                )
                            },
                        )
                        put(
                            "shortFrameLimit",
                            rejects { encodeClientMessage(clientHello, maxFrameLength = 8) },
                        )
                    },
                )
            },
        ),
    )
}

private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun jsonStrings(values: List<String>): JsonArray =
    JsonArray(values.map(::JsonPrimitive))

private fun rejects(operation: () -> Any?): Boolean =
    runCatching(operation).isFailure
