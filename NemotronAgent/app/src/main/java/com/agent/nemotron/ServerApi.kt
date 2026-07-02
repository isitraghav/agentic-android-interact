package com.agent.nemotron

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to the local LangChain/LangGraph agent server (see /server). All LLM
 * reasoning — chat routing, memory, and the on-device automation loop — now
 * runs there; the phone only extracts the UI tree and executes tool calls.
 *
 * The server is reached via `adb reverse tcp:8000 tcp:8000`, so 127.0.0.1
 * from the device lands on the dev machine.
 */
class ServerApi(private val baseUrl: String = "http://127.0.0.1:8000") {

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class ChatRequest(
        @SerializedName("conversation_id") val conversationId: String,
        val message: String
    )

    data class ChatResponse(
        val type: String,
        val text: String,
        val log: List<String> = emptyList()
    )

    data class ActionResult(
        val action: String,
        val ok: Boolean,
        val id: String? = null,
        val text: String? = null,
        val direction: String? = null,
        val pkg: String? = null,
        val ms: Long? = null,
        @SerializedName("current_pkg") val currentPkg: String? = null,
        val result: String? = null
    )

    data class AutomationStepRequest(
        @SerializedName("session_id") val sessionId: String? = null,
        val goal: String? = null,
        val screen: List<UiElement> = emptyList(),
        @SerializedName("action_results") val actionResults: List<ActionResult> = emptyList(),
        @SerializedName("user_answer") val userAnswer: String? = null
    )

    data class AutomationStepResponse(
        @SerializedName("session_id") val sessionId: String,
        val status: String,
        @SerializedName("tool_calls") val toolCalls: List<ToolCall> = emptyList(),
        val message: String? = null,
        val log: List<String> = emptyList()
    )

    data class TasksResponse(val history: String)

    private fun <T> post(path: String, body: Any, responseType: Class<T>): T {
        val json = gson.toJson(body)
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $text")
            return gson.fromJson(text, responseType)
        }
    }

    private fun <T> get(path: String, responseType: Class<T>): T {
        val request = Request.Builder().url("$baseUrl$path").get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $text")
            return gson.fromJson(text, responseType)
        }
    }

    fun chat(conversationId: String, message: String): ChatResponse =
        post("/chat", ChatRequest(conversationId, message), ChatResponse::class.java)

    fun automationStep(req: AutomationStepRequest): AutomationStepResponse =
        post("/automation/step", req, AutomationStepResponse::class.java)

    fun taskHistory(): String = get("/tasks", TasksResponse::class.java).history

    fun memory(): String = get("/memory", Map::class.java)["memory"] as? String ?: ""
}
