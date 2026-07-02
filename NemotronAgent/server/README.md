# Nemotron Agent Server

Local LangChain/LangGraph server that runs the agentic logic previously baked
into the Android app (`AgentEngine.kt`, `Tools.kt`, `NvidiaNemotronClient.kt`).
The phone now only handles UI extraction + execution; all LLM reasoning runs
here.

## Setup

```bash
cd server
cp .env.example .env   # fill in NVIDIA_API_KEY / GROQ_API_KEY
uv sync
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## Expose to the phone over adb

```bash
adb reverse tcp:8000 tcp:8000
```

The app then talks to `http://127.0.0.1:8000` on the device, which adb
tunnels to this server on your machine.

## Endpoints

### `POST /chat`
```json
{"conversation_id": "default", "message": "text the user typed/said"}
```
Returns `{"type": "answer", "text": "..."}` for a direct reply, or
`{"type": "automate", "text": "<goal>"}` when the device should run the
automation loop for that goal.

### `POST /automation/step`
Drives one step of the on-device automation loop.

First call (new session):
```json
{"goal": "send Shiv a WhatsApp message saying I'm running late", "screen": [...]}
```

Subsequent calls:
```json
{
  "session_id": "...",
  "screen": [...],
  "action_results": [{"action": "click_element", "id": "12", "ok": true}]
}
```

If the previous response had `status: "ask"`, resume with:
```json
{"session_id": "...", "user_answer": "...", "screen": [...]}
```

Response:
```json
{"session_id": "...", "status": "continue|ask|done", "tool_calls": [...], "message": null}
```

- `status: "continue"` — execute `tool_calls` in order, then call
  `/automation/step` again with `action_results` and the new screen.
- `status: "ask"` — show `message` to the user, then resume with
  `user_answer`.
- `status: "done"` — `message` is the final result to show the user; the
  session is closed server-side.

`screen` is the same flat UI-tree JSON the app already builds
(`id`, `role`, `label`, `y`, `clickable`).

## Data

`memory.txt`, `conversations.json` and `task_history.json` live under
`./data` (configurable via `DATA_DIR`) and replace the equivalent files the
app used to keep in its own storage.
