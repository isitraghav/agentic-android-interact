package com.agent.nemotron

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Thin on-device shell around the LangChain/LangGraph agent server (see /server).
 * All LLM reasoning — chat routing, memory, and the automation decision loop —
 * happens server-side; this class extracts the UI tree, executes the tool calls
 * the server returns, and feeds the results back.
 */
class AgentEngine(private val filesDir: File) {

    private val TAG = "AgentEngine"
    private val api = ServerApi()
    private var job: Job? = null
    val conversations = ConversationStore(File(filesDir, "conversations.json"))

    fun chat(
        userMessage: String,
        onMessage: (ChatMessage) -> Unit,
        onLog: (String) -> Unit,
        onFinish: () -> Unit
    ) {
        job = CoroutineScope(Dispatchers.IO).launch {
            fun log(msg: String) { Log.d(TAG, msg); onLog(msg) }

            conversations.add(ChatMessage("user", userMessage))

            try {
                // "open/launch/start X (app)" — handle instantly without any server round trip.
                // Falls through to the normal flow if the message doesn't reduce to a single
                // app name (e.g. "open X and do Y").
                val openMatch = Regex("(?i)^(?:open|launch|start)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?$")
                    .find(userMessage.trim())
                if (openMatch != null) {
                    val service = AgentAccessibilityService.instance
                    val appName = openMatch.groupValues[1].trim()
                    if (service != null && appName.isNotBlank() && service.openApp(appName)) {
                        onMessage(ChatMessage("status", "▸ Opening $appName"))
                        service.showToast("Opening $appName")
                        reply("Done.", onMessage)
                        onFinish()
                        return@launch
                    }
                }

                val resp = api.chat(conversations.current.id, userMessage)
                resp.log.forEach { log(it) }

                when (resp.type) {
                    "automate" -> {
                        val goal = resp.text.ifBlank { userMessage }
                        val service = AgentAccessibilityService.instance
                        if (service == null) {
                            reply("I can't control the device — the Accessibility Service isn't enabled. " +
                                "Turn it on from Settings and try again.", onMessage)
                        } else {
                            onMessage(ChatMessage("status", "▸ $goal"))
                            val summary = runAutomation(goal, service, ::log)
                            reply(summary, onMessage)
                        }
                    }
                    else -> reply(resp.text.ifBlank { "Sorry, I didn't catch that." }, onMessage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chat error", e)
                reply("Error talking to agent server: ${e.javaClass.simpleName}: ${e.message} " +
                    "(is it running and reachable via `adb reverse tcp:8000 tcp:8000`?)", onMessage)
            }
            onFinish()
        }
    }

    private fun reply(text: String, onMessage: (ChatMessage) -> Unit) {
        val msg = ChatMessage("assistant", text)
        conversations.add(msg)
        onMessage(msg)
    }

    /**
     * Drives the on-device automation loop. Each iteration extracts the current
     * screen, sends it (plus the previous step's tool-call results) to
     * /automation/step, executes whatever tool calls come back, and repeats
     * until the server reports "done".
     */
    private suspend fun runAutomation(
        goal: String,
        service: AgentAccessibilityService,
        log: (String) -> Unit
    ): String {
        service.showToast("Agent started")
        log("Goal: $goal")
        service.goHome()
        delay(1500)

        var sessionId: String? = null
        var pendingResults = listOf<ServerApi.ActionResult>()
        var pendingAnswer: String? = null
        var resultText = "Done."
        var stepGuard = 0

        while (coroutineContext.isActive && stepGuard < 200) {
            stepGuard++
            val rawElements = service.extractUiTree()
            if (rawElements.isEmpty() && sessionId != null) {
                log("No elements. Waiting...")
                delay(1500)
                continue
            }

            val resp = try {
                api.automationStep(
                    ServerApi.AutomationStepRequest(
                        sessionId = sessionId,
                        goal = if (sessionId == null) goal else null,
                        screen = rawElements,
                        actionResults = pendingResults,
                        userAnswer = pendingAnswer
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
                log("Error: ${e.javaClass.simpleName}: ${e.message}")
                service.showToast("Error: ${e.javaClass.simpleName}")
                delay(3000)
                continue
            }

            sessionId = resp.sessionId
            pendingResults = emptyList()
            pendingAnswer = null
            resp.log.forEach { log(it) }

            var done = false
            for (call in resp.toolCalls) {
                log("→ ${call.action} pkg=${call.pkg} id=${call.id} text=${call.text}")
                when (call.action) {
                    "complete" -> {
                        service.showToast("Task complete!")
                        resultText = "Done."
                        done = true
                    }
                    "answer" -> {
                        service.showToast(call.text ?: "")
                        resultText = call.text ?: "Done."
                        done = true
                    }
                    "ask" -> {
                        val question = call.text ?: "What should I do?"
                        log("Asking user: $question")
                        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
                        AgentBridge.pendingQuestion = deferred
                        AgentBridge.currentQuestion = question
                        val intent = android.content.Intent(service, MainActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                     android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        service.startActivity(intent)
                        service.showToast("Agent is asking you something")
                        pendingAnswer = deferred.await()
                        AgentBridge.pendingQuestion = null
                        AgentBridge.currentQuestion = null
                        log("User answered: $pendingAnswer")
                    }
                    "wait" -> delay(call.ms ?: 1500L)
                    "toast" -> service.showToast(call.text ?: "")
                    "list_messaging_apps" -> {
                        val apps = service.listMessagingApps()
                        val summary = if (apps.isEmpty()) "No messaging apps found."
                                      else apps.joinToString("\n")
                        log("list_messaging_apps -> $summary")
                        pendingResults = pendingResults + ServerApi.ActionResult(
                            action = call.action, ok = apps.isNotEmpty(), result = summary
                        )
                    }
                    "send_message" -> {
                        val ok = service.executeToolCall(call, rawElements)
                        log("send_message: ok=$ok")
                        pendingResults = pendingResults + ServerApi.ActionResult(
                            action = call.action, ok = ok, text = call.text, pkg = call.pkg,
                            result = if (ok) "Chat opened with message pre-filled. Use find_and_click to tap the Send button."
                                     else "Failed to open messaging app."
                        )
                        delay(2000)
                    }
                    "search_contacts" -> {
                        val matches = service.searchContacts(call.text ?: "")
                        val summary = if (matches.isEmpty()) "No matching contacts." else matches.joinToString("\n")
                        log("Contacts: $summary")
                        pendingResults = pendingResults + ServerApi.ActionResult(
                            action = call.action, ok = matches.isNotEmpty(),
                            text = call.text, result = summary
                        )
                    }
                    else -> {
                        val ok = service.executeToolCall(call, rawElements)
                        log("Result: $ok")
                        val currentPkg = service.rootInActiveWindow
                            ?.packageName?.toString()?.substringAfterLast('.')
                        pendingResults = pendingResults + ServerApi.ActionResult(
                            action = call.action, ok = ok,
                            id = call.id, text = call.text, direction = call.direction,
                            pkg = call.pkg, ms = call.ms, currentPkg = currentPkg
                        )
                        val waitMs = if (call.action == "open_app") 3000L else 800L
                        delay(waitMs)
                    }
                }
                if (done) break
            }

            if (resp.status == "done") {
                resultText = resp.message ?: resultText
                return resultText
            }
            // "ask" loops again with pendingAnswer set; "continue" loops with pendingResults set.
        }

        if (stepGuard >= 200) {
            log("Max steps reached.")
            service.showToast("Agent stopped: max steps")
            return "I stopped after too many steps without finishing — want me to keep going or try something else?"
        }
        return resultText
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun historyAsText(): String = try { api.taskHistory() } catch (e: Exception) { "Error: ${e.message}" }

    fun memoryAsText(): String = try { api.memory() } catch (e: Exception) { "Error: ${e.message}" }
}
