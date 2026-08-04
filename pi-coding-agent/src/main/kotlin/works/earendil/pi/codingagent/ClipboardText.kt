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
                .withPiAgentEnvironment()
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

internal fun writeClipboardText(
    text: String,
    environment: Map<String, String> = System.getenv(),
    osName: String = System.getProperty("os.name").orEmpty(),
    commandRunner: (List<String>, String) -> Boolean = ::runClipboardWriteCommand,
): Boolean {
    val normalizedOs = osName.lowercase()
    val commands =
        when {
            normalizedOs.contains("mac") -> listOf(listOf("pbcopy"))
            normalizedOs.startsWith("windows") -> listOf(listOf("clip"))
            else ->
                buildList {
                    if (!environment["TERMUX_VERSION"].isNullOrBlank()) {
                        add(listOf("termux-clipboard-set"))
                    }
                    if (!environment["WAYLAND_DISPLAY"].isNullOrBlank()) {
                        add(listOf("wl-copy"))
                    }
                    if (!environment["DISPLAY"].isNullOrBlank()) {
                        add(listOf("xclip", "-selection", "clipboard"))
                        add(listOf("xsel", "--clipboard", "--input"))
                    }
                }
        }
    return commands.any { command -> commandRunner(command, text) }
}

private fun runClipboardWriteCommand(
    command: List<String>,
    text: String,
): Boolean =
    runCatching {
        val process =
            ProcessBuilder(command)
                .withPiAgentEnvironment()
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(text)
        }
        if (!process.waitFor(CLIPBOARD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    }.getOrDefault(false)

private const val MAX_CLIPBOARD_BYTES = 50 * 1024 * 1024
private const val CLIPBOARD_TIMEOUT_SECONDS = 5L
