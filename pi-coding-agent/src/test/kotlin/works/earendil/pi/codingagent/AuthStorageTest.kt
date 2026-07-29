package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.ApiKeyCredential
import works.earendil.pi.ai.OAuthCredential
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthStorageTest {
    @Test
    fun `persists canonical credentials with owner-only permissions`() =
        runTest {
            val directory = Files.createTempDirectory("pi-kotlin-auth-storage")
            try {
                val path = directory.resolve("auth.json")
                val store = JsonFileCredentialStore(path)
                val credential =
                    OAuthCredential(
                        access = "access-token",
                        refresh = "refresh-token",
                        expires = 123_456,
                        scope = "openid profile",
                        accountId = "account-id",
                        enterpriseUrl = "company.ghe.com",
                        availableModelIds = listOf("gpt-4.1", "claude-sonnet-4.6"),
                        gatewayConfig =
                            buildJsonObject {
                                put("baseUrl", "https://radius.example/v1")
                            },
                        extra =
                            buildJsonObject {
                                put("tenant", "team")
                                put(
                                    "env",
                                    buildJsonObject {
                                        put("TENANT_HEADER", "credential-header")
                                    },
                                )
                            },
                    )

                store.modify("openai-codex") { credential }

                assertEquals(credential, JsonFileCredentialStore(path).read("openai-codex"))
                assertTrue(path.readText().contains("\"type\": \"oauth\""))
                assertTrue(path.readText().contains("\"accountId\": \"account-id\""))
                assertTrue(path.readText().contains("\"enterpriseUrl\": \"company.ghe.com\""))
                assertTrue(path.readText().contains("\"availableModelIds\""))
                assertTrue(path.readText().contains("\"scope\": \"openid profile\""))
                assertTrue(path.readText().contains("\"gatewayConfig\""))
                assertTrue(path.readText().contains("\"tenant\": \"team\""))
                assertTrue(path.readText().contains("\"TENANT_HEADER\": \"credential-header\""))
                val stored =
                    Json
                        .parseToJsonElement(path.readText())
                        .jsonObject
                        .getValue("openai-codex")
                        .jsonObject
                assertFalse("extra" in stored)
                assertEquals("team", stored.getValue("tenant").jsonPrimitive.content)
                assertEquals(
                    "credential-header",
                    stored
                        .getValue("env")
                        .jsonObject
                        .getValue("TENANT_HEADER")
                        .jsonPrimitive
                        .content,
                )
                runCatching {
                    assertEquals(
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                        ),
                        Files.getPosixFilePermissions(path),
                    )
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `reads and rewrites TypeScript OAuth extension fields at the top level`() =
        runTest {
            val directory = Files.createTempDirectory("pi-kotlin-auth-extension-fields")
            try {
                val path = directory.resolve("auth.json")
                Files.writeString(
                    path,
                    """
                    {
                      "extension-oauth": {
                        "type": "oauth",
                        "access": "access-token",
                        "refresh": "refresh-token",
                        "expires": 123456,
                        "tenant": "team",
                        "env": { "TENANT_HEADER": "credential-header" },
                        "extra": "extension-owned-value"
                      }
                    }
                    """.trimIndent(),
                )
                val store = JsonFileCredentialStore(path)

                val credential = store.read("extension-oauth") as OAuthCredential
                assertEquals("team", credential.extra.getValue("tenant").jsonPrimitive.content)
                assertEquals(
                    "credential-header",
                    credential.extra
                        .getValue("env")
                        .jsonObject
                        .getValue("TENANT_HEADER")
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    "extension-owned-value",
                    credential.extra.getValue("extra").jsonPrimitive.content,
                )

                store.modify("extension-oauth") {
                    credential.copy(access = "updated-access")
                }

                val rewritten =
                    Json
                        .parseToJsonElement(path.readText())
                        .jsonObject
                        .getValue("extension-oauth")
                        .jsonObject
                assertEquals("updated-access", rewritten.getValue("access").jsonPrimitive.content)
                assertEquals("team", rewritten.getValue("tenant").jsonPrimitive.content)
                assertEquals(
                    "extension-owned-value",
                    rewritten.getValue("extra").jsonPrimitive.content,
                )
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `serializes concurrent stores and preserves unrelated providers`() =
        runTest {
            val directory = Files.createTempDirectory("pi-kotlin-auth-concurrent")
            try {
                val path = directory.resolve("auth.json")
                val stores = List(8) { JsonFileCredentialStore(path) }

                coroutineScope {
                    stores.mapIndexed { index, store ->
                        async {
                            store.modify("provider-$index") {
                                ApiKeyCredential("key-$index")
                            }
                        }
                    }.awaitAll()
                }

                assertEquals(
                    List(8) { "provider-$it" },
                    JsonFileCredentialStore(path).list().map { it.providerId },
                )
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `modify returning null preserves current credential and delete is scoped`() =
        runTest {
            val directory = Files.createTempDirectory("pi-kotlin-auth-delete")
            try {
                val path = directory.resolve("auth.json")
                val store = JsonFileCredentialStore(path)
                store.modify("one") { ApiKeyCredential("one") }
                store.modify("two") { ApiKeyCredential("two") }

                assertEquals(ApiKeyCredential("one"), store.modify("one") { null })
                store.delete("one")

                assertEquals(null, store.read("one"))
                assertEquals(ApiKeyCredential("two"), store.read("two"))
                assertFalse(path.readText().contains("\"one\""))
                assertTrue(path.readText().contains("\"two\""))
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `malformed auth file is not overwritten`() =
        runTest {
            val directory = Files.createTempDirectory("pi-kotlin-auth-malformed")
            try {
                val path = directory.resolve("auth.json")
                Files.writeString(path, "{invalid-json")
                val store = JsonFileCredentialStore(path)

                assertFails {
                    store.modify("openai-codex") {
                        ApiKeyCredential("new")
                    }
                }

                assertEquals("{invalid-json", path.readText())
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
}
