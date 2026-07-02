"""On-device automation loop — mirrors AgentEngine.runAutomation() in AgentEngine.kt.

Each HTTP call to /automation/step is one iteration: the client supplies the
current screen (and the outcome of whatever tool_calls it executed for the
previous step), and gets back the next batch of tool_calls to run.

memory_read / memory_write and "no tool call" retries are handled inline via a
self-looping "decide" node so the model can gather context before it commits
to device actions or a terminal action (complete / answer / ask).
"""

import json
import re
from typing import Optional, TypedDict

from langgraph.graph import END, StateGraph

from .. import stores
from ..llm import invoke_with_tools
from ..prompts import AUTOMATION_SYSTEM_PROMPT
from ..schemas import ActionResult, AutomationStepRequest, AutomationStepResponse, ToolCall, UiElement
from ..tools import device_tools

MAX_HISTORY_PAIRS = 6
MAX_INTERNAL_ITERATIONS = 4
MAX_STEPS = 50

_SIMPLE_OPEN_GOAL_RE = re.compile(
    r"^(open|launch|start)\s+(the\s+|my\s+)?[\w&-]+(\s+[\w&-]+)?(\s+app)?$", re.IGNORECASE
)

# session_id -> persisted automation state
_SESSIONS: dict[str, dict] = {}


# --------------------------------------------------------------------------- decide graph


class DecideState(TypedDict):
    messages: list[dict]
    element_ids: list[str]
    provider_idx: int
    iterations: int
    out_tool_calls: list[ToolCall]
    status: str  # "loop" | "continue" | "ask" | "done"
    out_message: Optional[str]
    log: list[str]


def _history_line(tc: ToolCall) -> str:
    parts = [f'"action":"{tc.action}"']
    if tc.pkg is not None:
        parts.append(f'"pkg":"{tc.pkg}"')
    if tc.id is not None:
        parts.append(f'"id":"{tc.id}"')
    if tc.text is not None:
        parts.append(f'"text":"{tc.text}"')
    if tc.direction is not None:
        parts.append(f'"direction":"{tc.direction}"')
    if tc.ms is not None:
        parts.append(f'"ms":{tc.ms}')
    return "{" + ",".join(parts) + "}"


def _decide(state: DecideState) -> DecideState:
    iterations = state["iterations"] + 1
    res = invoke_with_tools(
        state["messages"], tools=device_tools(state["element_ids"]), provider_idx=state["provider_idx"]
    )

    log = state["log"] + ([f"[Think] {res.reasoning[:300]}"] if res.reasoning else [])
    messages = state["messages"]
    calls = res.tool_calls

    if not calls:
        log.append(f"No tool call. [Raw] {res.content[:200]}")
        messages = messages + [{"role": "assistant", "content": res.content}]
        messages = messages + [{"role": "user", "content": "No action taken. Call one of the available tools."}]
        status = "loop" if iterations < MAX_INTERNAL_ITERATIONS else "continue"
        return {**state, "messages": messages, "iterations": iterations, "provider_idx": res.provider_idx,
                "status": status, "out_tool_calls": [], "out_message": None, "log": log}

    history_line = "\n".join(_history_line(c) for c in calls)
    messages = messages + [{"role": "assistant", "content": history_line}]

    out_tool_calls: list[ToolCall] = []
    status = "continue"
    out_message: Optional[str] = None

    for call in calls:
        log.append(f"-> {call.action} pkg={call.pkg} id={call.id} text={call.text}")
        if call.action == "memory_write":
            text = (call.text or "").strip()
            if text:
                stores.append_memory(text)
                log.append(f"Memory saved: {text}")
            messages = messages + [{"role": "user", "content": "memory_write:OK"}]
            status = "loop"
            break
        if call.action == "memory_read":
            mem = stores.read_memory()
            log.append(f"Memory read: {mem[:100]}")
            messages = messages + [{"role": "user", "content": f"MEMORY:\n{mem}"}]
            status = "loop"
            break
        if call.action == "complete":
            log.append("Done!")
            out_tool_calls.append(call)
            status = "done"
            out_message = "Done."
            break
        if call.action == "answer":
            log.append(f"Answer: {call.text}")
            out_tool_calls.append(call)
            status = "done"
            out_message = call.text or "Done."
            break
        if call.action == "ask":
            question = call.text or "What should I do?"
            log.append(f"Asking user: {question}")
            out_tool_calls.append(call)
            status = "ask"
            out_message = question
            break
        # device action / toast / wait — executed by the client
        out_tool_calls.append(call)

    if status == "loop" and iterations >= MAX_INTERNAL_ITERATIONS:
        status = "continue"

    return {**state, "messages": messages, "iterations": iterations, "provider_idx": res.provider_idx,
            "status": status, "out_tool_calls": out_tool_calls, "out_message": out_message, "log": log}


def _route(state: DecideState) -> str:
    return "loop" if state["status"] == "loop" else "end"


def _build_graph():
    graph = StateGraph(DecideState)
    graph.add_node("decide", _decide)
    graph.set_entry_point("decide")
    graph.add_conditional_edges("decide", _route, {"loop": "decide", "end": END})
    return graph.compile()


AUTOMATION_GRAPH = _build_graph()


# --------------------------------------------------------------------------- session helpers


def _build_system_prompt(goal: str) -> str:
    memory = stores.read_memory()
    similar = stores.task_history.find_similar(goal)
    parts = [AUTOMATION_SYSTEM_PROMPT]
    if memory:
        parts.append(f"\n\nMEMORY:\n{memory}")
    if similar:
        lines = [
            "\n\nPROVEN WORKFLOW (from previous successful run — adapt to current goal):",
            f"General task: how to do '{similar['goal']}' type tasks on {similar['app']}",
            f"Last run: {similar['date']}",
            "Programmatic steps that worked:",
        ]
        lines += [f"  {i + 1}. {a}" for i, a in enumerate(similar["actions"])]
        lines.append("Adapt contact names, text content, and any specifics to the current goal.")
        parts.append("\n".join(lines))
    return "".join(parts)


def _build_screen_json(elements: list[UiElement]) -> str:
    items = []
    for e in elements[:60]:
        item: dict = {"id": e.id, "role": e.role, "label": e.label.replace('"', "'"), "y": e.y}
        if e.clickable:
            item["c"] = 1
        items.append(item)
    return json.dumps(items, separators=(",", ":"))


def _prune_history(messages: list[dict]) -> list[dict]:
    if len(messages) <= 2 + MAX_HISTORY_PAIRS * 2:
        return messages
    system, first_user = messages[0], messages[1]
    tail = messages[2:][-(MAX_HISTORY_PAIRS * 2):]
    return [system, first_user] + tail


def _action_log_entry(ar: ActionResult) -> str:
    if ar.action == "open_app":
        return f"open_app -> {ar.pkg or ar.text}"
    if ar.action == "click_element":
        return f"click -> {ar.id}"
    if ar.action == "find_and_click":
        return f"find_click -> {ar.text}"
    if ar.action == "type_text":
        return f'type [{ar.id}] -> "{(ar.text or "")[:30]}"'
    if ar.action == "scroll_screen":
        return f"scroll -> {ar.direction}"
    if ar.action in ("press_back", "press_home"):
        return ar.action
    if ar.action == "search_contacts":
        return f"search_contacts -> {ar.text}"
    return ar.action


# --------------------------------------------------------------------------- entry point


def run_step(req: AutomationStepRequest) -> AutomationStepResponse:
    log: list[str] = []

    if req.session_id and req.session_id in _SESSIONS:
        session = _SESSIONS[req.session_id]
        session_id = req.session_id
    else:
        if not req.goal:
            raise ValueError("`goal` is required to start a new automation session")
        session_id = req.session_id or stores.new_session_id()
        goal = req.goal.strip()
        session = {
            "goal": goal,
            "messages": [{"role": "system", "content": _build_system_prompt(goal)}],
            "detected_app": "",
            "action_log": [],
            "step_count": 0,
            "is_simple_open_goal": bool(_SIMPLE_OPEN_GOAL_RE.match(goal)),
            "last_screen_hash": "",
            "provider_idx": 0,
        }
        _SESSIONS[session_id] = session
        log.append(f"Goal: {goal}")

    messages: list[dict] = session["messages"]

    # ---- ingest the previous turn's outcome ----
    if req.user_answer is not None:
        messages = messages + [{"role": "user", "content": f"User answer: {req.user_answer}"}]
        log.append(f"User answered: {req.user_answer}")

    done_early: Optional[AutomationStepResponse] = None
    for ar in req.action_results:
        if ar.ok:
            session["action_log"].append(_action_log_entry(ar))
            if ar.action == "open_app" and not session["detected_app"]:
                session["detected_app"] = ar.pkg or ar.text or ""
            if ar.action == "open_app" and session["is_simple_open_goal"]:
                log.append("Simple open-app goal — done without verification.")
                stores.task_history.record(session["goal"], session["detected_app"], session["action_log"])
                done_early = AutomationStepResponse(
                    session_id=session_id, status="done", tool_calls=[], message="Done.", log=log
                )
        content = f"{ar.action}:OK"
        if ar.ok and ar.current_pkg:
            content += f" [app:{ar.current_pkg}]"
        elif not ar.ok:
            content = f"{ar.action}:FAIL"
        if ar.result:
            content += f"\n{ar.result}"
        messages = messages + [{"role": "user", "content": content}]

    if done_early is not None:
        del _SESSIONS[session_id]
        return done_early

    # ---- no screen yet: client is still waiting for the UI tree ----
    if not req.screen:
        log.append("No elements. Waiting...")
        session["messages"] = messages
        return AutomationStepResponse(session_id=session_id, status="continue", tool_calls=[], message=None, log=log)

    session["step_count"] += 1
    screen_json = _build_screen_json(req.screen)
    screen_hash = str(hash(screen_json))
    screen_summary = "[unchanged]" if screen_hash == session["last_screen_hash"] else screen_json
    session["last_screen_hash"] = screen_hash

    if session["step_count"] == 1:
        user_content = f"GOAL: {session['goal']}\nSCREEN:{screen_summary}"
    else:
        user_content = f"SCREEN:{screen_summary}"
    messages = messages + [{"role": "user", "content": user_content}]
    messages = _prune_history(messages)

    if session["step_count"] >= MAX_STEPS:
        log.append("Max steps reached.")
        del _SESSIONS[session_id]
        return AutomationStepResponse(
            session_id=session_id, status="done", tool_calls=[],
            message="I stopped after too many steps without finishing — want me to keep going or try something else?",
            log=log,
        )

    element_ids = [e.id for e in req.screen]

    init_state: DecideState = {
        "messages": messages,
        "element_ids": element_ids,
        "provider_idx": session["provider_idx"],
        "iterations": 0,
        "out_tool_calls": [],
        "status": "continue",
        "out_message": None,
        "log": [],
    }
    final_state = AUTOMATION_GRAPH.invoke(init_state)
    log += final_state["log"]

    session["messages"] = final_state["messages"]
    session["provider_idx"] = final_state["provider_idx"]

    status = final_state["status"]
    if status == "done":
        stores.task_history.record(session["goal"], session["detected_app"], session["action_log"])
        del _SESSIONS[session_id]
    elif status == "ask":
        # session stays alive — client will resume with `user_answer` on the next call
        pass

    return AutomationStepResponse(
        session_id=session_id,
        status=status,
        tool_calls=final_state["out_tool_calls"],
        message=final_state["out_message"],
        log=log,
    )
