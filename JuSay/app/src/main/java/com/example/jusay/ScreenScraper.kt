package com.example.jusay

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object ScreenScraper {
    fun scrapeToJson(root: AccessibilityNodeInfo?): String {
        if (root == null) return "[]"

        val node = buildNode(root)
        val out = JSONArray()
        if (node != null) {
            out.put(node)
        }
        return out.toString()
    }

    private fun buildNode(node: AccessibilityNodeInfo): JSONObject? {
        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childJson = buildNode(child)
            child.recycle()
            if (childJson != null) {
                children.put(childJson)
            }
        }

        val viewId = node.viewIdResourceName?.trim().orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val contentDescription = node.contentDescription?.toString()?.trim().orEmpty()
        val clickable = node.isClickable

        val hasNodeData =
            viewId.isNotEmpty() || text.isNotEmpty() || contentDescription.isNotEmpty() || clickable

        if (!hasNodeData && children.length() == 0) {
            return null
        }

        val json = JSONObject()
        if (viewId.isNotEmpty()) json.put("viewIdResourceName", viewId)
        if (text.isNotEmpty()) json.put("text", text)
        if (contentDescription.isNotEmpty()) json.put("contentDescription", contentDescription)
        if (clickable) json.put("isClickable", true)
        if (children.length() > 0) json.put("children", children)

        return json
    }
}
