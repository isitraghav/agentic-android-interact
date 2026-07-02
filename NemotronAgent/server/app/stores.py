"""File-backed stores, mirroring AgentEngine's memory file, ConversationStore.kt
and TaskHistory.kt — same JSON shapes, just on the server's local disk now.
"""

import json
import os
import re
import time
import uuid
from datetime import datetime
from typing import Optional

from . import config

MAX_MESSAGES = 200
MAX_CONVERSATIONS = 50


def _ensure_data_dir() -> None:
    os.makedirs(config.DATA_DIR, exist_ok=True)


# --------------------------------------------------------------------------- memory


def read_memory() -> str:
    try:
        with open(config.MEMORY_FILE, "r") as f:
            return f.read().strip()
    except FileNotFoundError:
        return ""


def append_memory(text: str) -> None:
    _ensure_data_dir()
    date = datetime.now().strftime("%Y-%m-%d %H:%M")
    with open(config.MEMORY_FILE, "a") as f:
        f.write(f"[{date}] {text}\n")


# --------------------------------------------------------------------------- conversations


class ConversationStore:
    """Persists chat conversations to disk, one JSON list of conversation objects."""

    def __init__(self, path: str = config.CONVERSATIONS_FILE):
        self.path = path
        self.conversations: list[dict] = self._load()

    def _load(self) -> list[dict]:
        try:
            with open(self.path, "r") as f:
                return json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            return []

    def _persist(self) -> None:
        _ensure_data_dir()
        with open(self.path, "w") as f:
            json.dump(self.conversations, f)

    def get_or_create(self, conversation_id: str) -> dict:
        for c in self.conversations:
            if c["id"] == conversation_id:
                return c
        convo = {
            "id": conversation_id,
            "title": "New chat",
            "created_at": int(time.time() * 1000),
            "messages": [],
        }
        self.conversations.insert(0, convo)
        if len(self.conversations) > MAX_CONVERSATIONS:
            self.conversations.pop()
        self._persist()
        return convo

    def add(self, conversation_id: str, role: str, text: str) -> None:
        convo = self.get_or_create(conversation_id)
        convo["messages"].append({"role": role, "text": text, "time": int(time.time() * 1000)})
        if len(convo["messages"]) > MAX_MESSAGES:
            convo["messages"].pop(0)
        if convo["title"] == "New chat" and role == "user":
            convo["title"] = text[:40] + ("…" if len(text) > 40 else "")
        self._persist()

    def recent_for_prompt(self, conversation_id: str, n: int) -> list[dict]:
        convo = self.get_or_create(conversation_id)
        msgs = [m for m in convo["messages"] if m["role"] in ("user", "assistant")]
        return msgs[-n:]

    def search(self, query: str, limit: int = 12) -> str:
        q = query.lower().strip()
        if not q:
            return ""
        hits: list[str] = []
        for convo in sorted(self.conversations, key=lambda c: c["created_at"], reverse=True):
            for msg in convo["messages"]:
                if msg["role"] in ("user", "assistant") and q in msg["text"].lower():
                    hits.append(f"[{msg['role']}] {msg['text'][:300]}")
                    if len(hits) >= limit:
                        break
            if len(hits) >= limit:
                break
        return "\n".join(hits)


# --------------------------------------------------------------------------- task history


_WORD_RE = re.compile(r"\W+")


class TaskHistory:
    """Records successful automation runs, keyed by detected app — used to seed
    future runs of similar goals with a proven sequence of actions.
    """

    def __init__(self, path: str = config.TASK_HISTORY_FILE):
        self.path = path
        self.data: dict[str, list[dict]] = self._load()

    def _load(self) -> dict[str, list[dict]]:
        try:
            with open(self.path, "r") as f:
                return json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            return {}

    def _persist(self) -> None:
        _ensure_data_dir()
        with open(self.path, "w") as f:
            json.dump(self.data, f, indent=2)

    def record(self, goal: str, app: str, actions: list[str]) -> None:
        if not actions:
            return
        key = app or "General"
        record = {
            "goal": goal,
            "app": key,
            "date": datetime.now().strftime("%Y-%m-%d %H:%M"),
            "actions": actions,
        }
        records = self.data.setdefault(key, [])
        records[:] = [r for r in records if r["goal"].lower() != goal.lower()]
        records.insert(0, record)
        del records[10:]
        self._persist()

    def find_similar(self, goal: str) -> Optional[dict]:
        words = {w for w in _WORD_RE.split(goal.lower()) if len(w) > 2}
        best: Optional[dict] = None
        best_score = 1  # require at least 2 matching words
        for records in self.data.values():
            for r in records:
                r_words = {w for w in _WORD_RE.split(r["goal"].lower()) if len(w) > 2}
                score = len(words & r_words)
                if score > best_score:
                    best_score = score
                    best = r
        return best

    def as_text(self) -> str:
        if not self.data:
            return "(no task history yet)"
        sections = []
        for app, records in self.data.items():
            body = "\n\n".join(
                f"[{r['date']}] {r['goal']}\n"
                + "\n".join(f"  {i + 1}. {a}" for i, a in enumerate(r["actions"]))
                for r in records
            )
            sections.append(f"=== {app} ===\n{body}")
        return "\n\n".join(sections)


def new_session_id() -> str:
    return uuid.uuid4().hex


# Shared singletons — re-reading the file on every request is unnecessary; both
# stores persist on every write so the on-disk copy stays current.
conversation_store = ConversationStore()
task_history = TaskHistory()
