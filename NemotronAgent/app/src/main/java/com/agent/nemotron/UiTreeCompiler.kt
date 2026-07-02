package com.agent.nemotron

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson

/**
 * Flattens an Accessibility node tree into a dense semantic JSON array for the LLM.
 *
 * Splits "nested interactables" (e.g. a clickable chat row containing a clickable
 * avatar ImageView) into separate flat elements, each with its own id, so the model
 * can address either target directly instead of guessing what a grouped row does.
 */
class UiTreeCompiler {

    private val gson = Gson()
    private var idCounter = 0
    private var screenHeight = 1
    private val elements = mutableListOf<UiElement>()
    private val nodeRegistry = mutableMapOf<String, AccessibilityNodeInfo>()

    fun reset() {
        idCounter = 0
        elements.clear()
        nodeRegistry.clear()
    }

    /** Compiles the tree rooted at [root] into a flat list of actionable [UiElement]s. */
    fun compile(root: AccessibilityNodeInfo, screenHeight: Int): List<UiElement> {
        reset()
        this.screenHeight = screenHeight.coerceAtLeast(1)
        extractNode(root, depth = 0)
        return elements.toList()
    }

    fun extractNode(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > 40) return
        if (node.packageName?.toString() == SELF_PACKAGE) return

        if (node.isClickable || node.isEditable || node.isCheckable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val id = (idCounter++).toString()
                val cy = (bounds.top + bounds.bottom) / 2
                val yPct = (cy * 100 / screenHeight).coerceIn(0, 100)
                elements.add(UiElement(
                    id = id,
                    role = determineRole(node),
                    label = getMeaningfulText(node),
                    y = yPct,
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    bounds = intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
                ))
                nodeRegistry[id] = AccessibilityNodeInfo.obtain(node)
            }
        }

        for (i in 0 until node.childCount) {
            extractNode(node.getChild(i), depth + 1)
        }
    }

    /** Maps Android behavioral flags / class names to a primitive semantic role. */
    private fun determineRole(node: AccessibilityNodeInfo): String {
        if (node.isEditable) return "input"
        if (node.isCheckable) return "toggle"
        if (node.isScrollable) return "scrollable_container"

        val className = node.className?.toString() ?: ""
        return when {
            className.contains("EditText") -> "input"
            className.contains("Button") -> "button"
            className.contains("ImageView") -> "image"
            className.contains("CheckBox") || className.contains("Switch") -> "toggle"
            className.contains("SeekBar") -> "slider"
            className.contains("TextView") -> "text"
            node.isClickable -> "clickable_area"
            else -> "unknown"
        }
    }

    /** Waterfall: contentDescription -> text -> hintText -> non-clickable children -> "unlabeled". */
    private fun getMeaningfulText(node: AccessibilityNodeInfo): String {
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it.take(60) }
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it.take(60) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { return it.take(60) }
        }
        if (node.childCount > 0) {
            val childText = getChildTextRecursively(node)
            if (childText.isNotBlank()) return childText.take(80)
        }
        return "unlabeled"
    }

    /** Gathers text from non-clickable descendants only — clickable ones get their own element. */
    private fun getChildTextRecursively(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isClickable) {
                val text = getMeaningfulText(child)
                if (text != "unlabeled") sb.append(text).append(" ")
            }
        }
        return sb.toString().trim()
    }

    /** Looks up the live node for an element id assigned during the last [compile]. */
    fun getNodeById(id: String): AccessibilityNodeInfo? = nodeRegistry[id]

    /** Serializes the compiled elements to a flat JSON array for the LLM. */
    fun getSemanticJsonPayload(): String = gson.toJson(elements)

    companion object {
        private const val SELF_PACKAGE = "com.agent.nemotron"
    }
}
