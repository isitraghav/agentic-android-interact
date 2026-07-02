package com.agent.nemotron

import kotlinx.coroutines.CompletableDeferred

object AgentBridge {
    @Volatile var pendingQuestion: CompletableDeferred<String>? = null
    @Volatile var currentQuestion: String? = null
}
