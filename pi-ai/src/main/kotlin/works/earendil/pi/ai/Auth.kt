package works.earendil.pi.ai

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface Credential

@Serializable
@SerialName("api_key")
data class ApiKeyCredential(
    val key: String? = null,
    val env: Map<String, String> = emptyMap(),
) : Credential

@Serializable
@SerialName("oauth")
data class OAuthCredential(
    val access: String,
    val refresh: String,
    val expires: Long,
    val scope: String? = null,
    val accountId: String? = null,
    val enterpriseUrl: String? = null,
    val availableModelIds: List<String>? = null,
    val gatewayConfig: JsonObject? = null,
) : Credential

@Serializable
data class CredentialInfo(
    val providerId: String,
    val type: String,
)

interface CredentialStore {
    suspend fun read(providerId: String): Credential?

    suspend fun list(): List<CredentialInfo>

    suspend fun modify(
        providerId: String,
        transform: suspend (Credential?) -> Credential?,
    ): Credential?

    suspend fun delete(providerId: String)
}

class InMemoryCredentialStore(
    initial: Map<String, Credential> = emptyMap(),
) : CredentialStore {
    private val credentials = ConcurrentHashMap(initial)
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    override suspend fun read(providerId: String): Credential? = credentials[providerId]

    override suspend fun list(): List<CredentialInfo> =
        credentials
            .map { (providerId, credential) ->
                CredentialInfo(providerId, credential.typeName())
            }.sortedBy(CredentialInfo::providerId)

    override suspend fun modify(
        providerId: String,
        transform: suspend (Credential?) -> Credential?,
    ): Credential? =
        mutexes.computeIfAbsent(providerId) { Mutex() }.withLock {
            val current = credentials[providerId]
            val next = transform(current)
            if (next != null) {
                credentials[providerId] = next
            }
            next ?: current
        }

    override suspend fun delete(providerId: String) {
        mutexes.computeIfAbsent(providerId) { Mutex() }.withLock {
            credentials.remove(providerId)
        }
    }
}

enum class AuthType {
    API_KEY,
    OAUTH,
}

data class ModelAuth(
    val apiKey: String? = null,
    val headers: Map<String, String?> = emptyMap(),
    val baseUrl: String? = null,
)

data class AuthResult(
    val auth: ModelAuth,
    val source: String,
    val env: Map<String, String> = emptyMap(),
)

data class AuthOption(
    val id: String,
    val label: String,
    val description: String? = null,
)

sealed interface AuthPrompt {
    val message: String

    data class Text(
        override val message: String,
        val placeholder: String? = null,
        val secret: Boolean = false,
    ) : AuthPrompt

    data class Select(
        override val message: String,
        val options: List<AuthOption>,
    ) : AuthPrompt

    data class ManualCode(
        override val message: String,
        val placeholder: String? = null,
    ) : AuthPrompt
}

sealed interface AuthEvent {
    data class Info(
        val message: String,
        val links: List<AuthInfoLink> = emptyList(),
    ) : AuthEvent

    data class AuthUrl(
        val url: String,
        val instructions: String? = null,
    ) : AuthEvent

    data class DeviceCode(
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Double? = null,
        val expiresInSeconds: Int? = null,
    ) : AuthEvent

    data class Progress(
        val message: String,
    ) : AuthEvent
}

data class AuthInfoLink(
    val url: String,
    val label: String? = null,
)

interface AuthInteraction {
    suspend fun prompt(prompt: AuthPrompt): String

    fun notify(event: AuthEvent)
}

interface OAuthAuth {
    val name: String

    val loginLabel: String?
        get() = null

    suspend fun login(interaction: AuthInteraction): OAuthCredential

    suspend fun refresh(credential: OAuthCredential): OAuthCredential

    suspend fun toAuth(credential: OAuthCredential): ModelAuth
}

class ModelsAuthException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

fun Credential.typeName(): String =
    when (this) {
        is ApiKeyCredential -> "api_key"
        is OAuthCredential -> "oauth"
    }
