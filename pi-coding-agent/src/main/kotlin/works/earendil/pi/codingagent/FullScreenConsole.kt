package works.earendil.pi.codingagent

import java.io.Closeable
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.jline.terminal.Attributes
import org.jline.terminal.Terminal.Signal
import org.jline.terminal.Terminal.SignalHandler
import org.jline.terminal.Terminal as JLineTerminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader
import works.earendil.pi.tui.AutocompleteProvider
import works.earendil.pi.tui.Component
import works.earendil.pi.tui.Editor
import works.earendil.pi.tui.EditorOptions
import works.earendil.pi.tui.EditorTheme
import works.earendil.pi.tui.Focusable
import works.earendil.pi.tui.InputListenerResult
import works.earendil.pi.tui.Key
import works.earendil.pi.tui.KeybindingsManager
import works.earendil.pi.tui.OverlayAnchor
import works.earendil.pi.tui.OverlayHandle
import works.earendil.pi.tui.OverlayMargin
import works.earendil.pi.tui.OverlayOptions
import works.earendil.pi.tui.ScrollViewScrollbar
import works.earendil.pi.tui.SizeValue
import works.earendil.pi.tui.Terminal
import works.earendil.pi.tui.Tui
import works.earendil.pi.tui.TUI_KEYBINDINGS
import works.earendil.pi.tui.TranscriptSearchBar
import works.earendil.pi.tui.TuiScreenMode
import works.earendil.pi.tui.ViewportLayout
import works.earendil.pi.tui.matchesKey
import works.earendil.pi.tui.setKittyProtocolActive
import works.earendil.pi.tui.truncateToWidth
import works.earendil.pi.tui.wrapTextWithAnsi

internal interface FullScreenConsoleControl {
    fun setAutocompleteProvider(provider: AutocompleteProvider?)

    fun setTitle(title: String)

    fun currentTuiMode(): TuiMode = TuiMode.REGULAR

    fun switchTuiMode(mode: TuiMode): Boolean = false

    fun copyTextToClipboard(text: String): Boolean = false

    fun appendToolResult(
        collapsed: List<String>,
        expanded: List<String>,
    ) = Unit

    fun toolsExpanded(): Boolean = false

    fun toolExpandKey(): String? = null

    fun flash(message: String) = Unit

    fun setScrollbarStyle(style: (String) -> String) = Unit

    fun setSearchStyles(
        match: (String) -> String,
        current: (String) -> String,
    ) = Unit

    fun setHeader(lines: List<String>?) = Unit

    fun setFooter(lines: List<String>?) = Unit

    fun setWidget(
        key: String,
        lines: List<String>?,
        placement: String,
    ) = Unit

    fun readExtensionCustom(
        request: JsonObject,
        cancellation: ExtensionUiCancellation,
    ): JsonObject = buildJsonObject { put("cancelled", true) }

    fun closeExtensionCustom(componentId: String) = Unit

    fun setTerminalInputHandler(
        listenerId: String,
        handler: ((String) -> InputListenerResult?)?,
    ) = Unit

    fun setEditorComponent(
        componentId: String?,
        lines: List<String> = emptyList(),
        text: String? = null,
        bridge: ((operation: String, data: String?, text: String?) -> JsonObject?)? = null,
    ) = Unit

    fun setEditorText(
        text: String,
        paste: Boolean = false,
    ) = Unit

    fun setStreamingText(text: String?) = Unit

    fun commitStreamingText() = Unit

    fun controlExtensionCustom(
        componentId: String,
        operation: String,
        hidden: Boolean? = null,
        targetNull: Boolean = false,
    ) = Unit
}

internal class FullScreenConsole(
    private val terminalAdapter: Terminal = JLineTuiTerminal(),
    private val closeTerminal: Closeable? = terminalAdapter as? Closeable,
    tuiMode: TuiMode = TuiMode.REGULAR,
    fullscreenScrollbar: ScrollViewScrollbar = ScrollViewScrollbar.AUTO,
    autocompleteMaxVisible: Int = 5,
    private val keybindings: KeybindingsManager = KeybindingsManager(TUI_KEYBINDINGS),
    private val toolExpandKeys: List<String> = DEFAULT_TOOL_EXPAND_KEYS,
    private val clipboardTextReader: () -> String? = ::readClipboardText,
    private val clipboardTextWriter: (String) -> Boolean = ::writeClipboardText,
) : InteractiveConsole,
    FullScreenConsoleControl {
    private val header = MutableLinesComponent()
    private val transcript = TranscriptComponent()
    private val widgetsAbove = SurfaceCollectionComponent()
    private val searchBar = TranscriptSearchBar()
    private val prompt = PromptComponent()
    private val document = works.earendil.pi.tui.Container()
    private val dock = works.earendil.pi.tui.Container()
    private val viewport =
        ViewportLayout(
            document = document,
            dock = dock,
            scrollbar = fullscreenScrollbar,
            searchBar = searchBar,
        )
    @Volatile
    private var tuiMode = tuiMode
    private val tui =
        Tui(
            terminal = terminalAdapter,
            screenMode =
                if (tuiMode == TuiMode.FULLSCREEN) {
                    TuiScreenMode.ALTERNATE
                } else {
                    TuiScreenMode.MAIN
                },
            copySelectionToClipboard = clipboardTextWriter,
        )
    private val editor =
        Editor(
            tui = tui,
            theme = EditorTheme(),
            options =
                EditorOptions(
                    paddingX = 1,
                    autocompleteMaxVisible = autocompleteMaxVisible,
                ),
            keybindings = keybindings,
        )
    private val editorSlot = ComponentSlot(editor)
    private val widgetsBelow = SurfaceCollectionComponent()
    private val footer = MutableLinesComponent()
    private val reads = LinkedBlockingQueue<ConsoleRead>()
    private val closed = AtomicBoolean(false)
    private val activeShortcuts = linkedMapOf<String, String>()
    private val customSurfaces = linkedMapOf<String, CustomSurface>()
    private val pendingCustomControls = linkedMapOf<String, MutableList<CustomSurfaceControl>>()
    private val terminalInputHandlers = linkedMapOf<String, (String) -> InputListenerResult?>()
    @Volatile
    private var remoteEditor: RemoteEditorComponent? = null
    @Volatile
    private var reading = false
    @Volatile
    private var toolsExpanded = false

    init {
        document.addChild(header)
        document.addChild(transcript)
        dock.addChild(widgetsAbove)
        dock.addChild(searchBar)
        dock.addChild(prompt)
        dock.addChild(editorSlot)
        dock.addChild(widgetsBelow)
        dock.addChild(footer)
        mountTuiMode(tuiMode)
        tui.setFocus(editor)
        editor.onSubmit = { value ->
            if (reading) {
                editor.addToHistory(value)
                reads.offer(ConsoleRead.Line(value))
            }
        }
        tui.addInputListener(::handleExtensionInput)
        tui.addInputListener(::handleGlobalInput)
        tui.start()
    }

    override fun readLine(prompt: String): String? =
        readLineInternal(prompt, initialBuffer = "", secret = false, cancellation = null).let { result ->
            (result as? InteractiveReadResult.Line)?.value
        }

    override fun readSecret(prompt: String): String? =
        readLineInternal(prompt, initialBuffer = "", secret = true, cancellation = null).let { result ->
            (result as? InteractiveReadResult.Line)?.value
        }

    override fun readLine(
        prompt: String,
        cancellation: ExtensionUiCancellation,
    ): String? =
        readLineInternal(prompt, initialBuffer = "", secret = false, cancellation = cancellation).let { result ->
            (result as? InteractiveReadResult.Line)?.value
        }

    override fun readLineWithShortcuts(
        prompt: String,
        shortcuts: List<InteractiveShortcutBinding>,
        initialBuffer: String,
    ): InteractiveReadResult {
        synchronized(activeShortcuts) {
            activeShortcuts.clear()
            shortcuts.forEach { shortcut ->
                activeShortcuts[shortcut.id] = shortcut.key
            }
        }
        return try {
            readLineInternal(prompt, initialBuffer, secret = false, cancellation = null)
        } finally {
            synchronized(activeShortcuts) {
                activeShortcuts.clear()
            }
        }
    }

    private fun readLineInternal(
        prompt: String,
        initialBuffer: String,
        secret: Boolean,
        cancellation: ExtensionUiCancellation?,
    ): InteractiveReadResult {
        if (closed.get() || cancellation?.isCancelled == true) {
            return InteractiveReadResult.Line(null)
        }
        reading = true
        this.prompt.text = prompt
        val editorBeforeSecret = editorSlot.component
        if (secret) {
            editorSlot.component = editor
            tui.setFocus(editor)
            editor.maskCharacter = "*"
            editor.setText(initialBuffer)
        } else {
            editor.maskCharacter = null
            setPrimaryEditorText(initialBuffer)
            focusPrimaryEditor()
        }
        tui.requestRender()
        val registration =
            cancellation?.onCancellation {
                reads.offer(ConsoleRead.End)
            }
        return try {
            val result =
                when (val value = reads.take()) {
                is ConsoleRead.Line -> InteractiveReadResult.Line(value.value)

                is ConsoleRead.Shortcut -> InteractiveReadResult.Shortcut(value.id, value.buffer)

                ConsoleRead.End -> InteractiveReadResult.Line(null)
            }
            val line = (result as? InteractiveReadResult.Line)?.value
            if (!secret && line != null && prompt.contains("> ")) {
                transcript.append(OSC133_ZONE_START + prompt + line + OSC133_ZONE_END + OSC133_ZONE_FINAL)
                transcript.newLine()
            }
            result
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            InteractiveReadResult.Line(null)
        } finally {
            registration?.close()
            reading = false
            this.prompt.text = ""
            if (secret) {
                editor.setText("")
                editor.maskCharacter = null
                editorSlot.component = editorBeforeSecret
                tui.setFocus(editorBeforeSecret)
            } else {
                editor.maskCharacter = null
                setPrimaryEditorText("")
            }
            tui.requestRender()
        }
    }

    private fun handleGlobalInput(data: String): InputListenerResult? {
        if (toolExpandKeys.any { key -> matchesKey(data, key) }) {
            toolsExpanded = !toolsExpanded
            transcript.setToolsExpanded(toolsExpanded)
            tui.requestRender()
            return InputListenerResult(consume = true)
        }
        if (!reading) {
            return null
        }
        if (matchesKey(data, clipboardPasteKey())) {
            runCatching(clipboardTextReader)
                .getOrNull()
                ?.let { text -> setEditorText(text, paste = true) }
            return InputListenerResult(consume = true)
        }
        if (matchesKey(data, Key.ctrl("d")) && primaryEditorText().isEmpty()) {
            reads.offer(ConsoleRead.End)
            return InputListenerResult(consume = true)
        }
        if (
            remoteEditor == null &&
            (
                keybindings.matches(data, "tui.editor.historyPrevious") ||
                    keybindings.matches(data, "tui.editor.historyNext")
            )
        ) {
            return null
        }
        val shortcut =
            synchronized(activeShortcuts) {
                activeShortcuts.entries.firstOrNull { (_, key) -> matchesKey(data, key) }
        }
        if (shortcut != null) {
            reads.offer(ConsoleRead.Shortcut(shortcut.key, primaryEditorText()))
            return InputListenerResult(consume = true)
        }
        return null
    }

    override fun print(text: String) {
        transcript.append(text)
        tui.requestRender()
    }

    override fun println(text: String) {
        transcript.append(text)
        transcript.newLine()
        tui.requestRender()
    }

    override fun printlnAbove(text: String) {
        println(text)
    }

    override fun error(text: String) {
        println("Error: $text")
    }

    override fun width(): Int = terminalAdapter.columns.coerceAtLeast(1)

    override fun supportsAnsi(): Boolean = true

    override fun setAutocompleteProvider(provider: AutocompleteProvider?) {
        editor.setAutocompleteProvider(provider)
    }

    override fun setTitle(title: String) {
        terminalAdapter.setTitle(title)
    }

    override fun currentTuiMode(): TuiMode = tuiMode

    override fun switchTuiMode(mode: TuiMode): Boolean {
        if (mode == tuiMode) {
            return true
        }
        val screenMode =
            if (mode == TuiMode.FULLSCREEN) {
                TuiScreenMode.ALTERNATE
            } else {
                TuiScreenMode.MAIN
            }
        if (!tui.switchScreenMode(screenMode)) {
            return false
        }
        tuiMode = mode
        mountTuiMode(mode)
        tui.requestRender()
        return true
    }

    override fun copyTextToClipboard(text: String): Boolean {
        if (clipboardTextWriter(text)) {
            return true
        }
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        if (encoded.length > MAX_OSC52_ENCODED_LENGTH) {
            return false
        }
        terminalAdapter.write("\u001B]52;c;$encoded\u0007")
        return true
    }

    override fun appendToolResult(
        collapsed: List<String>,
        expanded: List<String>,
    ) {
        transcript.appendCollapsible(collapsed, expanded)
        tui.requestRender()
    }

    override fun toolsExpanded(): Boolean = toolsExpanded

    override fun toolExpandKey(): String? = toolExpandKeys.firstOrNull()

    override fun flash(message: String) {
        tui.flash(message)
    }

    override fun setScrollbarStyle(style: (String) -> String) {
        viewport.setScrollbarStyle(style)
    }

    override fun setSearchStyles(
        match: (String) -> String,
        current: (String) -> String,
    ) {
        viewport.setSearchStyles(match, current)
    }

    override fun setHeader(lines: List<String>?) {
        header.setLines(lines.orEmpty())
        tui.requestRender()
    }

    override fun setFooter(lines: List<String>?) {
        footer.setLines(lines.orEmpty())
        tui.requestRender()
    }

    override fun setWidget(
        key: String,
        lines: List<String>?,
        placement: String,
    ) {
        val target = if (placement == "belowEditor") widgetsBelow else widgetsAbove
        val other = if (placement == "belowEditor") widgetsAbove else widgetsBelow
        other.setLines(key, null)
        target.setLines(key, lines)
        tui.requestRender()
    }

    override fun readExtensionCustom(
        request: JsonObject,
        cancellation: ExtensionUiCancellation,
    ): JsonObject {
        val componentId = request.string("componentId")
            ?: return buildJsonObject { put("cancelled", true) }
        val lines = request.stringLines("lines").orEmpty()
        val overlay = request["overlay"]?.let { value -> (value as? JsonPrimitive)?.booleanOrNull } == true
        val options = request["overlayOptions"]?.let { value -> value as? JsonObject }?.toOverlayOptions()
        val surface =
            synchronized(customSurfaces) {
                val existing = customSurfaces[componentId]
                val active =
                    if (existing == null || existing.overlay != overlay) {
                        existing?.close()
                        createCustomSurface(componentId, lines, overlay, options)
                            .also { created -> customSurfaces[componentId] = created }
                    } else {
                        existing.component.setLines(lines)
                        if (overlay && existing.options != options) {
                            existing.handle?.hide()
                            existing.handle =
                                tui.showOverlay(
                                    existing.component,
                                    options ?: OverlayOptions(),
                                    renderImmediately = false,
                                )
                            existing.options = options
                        }
                        existing
                    }
                pendingCustomControls.remove(componentId).orEmpty().forEach { control ->
                    applyCustomControl(active, control)
                }
                active
            }
        tui.requestRender()
        val registration =
            cancellation.onCancellation {
                surface.inputs.offer(CustomSurfaceInput.Cancelled)
            }
        return try {
            when (val input = surface.inputs.take()) {
                is CustomSurfaceInput.Data ->
                    buildJsonObject {
                        put("input", input.value)
                    }

                CustomSurfaceInput.Cancelled ->
                    buildJsonObject {
                        put("cancelled", true)
                    }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            buildJsonObject { put("cancelled", true) }
        } finally {
            registration.close()
        }
    }

    override fun closeExtensionCustom(componentId: String) {
        val surface =
            synchronized(customSurfaces) {
                customSurfaces.remove(componentId)
            } ?: return
        surface.close()
        tui.requestRender()
    }

    override fun controlExtensionCustom(
        componentId: String,
        operation: String,
        hidden: Boolean?,
        targetNull: Boolean,
    ) {
        val control = CustomSurfaceControl(operation, hidden, targetNull)
        val applied =
            synchronized(customSurfaces) {
                val surface = customSurfaces[componentId]
                if (surface == null) {
                    pendingCustomControls
                        .getOrPut(componentId, ::mutableListOf)
                        .add(control)
                    false
                } else {
                    applyCustomControl(surface, control)
                    true
                }
            }
        if (applied) {
            tui.requestRender()
        }
    }

    private fun applyCustomControl(
        surface: CustomSurface,
        control: CustomSurfaceControl,
    ) {
        when (control.operation) {
            "hide" -> closeExtensionCustom(surface.component.componentId)
            "setHidden" -> surface.handle?.setHidden(control.hidden == true)
            "focus" -> surface.handle?.focus()
            "unfocus" ->
                if (control.targetNull) {
                    surface.handle?.unfocus(null)
                } else {
                    surface.handle?.unfocus()
                }
        }
    }

    override fun setTerminalInputHandler(
        listenerId: String,
        handler: ((String) -> InputListenerResult?)?,
    ) {
        synchronized(terminalInputHandlers) {
            if (handler == null) {
                terminalInputHandlers.remove(listenerId)
            } else {
                terminalInputHandlers[listenerId] = handler
            }
        }
    }

    override fun setEditorComponent(
        componentId: String?,
        lines: List<String>,
        text: String?,
        bridge: ((operation: String, data: String?, text: String?) -> JsonObject?)?,
    ) {
        if (componentId == null || bridge == null) {
            val restoredText = text ?: remoteEditor?.text.orEmpty()
            remoteEditor = null
            editor.setText(restoredText)
            restorePrimaryEditor()
            tui.requestRender()
            return
        }
        val existing = remoteEditor
        val remote =
            if (existing?.componentId == componentId) {
                existing.apply {
                    update(lines, text)
                    this.bridge = bridge
                }
            } else {
                RemoteEditorComponent(componentId, lines, text.orEmpty(), bridge).also { created ->
                    created.onSubmit = { value ->
                        if (reading) {
                            reads.offer(ConsoleRead.Line(value))
                        }
                    }
                }
            }
        remoteEditor = remote
        restorePrimaryEditor()
        tui.requestRender()
    }

    override fun setEditorText(
        text: String,
        paste: Boolean,
    ) {
        val remote = remoteEditor
        if (remote != null) {
            if (paste) {
                remote.handleInput("\u001B[200~$text\u001B[201~")
            } else {
                remote.setText(text)
            }
        } else if (paste) {
            editor.insertTextAtCursor(text)
        } else {
            editor.setText(text)
        }
        tui.requestRender()
    }

    override fun setStreamingText(text: String?) {
        transcript.setTransient(text)
        tui.requestRender()
    }

    override fun commitStreamingText() {
        transcript.commitTransient()
        tui.requestRender()
    }

    private fun handleExtensionInput(initialData: String): InputListenerResult? {
        var data = initialData
        val handlers = synchronized(terminalInputHandlers) { terminalInputHandlers.values.toList() }
        handlers.forEach { handler ->
            val result = handler(data)
            if (result?.consume == true) {
                return InputListenerResult(consume = true)
            }
            result?.data?.let { replacement ->
                data = replacement
            }
            if (data.isEmpty()) {
                return InputListenerResult(consume = true)
            }
        }
        return if (data == initialData) null else InputListenerResult(data = data)
    }

    private fun primaryEditor(): Component = remoteEditor ?: editor

    private fun mountTuiMode(mode: TuiMode) {
        tui.clear()
        if (mode == TuiMode.FULLSCREEN) {
            tui.addChild(viewport)
        } else {
            tui.addChild(header)
            tui.addChild(transcript)
            tui.addChild(widgetsAbove)
            tui.addChild(prompt)
            tui.addChild(editorSlot)
            tui.addChild(widgetsBelow)
            tui.addChild(footer)
        }
    }

    private fun primaryEditorText(): String = remoteEditor?.text ?: editor.getExpandedText()

    private fun setPrimaryEditorText(text: String) {
        remoteEditor?.setText(text) ?: editor.setText(text)
    }

    private fun focusPrimaryEditor() {
        if (customSurfaces.values.none { surface -> !surface.overlay }) {
            val primary = primaryEditor()
            editorSlot.component = primary
            tui.setFocus(primary)
        }
    }

    private fun restorePrimaryEditor() {
        synchronized(customSurfaces) {
            if (customSurfaces.values.none { surface -> !surface.overlay }) {
                val primary = primaryEditor()
                editorSlot.component = primary
                tui.setFocus(primary)
            }
        }
    }

    private fun createCustomSurface(
        componentId: String,
        lines: List<String>,
        overlay: Boolean,
        options: OverlayOptions?,
    ): CustomSurface {
        val component = RemoteCustomComponent(componentId, lines)
        val surface =
            CustomSurface(
                component = component,
                overlay = overlay,
                options = options,
            )
        component.onInput = { data ->
            surface.inputs.offer(CustomSurfaceInput.Data(data))
        }
        if (overlay) {
            surface.handle =
                tui.showOverlay(
                    component,
                    options ?: OverlayOptions(),
                    renderImmediately = false,
                )
        } else {
            editorSlot.component = component
            tui.setFocus(component)
        }
        return surface
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        reads.offer(ConsoleRead.End)
        synchronized(customSurfaces) {
            customSurfaces.values.toList().forEach(CustomSurface::close)
            customSurfaces.clear()
            pendingCustomControls.clear()
        }
        tui.stop()
        closeTerminal?.close()
    }

    private inner class CustomSurface(
        val component: RemoteCustomComponent,
        val overlay: Boolean,
        var options: OverlayOptions?,
        var handle: OverlayHandle? = null,
        val inputs: LinkedBlockingQueue<CustomSurfaceInput> = LinkedBlockingQueue(),
    ) {
        fun close() {
            inputs.offer(CustomSurfaceInput.Cancelled)
            if (overlay) {
                handle?.hide()
            } else if (editorSlot.component === component) {
                val primary = primaryEditor()
                editorSlot.component = primary
                tui.setFocus(primary)
            }
        }
    }
}

private fun clipboardPasteKey(): String =
    if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
        "alt+v"
    } else {
        "ctrl+v"
    }

private const val MAX_OSC52_ENCODED_LENGTH = 100_000

private sealed interface ConsoleRead {
    data class Line(
        val value: String,
    ) : ConsoleRead

    data class Shortcut(
        val id: String,
        val buffer: String,
    ) : ConsoleRead

    data object End : ConsoleRead
}

private sealed interface CustomSurfaceInput {
    data class Data(
        val value: String,
    ) : CustomSurfaceInput

    data object Cancelled : CustomSurfaceInput
}

private data class CustomSurfaceControl(
    val operation: String,
    val hidden: Boolean?,
    val targetNull: Boolean,
)

private class ComponentSlot(
    @Volatile
    var component: Component,
) : Component {
    override fun render(width: Int): List<String> = component.render(width)

    override fun invalidate() {
        component.invalidate()
    }
}

private class MutableLinesComponent : Component {
    private var lines: List<String> = emptyList()

    @Synchronized
    fun setLines(value: List<String>) {
        lines = value.toList()
    }

    @Synchronized
    override fun render(width: Int): List<String> =
        lines.flatMap { line ->
            wrapTextWithAnsi(line, width.coerceAtLeast(1))
        }
}

private class SurfaceCollectionComponent : Component {
    private val surfaces = linkedMapOf<String, List<String>>()

    @Synchronized
    fun setLines(
        key: String,
        lines: List<String>?,
    ) {
        if (lines == null) {
            surfaces.remove(key)
        } else {
            surfaces[key] = lines.toList()
        }
    }

    @Synchronized
    override fun render(width: Int): List<String> =
        surfaces.values.flatten().flatMap { line ->
            wrapTextWithAnsi(line, width.coerceAtLeast(1))
        }
}

private class RemoteCustomComponent(
    val componentId: String,
    lines: List<String>,
) : Component,
    Focusable {
    @Volatile
    override var focused: Boolean = false
    @Volatile
    var onInput: ((String) -> Unit)? = null
    private var lines: List<String> = lines

    @Synchronized
    fun setLines(value: List<String>) {
        lines = value.toList()
    }

    @Synchronized
    override fun render(width: Int): List<String> =
        lines.flatMap { line ->
            wrapTextWithAnsi(line, width.coerceAtLeast(1))
        }

    override fun handleInput(data: String) {
        onInput?.invoke(data)
    }

    override fun toString(): String = "RemoteCustomComponent($componentId)"
}

private class RemoteEditorComponent(
    val componentId: String,
    lines: List<String>,
    text: String,
    bridge: (operation: String, data: String?, text: String?) -> JsonObject?,
) : Component,
    Focusable {
    @Volatile
    override var focused: Boolean = false
    @Volatile
    var text: String = text
        private set
    @Volatile
    var bridge: (operation: String, data: String?, text: String?) -> JsonObject? = bridge
    @Volatile
    var onSubmit: ((String) -> Unit)? = null
    private var lines: List<String> = lines

    fun setText(value: String) {
        applyResponse(bridge("set_text", null, value))
    }

    @Synchronized
    fun update(
        lines: List<String>,
        text: String?,
    ) {
        this.lines = lines.toList()
        text?.let { value -> this.text = value }
    }

    override fun handleInput(data: String) {
        applyResponse(bridge("input", data, null))
    }

    @Synchronized
    private fun applyResponse(response: JsonObject?) {
        response ?: return
        response.string("error")?.let { return }
        response.stringLines("lines")?.let { value -> lines = value }
        response.string("text")?.let { value -> text = value }
        response.string("submitted")?.let { value -> onSubmit?.invoke(value) }
    }

    @Synchronized
    override fun render(width: Int): List<String> =
        lines.flatMap { line ->
            wrapTextWithAnsi(line, width.coerceAtLeast(1))
        }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringLines(name: String): List<String>? =
    this[name]
        ?.let { value -> value as? kotlinx.serialization.json.JsonArray }
        ?.mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.toOverlayOptions(): OverlayOptions =
    OverlayOptions(
        width = sizeValue("width"),
        minWidth = intValue("minWidth"),
        maxHeight = sizeValue("maxHeight"),
        anchor =
            string("anchor")
                ?.let(::overlayAnchor)
                ?: OverlayAnchor.CENTER,
        offsetX = intValue("offsetX") ?: 0,
        offsetY = intValue("offsetY") ?: 0,
        row = sizeValue("row"),
        col = sizeValue("col"),
        margin = marginValue(),
        nonCapturing = booleanValue("nonCapturing") ?: false,
    )

private fun JsonObject.sizeValue(name: String): SizeValue? {
    val value = this[name] as? JsonPrimitive ?: return null
    value.intOrNull?.let { return SizeValue.Absolute(it) }
    value.doubleOrNull?.let { number ->
        if (number % 1.0 == 0.0) {
            return SizeValue.Absolute(number.toInt())
        }
    }
    val text = value.contentOrNull ?: return null
    return text
        .removeSuffix("%")
        .toDoubleOrNull()
        ?.takeIf { text.endsWith("%") }
        ?.let(SizeValue::Percent)
}

private fun JsonObject.intValue(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.booleanValue(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.marginValue(): OverlayMargin {
    val value = this["margin"] ?: return OverlayMargin()
    (value as? JsonPrimitive)?.intOrNull?.let { return OverlayMargin(it) }
    val margin = value as? JsonObject ?: return OverlayMargin()
    return OverlayMargin(
        top = margin.intValue("top") ?: 0,
        right = margin.intValue("right") ?: 0,
        bottom = margin.intValue("bottom") ?: 0,
        left = margin.intValue("left") ?: 0,
    )
}

private fun overlayAnchor(value: String): OverlayAnchor =
    when (value) {
        "top-left" -> OverlayAnchor.TOP_LEFT
        "top-right" -> OverlayAnchor.TOP_RIGHT
        "bottom-left" -> OverlayAnchor.BOTTOM_LEFT
        "bottom-right" -> OverlayAnchor.BOTTOM_RIGHT
        "top-center" -> OverlayAnchor.TOP_CENTER
        "bottom-center" -> OverlayAnchor.BOTTOM_CENTER
        "left-center" -> OverlayAnchor.LEFT_CENTER
        "right-center" -> OverlayAnchor.RIGHT_CENTER
        else -> OverlayAnchor.CENTER
    }

private class TranscriptComponent : Component {
    private val entries = mutableListOf<TranscriptEntry>(TranscriptEntry.Text(""))
    private var transient: String? = null
    private var toolsExpanded = false

    @Synchronized
    fun append(value: String) {
        val parts = value.split('\n')
        val line = entries.lastOrNull() as? TranscriptEntry.Text
            ?: TranscriptEntry.Text("").also(entries::add)
        line.value += parts.first()
        parts.drop(1).forEach { part ->
            entries += TranscriptEntry.Text(part)
        }
    }

    @Synchronized
    fun newLine() {
        entries += TranscriptEntry.Text("")
    }

    @Synchronized
    fun appendCollapsible(
        collapsed: List<String>,
        expanded: List<String>,
    ) {
        if (collapsed.isEmpty() && expanded.isEmpty()) {
            return
        }
        if ((entries.lastOrNull() as? TranscriptEntry.Text)?.value.isNullOrEmpty()) {
            entries.removeLast()
        }
        entries += TranscriptEntry.Collapsible(collapsed.toList(), expanded.toList())
        entries += TranscriptEntry.Text("")
    }

    @Synchronized
    fun setToolsExpanded(expanded: Boolean) {
        toolsExpanded = expanded
    }

    @Synchronized
    fun setTransient(value: String?) {
        transient = value
    }

    @Synchronized
    fun commitTransient() {
        val value = transient
        transient = null
        if (value != null) {
            append(value)
            newLine()
        }
    }

    @Synchronized
    override fun render(width: Int): List<String> =
        buildList {
            val stable =
                if (
                    transient != null &&
                    (entries.lastOrNull() as? TranscriptEntry.Text)?.value.isNullOrEmpty()
                ) {
                    entries.dropLast(1)
                } else {
                    entries
                }
            stable.forEach { entry ->
                val lines =
                    when (entry) {
                        is TranscriptEntry.Text -> listOf(entry.value)
                        is TranscriptEntry.Collapsible ->
                            if (toolsExpanded) entry.expanded else entry.collapsed
                    }
                lines.forEach { line ->
                    addAll(wrapTextWithAnsi(line, width.coerceAtLeast(1)))
                }
            }
            transient?.let { value ->
                addAll(wrapTextWithAnsi(value, width.coerceAtLeast(1)))
            }
        }
}

private sealed interface TranscriptEntry {
    data class Text(
        var value: String,
    ) : TranscriptEntry

    data class Collapsible(
        val collapsed: List<String>,
        val expanded: List<String>,
    ) : TranscriptEntry
}

private class PromptComponent : Component {
    @Volatile
    var text: String = ""

    override fun render(width: Int): List<String> =
        text
            .takeIf(String::isNotEmpty)
            ?.let { value ->
                listOf(truncateToWidth(value, width.coerceAtLeast(1)))
            }.orEmpty()
}

internal class JLineTuiTerminal(
    private val terminal: JLineTerminal =
        TerminalBuilder
            .builder()
            .system(true)
            .build(),
) : Terminal,
    Closeable {
    private val running = AtomicBoolean(false)
    private var previousAttributes: Attributes? = null
    private var inputThread: Thread? = null
    private var previousWinchHandler: SignalHandler? = null

    override val columns: Int
        get() = normalizeTerminalWidth(terminal.width)

    override val rows: Int
        get() = terminal.height.takeIf { it > 0 } ?: 24

    override fun start(
        onInput: (String) -> Unit,
        onResize: () -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) {
            return
        }
        previousAttributes = terminal.enterRawMode()
        previousWinchHandler = terminal.handle(Signal.WINCH) { onResize() }
        write(BRACKETED_PASTE_ON + KEYBOARD_PROTOCOL_QUERY)
        inputThread =
            thread(
                name = "pi-full-screen-input",
                isDaemon = true,
            ) {
                val reader = terminal.reader()
                while (running.get()) {
                    val sequence =
                        runCatching { readInputSequence(reader) }
                            .getOrNull()
                            ?: break
                    if (handleKeyboardNegotiation(sequence)) {
                        continue
                    }
                    onInput(sequence)
                }
            }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        write(BRACKETED_PASTE_OFF + KEYBOARD_PROTOCOL_POP + MODIFY_OTHER_KEYS_OFF)
        setKittyProtocolActive(false)
        inputThread?.interrupt()
        inputThread?.join(250)
        inputThread = null
        previousWinchHandler?.let { handler ->
            terminal.handle(Signal.WINCH, handler)
        }
        previousWinchHandler = null
        previousAttributes?.let(terminal::setAttributes)
        previousAttributes = null
    }

    override fun write(data: String) {
        synchronized(terminal) {
            terminal.writer().print(data)
            terminal.writer().flush()
        }
    }

    override fun close() {
        stop()
        terminal.close()
    }

    private fun handleKeyboardNegotiation(sequence: String): Boolean {
        val flags = KITTY_FLAGS_PATTERN.matchEntire(sequence)
        if (flags != null) {
            val active = flags.groupValues[1].toInt() != 0
            setKittyProtocolActive(active)
            if (!active) {
                write(MODIFY_OTHER_KEYS_ON)
            }
            return true
        }
        if (DEVICE_ATTRIBUTES_PATTERN.matches(sequence)) {
            write(MODIFY_OTHER_KEYS_ON)
            return true
        }
        return false
    }
}

private fun readInputSequence(reader: NonBlockingReader): String? {
    val first = reader.read()
    if (first == NonBlockingReader.EOF) {
        return null
    }
    if (first == NonBlockingReader.READ_EXPIRED) {
        return ""
    }
    val result = StringBuilder().append(first.toChar())
    if (first.toChar().isHighSurrogate()) {
        val second = reader.read(INPUT_FRAGMENT_TIMEOUT_MS)
        if (second >= 0) {
            result.append(second.toChar())
        }
        return result.toString()
    }
    if (first != ESCAPE_CODE) {
        return result.toString()
    }
    while (true) {
        val next = reader.read(INPUT_FRAGMENT_TIMEOUT_MS)
        if (next < 0) {
            return result.toString()
        }
        result.append(next.toChar())
        val value = result.toString()
        if (value.startsWith(PASTE_START)) {
            if (value.endsWith(PASTE_END)) {
                return value
            }
            continue
        }
        if (isCompleteEscapeSequence(value)) {
            return value
        }
    }
}

private fun isCompleteEscapeSequence(value: String): Boolean {
    if (value.length < 2) {
        return false
    }
    return when (value[1]) {
        '[' -> value.length >= 3 && value.last().code in 0x40..0x7e
        ']' -> value.endsWith('\u0007') || value.endsWith("\u001B\\")
        'O' -> value.length >= 3
        else -> value.length >= 2
    }
}

private val KITTY_FLAGS_PATTERN = Regex("""^\u001B\[\?(\d+)u$""")
private val DEVICE_ATTRIBUTES_PATTERN = Regex("""^\u001B\[\?[\d;]*c$""")
private const val OSC133_ZONE_START = "\u001B]133;A\u0007"
private const val OSC133_ZONE_END = "\u001B]133;B\u0007"
private const val OSC133_ZONE_FINAL = "\u001B]133;C\u0007"
private const val ESCAPE_CODE = 27
private const val INPUT_FRAGMENT_TIMEOUT_MS = 12L
private const val PASTE_START = "\u001B[200~"
private const val PASTE_END = "\u001B[201~"
private const val BRACKETED_PASTE_ON = "\u001B[?2004h"
private const val BRACKETED_PASTE_OFF = "\u001B[?2004l"
private const val KEYBOARD_PROTOCOL_QUERY = "\u001B[>7u\u001B[?u\u001B[c"
private const val KEYBOARD_PROTOCOL_POP = "\u001B[<u"
private const val MODIFY_OTHER_KEYS_ON = "\u001B[>4;2m"
private const val MODIFY_OTHER_KEYS_OFF = "\u001B[>4;0m"
