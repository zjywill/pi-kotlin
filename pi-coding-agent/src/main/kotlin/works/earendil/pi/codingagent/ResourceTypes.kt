package works.earendil.pi.codingagent

import java.nio.file.Path

internal enum class ResourceDiagnosticType {
    WARNING,
    ERROR,
    COLLISION,
}

internal data class ResourceCollision(
    val resourceType: String,
    val name: String,
    val winnerPath: Path,
    val loserPath: Path,
)

internal data class ResourceDiagnostic(
    val type: ResourceDiagnosticType,
    val message: String,
    val path: Path? = null,
    val collision: ResourceCollision? = null,
)

internal data class ResourceSourceInfo(
    val path: Path,
    val source: String,
    val scope: String = "temporary",
    val origin: String = "top-level",
    val baseDir: Path? = null,
)

internal data class Skill(
    val name: String,
    val description: String,
    val filePath: Path,
    val baseDir: Path,
    val sourceInfo: ResourceSourceInfo,
    val disableModelInvocation: Boolean = false,
)

internal data class PromptTemplate(
    val name: String,
    val description: String,
    val argumentHint: String? = null,
    val content: String,
    val sourceInfo: ResourceSourceInfo,
    val filePath: Path,
)

internal data class LoadedSkills(
    val skills: List<Skill>,
    val diagnostics: List<ResourceDiagnostic>,
)

internal data class LoadedPromptTemplates(
    val prompts: List<PromptTemplate>,
    val diagnostics: List<ResourceDiagnostic>,
)
