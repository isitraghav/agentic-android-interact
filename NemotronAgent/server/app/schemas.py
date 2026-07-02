from typing import Literal, Optional

from pydantic import BaseModel, Field


class UiElement(BaseModel):
    id: str
    role: str
    label: str
    y: int
    clickable: bool = False
    editable: bool = False
    scrollable: bool = False


class ToolCall(BaseModel):
    action: str
    id: Optional[str] = None
    text: Optional[str] = None
    direction: Optional[str] = None
    pkg: Optional[str] = None
    ms: Optional[int] = None
    phone: Optional[str] = None


class ChatRequest(BaseModel):
    conversation_id: str = "default"
    message: str


class ChatResponse(BaseModel):
    type: Literal["answer", "automate"]
    text: str
    log: list[str] = Field(default_factory=list)


class ActionResult(BaseModel):
    """Echoes back the executed ToolCall plus its outcome, so the server can
    update action_log / detected_app and tell the model what happened.
    """

    action: str
    ok: bool
    id: Optional[str] = None
    text: Optional[str] = None
    direction: Optional[str] = None
    pkg: Optional[str] = None
    ms: Optional[int] = None
    current_pkg: Optional[str] = None
    result: Optional[str] = None


class AutomationStepRequest(BaseModel):
    session_id: Optional[str] = None
    goal: Optional[str] = None
    screen: list[UiElement] = Field(default_factory=list)
    action_results: list[ActionResult] = Field(default_factory=list)
    user_answer: Optional[str] = None


class AutomationStepResponse(BaseModel):
    session_id: str
    status: Literal["continue", "ask", "done"]
    tool_calls: list[ToolCall] = Field(default_factory=list)
    message: Optional[str] = None
    log: list[str] = Field(default_factory=list)
