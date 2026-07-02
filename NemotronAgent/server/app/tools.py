"""OpenAI-style function definitions, mirroring app/.../Tools.kt.

Device-control tools are built dynamically per automation step: the `id`
parameter of id-addressed tools is constrained to an enum of the ids actually
present on the current screen, so the model can't reference stale/hallucinated
element ids.
"""

from typing import Any, Optional


def _tool(
    name: str,
    description: str,
    properties: Optional[dict[str, Any]] = None,
    required: Optional[list[str]] = None,
) -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": {
                "type": "object",
                "properties": properties or {},
                "required": required or [],
            },
        },
    }


def _prop(type_: str, description: str, enum: Optional[list[str]] = None) -> dict[str, Any]:
    p: dict[str, Any] = {"type": type_, "description": description}
    if enum is not None:
        p["enum"] = enum
    return p


def chat_tools() -> list[dict[str, Any]]:
    """Top-level tools for conversational turns."""
    return [
        _tool(
            "answer",
            "Reply conversationally to the user. Use for questions, conversation, advice, explanations, or reporting a result you already know.",
            properties={"text": _prop("string", "The reply to show the user.")},
            required=["text"],
        ),
        _tool(
            "automate",
            "Control the device to accomplish a task: open an app, send a message, search, navigate, tap/type/scroll, change a setting, etc.",
            properties={"text": _prop("string", "Short, concrete restatement of the on-device task to perform.")},
            required=["text"],
        ),
        _tool(
            "memory_write",
            "Remember a fact for future conversations (name, preference, contact, routine, etc).",
            properties={"text": _prop("string", "The fact to remember, phrased for future reference.")},
            required=["text"],
        ),
        _tool("memory_read", "Recall previously saved facts before answering."),
        _tool(
            "search_conversations",
            "Search past conversation history for context not in saved memory — names, contact details, prior tasks, or anything the user mentioned before.",
            properties={"query": _prop("string", "Keyword or phrase to search for (e.g. 'Shiv', 'dinner', 'phone number').")},
            required=["query"],
        ),
    ]


def device_tools(element_ids: list[str]) -> list[dict[str, Any]]:
    """Device-control tools for the automation loop.

    `element_ids` constrains id-addressed tools to the current screen.
    """
    enum = element_ids or None
    id_param = _prop("string", "Element id from the current SCREEN JSON.", enum=enum)
    return [
        _tool(
            "open_app",
            "Launch an app by name or package.",
            properties={"pkg": _prop("string", "App name or package, e.g. 'WhatsApp' or 'com.whatsapp'.")},
            required=["pkg"],
        ),
        _tool(
            "click_element",
            "Tap a specific element on the current screen.",
            properties={"id": id_param},
            required=["id"],
        ),
        _tool(
            "find_and_click",
            "Find an element by its exact visible text anywhere on screen and tap it.",
            properties={"text": _prop("string", "Exact visible text to find and tap.")},
            required=["text"],
        ),
        _tool(
            "type_text",
            "Tap an input field and type text into it.",
            properties={"id": id_param, "text": _prop("string", "Text to type.")},
            required=["id", "text"],
        ),
        _tool(
            "press_enter",
            "Press the keyboard's enter/search/go action, optionally on a specific field.",
            properties={"id": _prop("string", "Optional input field id. If omitted, uses the focused field.", enum=enum)},
        ),
        _tool(
            "scroll_screen",
            "Scroll the current screen.",
            properties={"direction": _prop("string", "Scroll direction.", enum=["up", "down"])},
            required=["direction"],
        ),
        _tool("press_back", "Press the system back button."),
        _tool("press_home", "Go to the home screen."),
        _tool(
            "wait",
            "Wait before checking the screen again.",
            properties={"ms": _prop("integer", "Milliseconds to wait.")},
        ),
        _tool(
            "toast",
            "Show the user a short status update describing what you're about to do (under 60 chars).",
            properties={"text": _prop("string", "Status text.")},
            required=["text"],
        ),
        _tool(
            "ask",
            "Ask the user a question when genuinely stuck (ambiguous target, missing info, repeated failure).",
            properties={"text": _prop("string", "Question for the user.")},
            required=["text"],
        ),
        _tool(
            "list_messaging_apps",
            "List all messaging apps installed on the phone that can send messages. "
            "Call this once at the start of any messaging task if you don't know which app to use.",
        ),
        _tool(
            "search_contacts",
            "Search the phone's contacts by name to find a phone number.",
            properties={"text": _prop("string", "Name (or part of a name) to search for.")},
            required=["text"],
        ),
        _tool(
            "send_message",
            "Send a text message via any installed messaging app using a phone number. "
            "Always call search_contacts first to get the number, then call this. "
            "The app opens the chat pre-filled — follow up with find_and_click('Send') to actually send.",
            properties={
                "pkg": _prop("string", "Messaging app: 'whatsapp', 'sms', 'telegram', 'signal', or any package name."),
                "phone": _prop("string", "Phone number from search_contacts, e.g. '+911234567890'."),
                "text": _prop("string", "Message text to send."),
            },
            required=["pkg", "phone", "text"],
        ),
        _tool(
            "memory_write",
            "Remember a fact useful across sessions (contact names, preferences, app locations).",
            properties={"text": _prop("string", "The fact to remember.")},
            required=["text"],
        ),
        _tool("memory_read", "Recall previously saved facts."),
        _tool(
            "answer",
            "Finish: report the answer/result to the user.",
            properties={"text": _prop("string", "Answer to show the user.")},
            required=["text"],
        ),
        _tool("complete", "Finish: the goal has been visibly accomplished, nothing more to report."),
    ]


# Actions handled internally by the server graphs — never returned to the client.
INTERNAL_ACTIONS = {"memory_read", "memory_write"}

# Actions that end an automation turn (returned to the client as the last tool_call).
TERMINAL_ACTIONS = {"complete", "answer", "ask"}
