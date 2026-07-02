import os
from dataclasses import dataclass, field
from typing import Any

from dotenv import load_dotenv

load_dotenv()


@dataclass
class LlmProvider:
    name: str
    base_url: str
    api_key: str
    model: str
    max_tokens: int = 8192
    temperature: float = 1.0
    extra_body: dict[str, Any] = field(default_factory=dict)


NVIDIA = LlmProvider(
    name="NVIDIA / MiniMax M3",
    base_url="https://integrate.api.nvidia.com/v1",
    api_key=os.environ.get("NVIDIA_API_KEY", ""),
    model="minimaxai/minimax-m3",
    extra_body={"chat_template_kwargs": {"thinking_mode": "on"}},
)

GROQ = LlmProvider(
    name="Groq / Llama 3.3 70B",
    base_url="https://api.groq.com/openai/v1",
    api_key=os.environ.get("GROQ_API_KEY", ""),
    model="llama-3.3-70b-versatile",
    max_tokens=8192,
    temperature=1.0,
)

OLLAMA = LlmProvider(
    name="Ollama / Qwen2.5 3B",
    base_url=os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434/v1"),
    api_key="ollama",
    model=os.environ.get("OLLAMA_MODEL", "qwen2.5:7b-instruct"),
    max_tokens=8192,
    temperature=1.0,
)

ALL_PROVIDERS = [NVIDIA, GROQ, OLLAMA]

DATA_DIR = os.environ.get("DATA_DIR", "./data")
MEMORY_FILE = os.path.join(DATA_DIR, "agent_memory.txt")
CONVERSATIONS_FILE = os.path.join(DATA_DIR, "conversations.json")
TASK_HISTORY_FILE = os.path.join(DATA_DIR, "task_history.json")

HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8000"))
