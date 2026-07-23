package works.earendil.pi.server

import java.nio.file.Path

data class ServerConfig(
    val serverDir: Path = defaultServerDir(),
) {
    val instancesPath: Path = serverDir.resolve("instances.json")
    val socketPath: Path = serverDir.resolve("server.sock")

    companion object {
        private fun defaultServerDir(): Path {
            val explicit = System.getenv("PI_SERVER_DIR")
            if (!explicit.isNullOrBlank()) {
                return Path.of(explicit).toAbsolutePath().normalize()
            }
            val piRoot =
                System.getenv("PI_CONFIG_DIR")
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?: Path.of(System.getProperty("user.home"), ".pi")
            return piRoot.resolve("server").toAbsolutePath().normalize()
        }
    }
}
