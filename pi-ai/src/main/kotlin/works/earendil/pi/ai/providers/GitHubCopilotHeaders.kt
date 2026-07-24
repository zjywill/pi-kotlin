package works.earendil.pi.ai.providers

import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.UserMessage

internal fun githubCopilotDynamicHeaders(context: Context): Map<String, String> =
    buildMap {
        val lastMessage = context.messages.lastOrNull()
        put(
            "X-Initiator",
            if (lastMessage == null || lastMessage is UserMessage) "user" else "agent",
        )
        put("Openai-Intent", "conversation-edits")
        if (context.messages.any(::containsCopilotImage)) {
            put("Copilot-Vision-Request", "true")
        }
    }

private fun containsCopilotImage(message: works.earendil.pi.ai.Message): Boolean =
    when (message) {
        is UserMessage ->
            (message.content as? MessageContent.Blocks)
                ?.blocks
                ?.any { it is ImageContent } == true

        is ToolResultMessage -> message.content.any { it is ImageContent }
        else -> false
    }
