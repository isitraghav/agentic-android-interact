"""Conversational graph — mirrors AgentEngine.chat() in AgentEngine.kt.

The model picks one of a small set of top-level tools (answer / automate /
memory_write / memory_read / search_conversations). memory_read and
search_conversations are handled inline and loop back into the agent so the
model can gather context before committing to "answer" or "automate".
"""

import json
from typing import Optional, TypedDict

from langgraph.graph import END, StateGraph

from .. import stores
from ..llm import invoke_with_tools
from ..prompts import CHAT_SYSTEM_PROMPT
from ..tools import chat_tools

MAX_ITERATIONS = 4


class ChatState(TypedDict):
    messages: list[dict]
    iterations: int
    result: Optional[dict]
    provider_idx: int
    log: list[str]


def _agent(state: ChatState) -> ChatState:
    iterations = state["iterations"] + 1
    res = invoke_with_tools(state["messages"], tools=chat_tools(), provider_idx=state["provider_idx"])

    log = state["log"] + ([f"[Think] {res.reasoning[:300]}"] if res.reasoning else [])
    log.append(f"[Tool calls] {res.tool_calls}")

    call = res.tool_calls[0] if res.tool_calls else None
    if call is None:
        text = res.content.strip() or "Sorry, I didn't catch that."
        return {**state, "iterations": iterations, "provider_idx": res.provider_idx,
                "result": {"type": "answer", "text": text}, "log": log}

    assistant_line: dict = {"action": call.action}
    if call.text is not None:
        assistant_line["text"] = call.text
    messages = state["messages"] + [{"role": "assistant", "content": json.dumps(assistant_line)}]

    result: Optional[dict] = None

    if call.action == "automate":
        goal = (call.text or "").strip()
        result = {"type": "automate", "text": goal}
    elif call.action == "memory_write":
        fact = (call.text or "").strip()
        if fact:
            stores.append_memory(fact)
        result = {"type": "answer", "text": "Got it — I'll remember that." if fact else "Okay."}
    elif call.action == "memory_read":
        mem = stores.read_memory()
        tool_result = f"MEMORY:\n{mem}" if mem else "Nothing in saved memory."
        log.append(f"memory_read -> {tool_result[:100]}")
        messages.append({"role": "user", "content": tool_result})
    elif call.action == "search_conversations":
        query = (call.text or "").strip()
        hits = stores.conversation_store.search(query)
        tool_result = (
            f'CONVERSATION HISTORY (matching "{query}"):\n{hits}'
            if hits else f'No past conversations mention "{query}".'
        )
        log.append(f'search_conversations "{query}" -> {tool_result[:100]}')
        messages.append({"role": "user", "content": tool_result})
    elif call.action == "answer":
        text = (call.text or "").strip() or res.content
        result = {"type": "answer", "text": text or "Okay."}
    else:
        result = {"type": "answer", "text": res.content.strip() or "Okay."}

    return {**state, "messages": messages, "iterations": iterations, "provider_idx": res.provider_idx,
            "result": result, "log": log}


def _route(state: ChatState) -> str:
    if state["result"] is not None or state["iterations"] >= MAX_ITERATIONS:
        return "end"
    return "loop"


def _build_graph():
    graph = StateGraph(ChatState)
    graph.add_node("agent", _agent)
    graph.set_entry_point("agent")
    graph.add_conditional_edges("agent", _route, {"loop": "agent", "end": END})
    return graph.compile()


CHAT_GRAPH = _build_graph()


def run_chat(conversation_id: str, user_message: str) -> tuple[dict, list[str]]:
    """Runs one conversational turn. Returns ({"type": "answer"|"automate", "text": ...}, log_lines)."""
    stores.conversation_store.add(conversation_id, "user", user_message)

    memory = stores.read_memory()
    system_content = CHAT_SYSTEM_PROMPT
    if memory:
        system_content += f"\n\nMEMORY:\n{memory}"

    messages: list[dict] = [{"role": "system", "content": system_content}]
    history = stores.conversation_store.recent_for_prompt(conversation_id, 13)
    for m in history[:-1]:
        messages.append({"role": m["role"], "content": m["text"]})
    messages.append({"role": "user", "content": user_message})

    init_state: ChatState = {"messages": messages, "iterations": 0, "result": None, "provider_idx": 0, "log": []}
    final_state = CHAT_GRAPH.invoke(init_state)
    result = final_state["result"] or {"type": "answer", "text": "Sorry, I didn't catch that."}

    if result["type"] == "answer":
        stores.conversation_store.add(conversation_id, "assistant", result["text"])

    return result, final_state["log"]
