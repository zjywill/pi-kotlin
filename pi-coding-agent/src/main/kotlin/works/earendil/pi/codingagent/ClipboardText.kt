package works.earendil.pi.codingagent

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal fun readClipboardText(
    environment: Map<String, String> = System.getenv(),
    osName: String = System.getProperty("os.name").orEmpty(),
    commandRunner: (List<String>) -> String? = ::runClipboardCommand,
): String? {
    val normalizedOs = osName.lowercase()
    val commands =
        when {
            normalizedOs.contains("mac") -> listOf(listOf("pbpaste"))
            normalizedOs.contains("win") ->
                listOf(
                    listOf(
                        "powershell",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        "Get-Clipboard -Raw",
                    ),
                )

            else ->
                buildList {
                    if (!environment["WAYLAND_DISPLAY"].isNullOrBlank()) {
                        add(listOf("wl-paste", "--no-newline", "--type", "text"))
                    }
                    add(listOf("xclip", "-selection", "clipboard", "-o"))
                    add(listOf("xsel", "--clipboard", "--output"))
                }
        }
    commands.forEach { command ->
        val text = commandRunner(command)
        if (text != null) {
            return text.takeIf(String::isNotEmpty)
        }
    }
    return null
}

private fun runClipboardCommand(command: List<String>): String? =
    runCatching {
        val process =
            ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        val output = ByteArrayOutputStream()
        val reader =
            Thread {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8_192)
                    var remaining = MAX_CLIPBOARD_BYTES
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        if (!process.waitFor(CLIPBOARD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        reader.join(1_000)
        if (process.exitValue() == 0) output.toString(Charsets.UTF_8) else null
    }.getOrNull()

private const val MAX_CLIPBOARD_BYTES = 50 * 1024 * 1024
private const val CLIPBOARD_TIMEOUT_SECONDS = 5L
