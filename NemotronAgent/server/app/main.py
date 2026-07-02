"""FastAPI entry point.

Run with: uv run uvicorn app.main:app --host 0.0.0.0 --port 8000

Endpoints:
  POST /chat               - conversational turn -> {"type": "answer"|"automate", "text": ...}
  POST /automation/step    - one step of the on-device automation loop
  GET  /memory             - dump saved memory (debug)
  GET  /tasks               - dump task history (debug)
  GET  /health
"""

import logging

from fastapi import FastAPI, HTTPException

from . import stores
from .graphs.automation_graph import run_step
from .graphs.chat_graph import run_chat
from .schemas import AutomationStepRequest, AutomationStepResponse, ChatRequest, ChatResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
logger = logging.getLogger("agent")

app = FastAPI(title="Nemotron Agent Server")


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest):
    logger.info("CHAT [%s] <- %r", req.conversation_id, req.message)
    result, log = run_chat(req.conversation_id, req.message)
    for line in log:
        logger.info("  %s", line)
    logger.info("CHAT [%s] -> %s: %r", req.conversation_id, result["type"], result["text"])
    return ChatResponse(type=result["type"], text=result["text"], log=log)


@app.post("/automation/step", response_model=AutomationStepResponse)
def automation_step(req: AutomationStepRequest):
    logger.info(
        "STEP [%s] goal=%r screen=%d elements, action_results=%s, user_answer=%r",
        req.session_id, req.goal, len(req.screen), [a.action for a in req.action_results], req.user_answer,
    )
    try:
        resp = run_step(req)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    for line in resp.log:
        logger.info("  %s", line)
    logger.info(
        "STEP [%s] -> status=%s tool_calls=%s message=%r",
        resp.session_id, resp.status, [(c.action, c.text) for c in resp.tool_calls], resp.message,
    )
    return resp


@app.get("/memory")
def get_memory():
    return {"memory": stores.read_memory()}


@app.get("/tasks")
def get_tasks():
    return {"history": stores.task_history.as_text()}
