pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "pi-kotlin"

include(
    "pi-ai",
    "pi-agent-core",
    "pi-tui",
    "pi-storage-sqlite",
    "pi-coding-agent",
    "pi-server",
)
