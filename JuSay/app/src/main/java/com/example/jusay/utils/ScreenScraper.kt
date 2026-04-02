package com.example.jusay.utils

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object ScreenScraper {

    fun scrapeToJson(root: AccessibilityNodeInfo?): String {
        if (root == null) return "[]"

        val compressed = JSONArray()
        appendNodeRecursive(root, compressed)
        return compressed.toString()
    }

    private fun appendNodeRecursive(node: AccessibilityNodeInfo, out: JSONArray) {
        val viewId = node.viewIdResourceName?.trim().orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val contentDescription = node.contentDescription?.toString()?.trim().orEmpty()
        val isClickable = node.isClickable

        // Skip nodes that have no useful content and no interactive value.
        val isEmpty = viewId.isEmpty() && text.isEmpty() && contentDescription.isEmpty() && !isClickable
        if (!isEmpty) {
            val item = JSONObject()
            if (viewId.isNotEmpty()) item.put("viewIdResourceName", viewId)
            if (text.isNotEmpty()) item.put("text", text)
            if (contentDescription.isNotEmpty()) item.put("contentDescription", contentDescription)
            item.put("isClickable", isClickable)
            out.put(item)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                appendNodeRecursive(child, out)
            } finally {
                child.recycle()
            }
        }
    }
}
