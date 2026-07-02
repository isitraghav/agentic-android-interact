package com.agent.nemotron

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.net.URLEncoder

class AgentAccessibilityService : AccessibilityService() {

    private val TAG = "AgentService"
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
        @Volatile var screenHeight: Int = 2400
        @Volatile var activityContext: android.content.Context? = null

    }

    override fun onServiceConnected() {
        instance = this
        screenHeight = resources.displayMetrics.heightPixels.takeIf { it > 0 } ?: 2400
    }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // --- Navigation helpers ---
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun showToast(text: String) {
        Log.d(TAG, "Toast: $text")
        NotificationHelper.showToast(this, text)
    }

    /** Returns installed apps that can handle text/plain share or smsto: intents. */
    fun listMessagingApps(): List<String> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<String>()
        val intents = listOf(
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" },
            Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:"))
        )
        for (intent in intents) {
            for (ri in packageManager.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (seen.add(pkg)) {
                    val label = ri.loadLabel(packageManager).toString()
                    results.add("$label ($pkg)")
                }
            }
        }
        return results
    }

    /** Looks up phone numbers by display name. Returns up to 5 "Name: number" matches. */
    fun searchContacts(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val results = mutableListOf<String>()
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$trimmed%")
        try {
            contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && results.size < 5) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    results.add("$name: $number")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "searchContacts: READ_CONTACTS permission not granted", e)
        }
        return results
    }

    fun openApp(pkgOrName: String): Boolean {
        Log.d(TAG, "openApp: attempting '$pkgOrName'")
        val query = pkgOrName.lowercase().trim()

        fun launch(pkg: String): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(i)
            Log.d(TAG, "openApp: launched '$pkg'")
            return true
        }

        // 1. Exact package name
        if (launch(pkgOrName)) return true

        // 2. Query only user-launchable apps (MAIN + LAUNCHER) — correct labels, no dead entries
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = packageManager.queryIntentActivities(launchIntent, 0)
        val candidates = launchable.map { ri ->
            val label = ri.loadLabel(packageManager).toString()
            Pair(ri.activityInfo.packageName, label)
        }

        // Exact label match first, then starts-with, then contains, then pkg contains
        val match = candidates.firstOrNull { (_, label) -> label.equals(query, ignoreCase = true) }
            ?: candidates.firstOrNull { (_, label) -> label.lowercase().startsWith(query) }
            ?: candidates.firstOrNull { (_, label) -> label.lowercase().contains(query) }
            ?: candidates.firstOrNull { (pkg, _) -> pkg.contains(query, ignoreCase = true) }

        if (match != null && launch(match.first)) return true
        Log.w(TAG, "openApp: no app found for '$pkgOrName'")
        return false
    }

    // --- UI tree ---
    private val uiCompiler = UiTreeCompiler()

    fun extractUiTree(): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = uiCompiler.compile(root, screenHeight)
        root.recycle()
        return elements
    }

    // --- Tool execution ---
    fun executeToolCall(call: ToolCall, elements: List<UiElement>): Boolean {
        return when (call.action) {
            "click_element" -> {
                val id = call.id ?: return false
                val node = uiCompiler.getNodeById(id) ?: findNodeByText(id)
                val nodeClicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                if (nodeClicked) true
                else elements.find { it.id == id }?.let { clickAt(it.centerX(), it.centerY()) } ?: false
            }
            "type_text" -> {
                val id = call.id ?: return false
                val node = uiCompiler.getNodeById(id)
                    ?: findNodeByText(id)
                    ?: elements.find { it.id == id }?.let { findNodeAt(it.centerX(), it.centerY()) }
                    ?: return false
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, call.text ?: "")
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            "scroll_screen" -> {
                val root = rootInActiveWindow ?: return false
                val action = if (call.direction == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                             else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                val scrollable = findScrollableNode(root) ?: root
                scrollable.performAction(action).also {
                    if (scrollable != root) scrollable.recycle()
                    root.recycle()
                }
            }
            "press_enter" -> {
                // If id given, target that field; otherwise use currently focused input
                val node = call.id?.takeIf { it.isNotBlank() }?.let { id ->
                    uiCompiler.getNodeById(id) ?: findNodeByText(id)
                } ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                // ACTION_IME_ENTER = 0x01000000 — submits search/go/done on keyboard
                node?.performAction(0x01000000) ?: false
            }
            "find_and_click" -> {
                val query = call.text ?: return false
                val node = findNodeByText(query)
                if (node != null) {
                    Log.d(TAG, "find_and_click: found '$query', clicking")
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK).also { node.recycle() }
                } else {
                    Log.w(TAG, "find_and_click: '$query' not found in tree")
                    false
                }
            }
            "send_message" -> {
                val phone = call.phone?.replace(Regex("[^+\\d]"), "") ?: return false
                val msg = call.text ?: ""
                val encoded = URLEncoder.encode(msg, "UTF-8")
                val appKey = call.pkg?.lowercase()?.trim() ?: "whatsapp"
                val pkgAliases = mapOf(
                    "whatsapp" to "com.whatsapp",
                    "wa" to "com.whatsapp",
                    "telegram" to "org.telegram.messenger",
                    "tg" to "org.telegram.messenger",
                    "signal" to "org.thoughtcrime.securesms",
                    "sms" to null,
                    "messages" to null,
                    "mms" to null,
                )
                val resolvedPkg = pkgAliases.getOrDefault(appKey, appKey.takeIf { it.contains(".") })
                val intent = when {
                    appKey in setOf("sms", "messages", "mms") ->
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
                            .apply { putExtra("sms_body", msg) }
                    appKey in setOf("whatsapp", "wa") || resolvedPkg == "com.whatsapp" ->
                        Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://api.whatsapp.com/send?phone=${phone.trimStart('+')}&text=$encoded"))
                    appKey in setOf("telegram", "tg") || resolvedPkg == "org.telegram.messenger" ->
                        Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=$phone&text=$encoded"))
                    appKey in setOf("signal") || resolvedPkg == "org.thoughtcrime.securesms" ->
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg)
                            setPackage("org.thoughtcrime.securesms")
                        }
                    else ->
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg)
                            resolvedPkg?.let { setPackage(it) }
                        }
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try { startActivity(intent); true } catch (e: Exception) {
                    Log.e(TAG, "send_message failed", e); false
                }
            }
            "open_app" -> openApp(call.pkg ?: call.text ?: return false)
            "press_back" -> pressBack()
            "press_home" -> { goHome(); true }
            "press_recents" -> pressRecents()
            "wait" -> true  // delay handled by engine
            "toast" -> { showToast(call.text ?: ""); true }
            else -> false
        }
    }

    private fun findNodeByText(query: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val results = root.findAccessibilityNodeInfosByText(query)
        root.recycle()
        if (results.isNullOrEmpty()) return null
        // prefer exact text match, fall back to first partial
        return results.firstOrNull { it.text?.toString().equals(query, ignoreCase = true)
                || it.contentDescription?.toString().equals(query, ignoreCase = true) }
            ?: results.first()
    }

    private fun findNodeAt(x: Float, y: Float): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeAtCoord(root, x.toInt(), y.toInt()).also { root.recycle() }
    }

    private fun findNodeAtCoord(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            findNodeAtCoord(child, x, y)?.let { return it }
            child.recycle()
        }
        return if (node.isClickable || node.isEditable) node else null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) { child.recycle(); return result }
            child.recycle()
        }
        return null
    }

    private fun clickAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
