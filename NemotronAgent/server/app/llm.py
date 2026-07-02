"""LLM call wrapper, mirroring NvidiaNemotronClient.kt: builds an OpenAI-compatible
chat model for the active provider, offers it the given tool schemas, and falls
back to the next provider in ALL_PROVIDERS on a rate-limit error.
"""

import json
import re
from typing import Any, Optional

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from openai import BadRequestError

from .config import ALL_PROVIDERS, LlmProvider
from .schemas import ToolCall


def _build_chat_model(provider: LlmProvider) -> ChatOpenAI:
    kwargs: dict[str, Any] = dict(
        model=provider.model,
        api_key=provider.api_key,
        base_url=provider.base_url,
        max_tokens=provider.max_tokens,
        temperature=provider.temperature,
        top_p=0.95,
    )
    if "localhost:11434" in (provider.base_url or ""):
        kwargs["extra_body"] = {**kwargs.get("extra_body", {}), "options": {"num_ctx": 8192}}
    if provider.extra_body:
        kwargs["extra_body"] = provider.extra_body
    return ChatOpenAI(**kwargs)


def to_lc_messages(messages: list[dict[str, str]]) -> list[BaseMessage]:
    out: list[BaseMessage] = []
    for m in messages:
        role, content = m["role"], m["content"]
        if role == "system":
            out.append(SystemMessage(content=content))
        elif role == "assistant":
            out.append(AIMessage(content=content))
        else:
            out.append(HumanMessage(content=content))
    return out


def _is_rate_limit(msg: str) -> bool:
    low = msg.lower()
    return "429" in msg or "rate_limit" in low or "rate limit" in low


def _parse_content_as_tool_calls(content: str) -> list[ToolCall]:
    """Some models emit tool calls as raw JSON in content instead of structured
    tool_calls. Handles: single object, JSON array, or newline-separated objects.
    """
    trimmed = content.strip()
    if not trimmed:
        return []

    # collect candidate JSON blobs: try full content first, then line-by-line
    blobs: list[str] = []
    if trimmed[0] in "{[":
        blobs.append(trimmed)
    for line in trimmed.splitlines():
        line = line.strip()
        if line and line[0] in "{[" and line != trimmed:
            blobs.append(line)

    objects: list[dict] = []
    for blob in blobs:
        try:
            parsed = json.loads(blob)
            if isinstance(parsed, list):
                objects.extend(parsed)
            elif isinstance(parsed, dict):
                objects.append(parsed)
        except json.JSONDecodeError:
            pass

    # fallback: model emitted Python-style call e.g. automate("do thing") or find_and_click(text="X")
    if not objects:
        m = _PYTHON_CALL_RE.match(trimmed)
        if m:
            name, raw_args = m.group(1), m.group(2).strip()
            kwargs: dict[str, str] = {}
            # try kwargs: key="value" pairs
            for kv in re.finditer(r'(\w+)\s*=\s*"([^"]*)"', raw_args):
                kwargs[kv.group(1)] = kv.group(2)
            if not kwargs:
                # positional: first quoted string -> "text"
                pos = re.search(r'"([^"]*)"', raw_args)
                if pos:
                    kwargs["text"] = pos.group(1)
            if name:
                return [ToolCall(
                    action=name,
                    text=kwargs.get("text"),
                    id=kwargs.get("id"),
                    direction=kwargs.get("direction"),
                    pkg=kwargs.get("pkg") or kwargs.get("app") or kwargs.get("package"),
                    ms=int(kwargs["ms"]) if kwargs.get("ms", "").isdigit() else None,
                )]

    calls: list[ToolCall] = []
    seen: set[str] = set()
    for obj in objects:
        if not isinstance(obj, dict):
            continue
        # support {"action": ..., "text": ...} and {"name": ..., "arguments": {...}}
        if "name" in obj and "action" not in obj:
            args = obj.get("arguments") or {}
            obj = {"action": obj["name"], **args}
        if "action" not in obj:
            continue
        key = json.dumps(obj, sort_keys=True)
        if key in seen:
            continue
        seen.add(key)
        calls.append(
            ToolCall(
                action=obj["action"],
                id=obj.get("id"),
                text=obj.get("text"),
                direction=obj.get("direction"),
                pkg=obj.get("pkg") or obj.get("app") or obj.get("package"),
                ms=obj.get("ms"),
                phone=obj.get("phone"),
            )
        )
    return calls


_FUNCTION_TAG_RE = re.compile(r"<function=(\w+)\s*(\{.*\})[^<]*</function>", re.DOTALL)
# matches: action_name("positional text") or action_name(text="...", pkg="...", ...)
_PYTHON_CALL_RE = re.compile(r'^(\w+)\((.+)\)\s*$', re.DOTALL)


def _extract_tool_calls(ai_msg: AIMessage) -> list[ToolCall]:
    calls: list[ToolCall] = []
    for tc in ai_msg.tool_calls or []:
        args = tc.get("args") or {}
        calls.append(
            ToolCall(
                action=tc["name"],
                id=args.get("id"),
                text=args.get("text"),
                direction=args.get("direction"),
                pkg=args.get("pkg") or args.get("app") or args.get("package"),
                ms=args.get("ms"),
                phone=args.get("phone"),
            )
        )
    if not calls:
        content = ai_msg.content if isinstance(ai_msg.content, str) else ""
        calls = _parse_content_as_tool_calls(content)
    return calls


class LlmResult:
    def __init__(self, content: str, reasoning: str, tool_calls: list[ToolCall], provider_idx: int):
        self.content = content
        self.reasoning = reasoning
        self.tool_calls = tool_calls
        self.provider_idx = provider_idx


def _recover_tool_use_failed(err: BadRequestError) -> Optional[LlmResult]:
    """Groq's llama-3.3-70b-versatile occasionally emits tool calls in a
    `<function=name{...}</function>` format that Groq itself can't parse,
    returning a 400 `tool_use_failed` with the raw text in `failed_generation`.
    Recover the call from that text instead of failing the whole turn.
    """
    body = err.body if isinstance(err.body, dict) else {}
    error = body.get("error", body)
    if error.get("code") != "tool_use_failed":
        return None
    match = _FUNCTION_TAG_RE.search(error.get("failed_generation") or "")
    if not match:
        return None
    name, raw_args = match.group(1), match.group(2)
    try:
        args = json.loads(raw_args)
    except json.JSONDecodeError:
        args = {}
    call = ToolCall(
        action=name,
        id=args.get("id"),
        text=args.get("text"),
        direction=args.get("direction"),
        pkg=args.get("pkg") or args.get("app") or args.get("package"),
        ms=args.get("ms"),
        phone=args.get("phone"),
    )
    return LlmResult("", "", [call], -1)


def invoke_with_tools(
    messages: list[dict[str, str]],
    tools: Optional[list[dict[str, Any]]] = None,
    provider_idx: int = 0,
) -> LlmResult:
    """Calls the active provider; on a rate-limit error, rotates to the next
    provider in ALL_PROVIDERS and retries (matching AgentEngine's behaviour).
    """
    lc_messages = to_lc_messages(messages)
    last_err: Optional[Exception] = None
    for offset in range(len(ALL_PROVIDERS)):
        idx = (provider_idx + offset) % len(ALL_PROVIDERS)
        provider = ALL_PROVIDERS[idx]
        try:
            llm = _build_chat_model(provider)
            bound = llm.bind_tools(tools, tool_choice="auto") if tools else llm
            ai_msg = bound.invoke(lc_messages)
            content = ai_msg.content if isinstance(ai_msg.content, str) else str(ai_msg.content)
            reasoning = ai_msg.additional_kwargs.get("reasoning_content") or ""
            return LlmResult(content, reasoning, _extract_tool_calls(ai_msg), idx)
        except BadRequestError as e:
            recovered = _recover_tool_use_failed(e)
            if recovered is not None:
                recovered.provider_idx = idx
                return recovered
            raise
        except Exception as e:  # noqa: BLE001 - provider fallback needs the broad catch
            if _is_rate_limit(str(e)) and offset < len(ALL_PROVIDERS) - 1:
                last_err = e
                continue
            raise
    assert last_err is not None
    raise last_err
