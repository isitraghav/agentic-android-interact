package com.agent.nemotron

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.agent.nemotron.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine by lazy { AgentEngine(filesDir) }
    private val chatAdapter = ChatAdapter()
    private val conversationAdapter = ConversationAdapter { convo -> switchConversation(convo) }
    private var running = false

    private enum class Panel { CHAT, LOGS, MEMORY, HISTORY }
    private var activePanel = Panel.CHAT

    private val assistantRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val granted = result.resultCode == RESULT_OK
        Toast.makeText(this,
            if (granted) "CryoAgent is now your default assistant" else "Not set as default assistant",
            Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AgentAccessibilityService.activityContext = this
        NotificationHelper.createChannel(this)
        requestNotificationPermission()

        setSupportActionBar(binding.toolbar)

        setupChat()
        setupDrawer()

        binding.stopFab.setOnClickListener { stopAgent() }

        updateServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        AgentAccessibilityService.activityContext = this
        updateServiceStatus()
        AgentBridge.currentQuestion?.let { question ->
            if (AgentBridge.pendingQuestion?.isActive == true) showAskDialog(question)
        }
        if (activePanel == Panel.MEMORY) refreshMemory()
        if (activePanel == Panel.HISTORY) refreshHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AgentAccessibilityService.activityContext == this)
            AgentAccessibilityService.activityContext = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_menu -> { binding.drawerLayout.openDrawer(GravityCompat.END); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ===================== Chat =====================

    private fun setupChat() {
        binding.chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatRecycler.adapter = chatAdapter
        chatAdapter.setItems(engine.conversations.current.messages)
        scrollChatToBottom()

        binding.sendBtn.setOnClickListener { sendChatMessage() }
        binding.chatInput.setOnEditorActionListener { _, _, _ -> sendChatMessage(); true }
    }

    private fun sendChatMessage() {
        val text = binding.chatInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.chatInput.setText("")

        chatAdapter.append(ChatMessage("user", text))
        scrollChatToBottom()

        setRunning(true)
        engine.chat(
            userMessage = text,
            onMessage = { msg ->
                runOnUiThread {
                    chatAdapter.append(msg)
                    scrollChatToBottom()
                    refreshConversationList()
                }
            },
            onLog = { line -> runOnUiThread { appendLog(line) } },
            onFinish = { runOnUiThread { setRunning(false) } }
        )
    }

    private fun setRunning(value: Boolean) {
        running = value
        binding.stopFab.visibility = if (value) View.VISIBLE else View.GONE
        binding.sendBtn.isEnabled = !value
    }

    private fun stopAgent() {
        engine.stop()
        setRunning(false)
        chatAdapter.append(ChatMessage("status", "■ Stopped"))
        scrollChatToBottom()
        AgentAccessibilityService.instance?.showToast("Agent stopped")
    }

    private fun scrollChatToBottom() {
        if (chatAdapter.itemCount > 0) binding.chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
    }

    // ===================== Drawer / Conversations =====================

    private fun setupDrawer() {
        binding.conversationRecycler.layoutManager = LinearLayoutManager(this)
        binding.conversationRecycler.adapter = conversationAdapter
        refreshConversationList()

        binding.newChatBtn.setOnClickListener { startNewChat() }

        binding.navLogs.setOnClickListener { showPanel(Panel.LOGS); closeDrawer() }
        binding.navMemory.setOnClickListener { showPanel(Panel.MEMORY); closeDrawer() }
        binding.navHistory.setOnClickListener { showPanel(Panel.HISTORY); closeDrawer() }
        binding.navSettings.setOnClickListener { showSettingsDialog(); closeDrawer() }
    }

    private fun closeDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.END)
    }

    private fun refreshConversationList() {
        conversationAdapter.setItems(engine.conversations.all())
    }

    private fun startNewChat() {
        engine.conversations.newConversation()
        chatAdapter.clear()
        refreshConversationList()
        showPanel(Panel.CHAT)
        closeDrawer()
    }

    private fun switchConversation(convo: Conversation) {
        engine.conversations.switchTo(convo.id)
        chatAdapter.setItems(engine.conversations.current.messages)
        scrollChatToBottom()
        showPanel(Panel.CHAT)
        closeDrawer()
    }

    // ===================== Panels =====================

    private fun showPanel(panel: Panel) {
        activePanel = panel
        binding.chatPanel.visibility = if (panel == Panel.CHAT) View.VISIBLE else View.GONE
        binding.logsPanel.visibility = if (panel == Panel.LOGS) View.VISIBLE else View.GONE
        binding.memoryPanel.visibility = if (panel == Panel.MEMORY) View.VISIBLE else View.GONE
        binding.historyPanel.visibility = if (panel == Panel.HISTORY) View.VISIBLE else View.GONE
        binding.stopFab.visibility = if (running && panel == Panel.CHAT) View.VISIBLE else View.GONE
        when (panel) {
            Panel.MEMORY -> refreshMemory()
            Panel.HISTORY -> refreshHistory()
            else -> Unit
        }
    }

    private fun refreshMemory() {
        Thread {
            val text = engine.memoryAsText().ifBlank { "(empty)" }
            runOnUiThread { binding.memoryView.text = text }
        }.start()
    }

    private fun refreshHistory() {
        Thread {
            val text = engine.historyAsText()
            runOnUiThread { binding.historyView.text = text }
        }.start()
    }

    private fun appendLog(msg: String) {
        binding.logView.append("$msg\n")
        val scrollAmount = binding.logView.layout?.getLineTop(binding.logView.lineCount) ?: 0
        binding.logView.scrollTo(0, (scrollAmount - binding.logView.height).coerceAtLeast(0))
    }

    // ===================== Settings dialog =====================

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val accessibilityBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.accessibilityBtn)
        val assistantBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.assistantBtn)

        accessibilityBtn.text = if (isAccessibilityEnabled()) "Accessibility Settings" else "Enable Accessibility Service"
        accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        assistantBtn.setOnClickListener { requestAssistantRole() }

        MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun requestAssistantRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            when {
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) ->
                    Toast.makeText(this, "CryoAgent is already your default assistant", Toast.LENGTH_SHORT).show()
                roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) ->
                    assistantRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                else ->
                    startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        } else {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }
    }

    // ===================== Misc =====================

    private fun requestNotificationPermission() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_CONTACTS)
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun showAskDialog(question: String) {
        val input = EditText(this).apply {
            hint = "Your answer..."
            setPadding(48, 24, 48, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Agent is asking")
            .setMessage(question)
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                AgentBridge.pendingQuestion?.complete(input.text.toString().trim())
            }
            .setNegativeButton("Skip") { _, _ ->
                AgentBridge.pendingQuestion?.complete("(no answer)")
            }
            .setCancelable(false)
            .show()
    }

    private fun updateServiceStatus() {
        val enabled = isAccessibilityEnabled()
        binding.toolbar.subtitle = if (enabled) "Service: Connected" else "Service: Disabled — open Settings"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val services = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return services.contains(packageName)
    }
}
