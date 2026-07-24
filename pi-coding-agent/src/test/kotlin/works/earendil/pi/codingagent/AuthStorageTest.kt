package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
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
                        accountId = "account-id",
                    )

                store.modify("openai-codex") { credential }

                assertEquals(credential, JsonFileCredentialStore(path).read("openai-codex"))
                assertTrue(path.readText().contains("\"type\": \"oauth\""))
                assertTrue(path.readText().contains("\"accountId\": \"account-id\""))
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
