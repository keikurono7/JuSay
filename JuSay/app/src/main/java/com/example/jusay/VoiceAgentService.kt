package com.example.jusay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.jusay.utils.SafetyWall
import com.example.jusay.utils.ScreenScraper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.regex.Pattern

class VoiceAgentService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val systemPrompt = (
        "You are an intent-to-action planner for mobile UI automation. " +
            "You will receive: (1) a JSON screen UI tree, (2) a spoken user intent, (3) plan step number, and (4) prior action history. " +
            "Analyze the CURRENT screen and return ONLY THE NEXT BEST ACTION as a JSON object with this exact shape: " +
            "{\"action\": \"click|type|scroll|wait|launch|home|back\", \"target_id\": \"view_id_or_text_or_app_name\", \"input_text\": \"text_to_type_if_any\"}. " +
            "Do not include markdown, explanations, code fences, or extra keys. " +
            "Never output API keys, tokens, passwords, or long secret-like strings as target_id. " +
            "Prefer stable view IDs or short visible labels. " +
            "For open-app intents, you may use home/back/scroll/launch as needed across steps. " +
            "Avoid interacting with controller app UI unless explicitly requested."
        )

    private val sectionSelectorPrompt = (
        "You are a UI section selector for mobile automation planning. " +
            "You will receive a spoken intent and a list of section headings with short summaries. " +
            "Choose the single most relevant section id for the next action. " +
            "Return ONLY JSON with exact shape: {\"section\":\"section_id\"}. " +
            "Never add explanation, markdown, or extra keys."
        )

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        ensureStatusChannel()
        setupSpeechRecognizer()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally minimal: command processing is triggered manually.
    }

    override fun onInterrupt() {
        speechRecognizer?.cancel()
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    fun triggerVoiceCommandListening(): String {
        val agentEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_AGENT_ENABLED, true)
        if (!agentEnabled) {
            showToast("Voice agent is OFF")
            return "Voice agent is OFF"
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showToast("Microphone permission is missing")
            return "Microphone permission is missing"
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showToast("Speech recognition unavailable on this device")
            return "Speech recognition unavailable"
        }

        if (isListening) {
            showToast("Already listening")
            return "Already listening"
        }

        val recognizer = speechRecognizer ?: run {
            setupSpeechRecognizer()
            speechRecognizer
        } ?: return "Speech recognizer not ready"

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        return try {
            recognizer.startListening(intent)
            isListening = true
            showToast("Listening... speak now")
            "Listening... speak now"
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to start listening", exc)
            showToast("Failed to start listening")
            "Failed to start listening"
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Speech client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing"
                        SpeechRecognizer.ERROR_NETWORK -> "Speech network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Speech server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        else -> "Speech error: $error"
                    }
                    showToast(message)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val spokenCommand = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    if (spokenCommand.isBlank()) return
                    showToast("Heard: $spokenCommand")
                    processSpokenCommand(spokenCommand)
                }
            })
        }
    }

    private fun processSpokenCommand(spokenCommand: String) {
        Log.i(TAG, "VOICE_INPUT raw=$spokenCommand")

        val directSearch = extractDirectWebSearchQuery(spokenCommand)
        if (directSearch != null) {
            val launched = launchDirectWebSearch(directSearch)
            if (launched) {
                showToast("Searching: $directSearch")
            } else {
                showToast("Unable to start web search")
            }
            return
        }

        val apiKey = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_GROQ_API_KEY, null)
            ?.trim()
            .orEmpty()

        if (apiKey.isBlank()) {
            showToast("Save your Groq API key first")
            return
        }

        runPlanningStep(
            spokenCommand = spokenCommand,
            apiKey = apiKey,
            step = 1,
            history = mutableListOf(),
        )
    }

    private fun runPlanningStep(
        spokenCommand: String,
        apiKey: String,
        step: Int,
        history: MutableList<String>,
    ) {
        if (step > MAX_PLAN_STEPS) {
            showToast("Stopped after max steps")
            Log.i(TAG, "PLAN_STOP reason=max_steps")
            return
        }

        val root = rootInActiveWindow ?: run {
            showToast("No active window")
            Log.i(TAG, "PLAN_STOP reason=no_active_window")
            return
        }

        val currentPackage = root.packageName?.toString().orEmpty()
        if (SafetyWall.blockedPackages.contains(currentPackage)) {
            showToast("Blocked in this app: $currentPackage")
            root.recycle()
            return
        }

        val screenJson = ScreenScraper.scrapeToJson(root)
        logLarge("SCREEN_TREE", screenJson)
        root.recycle()

        requestActionFromGroq(
            apiKey = apiKey,
            spokenCommand = spokenCommand,
            screenJson = screenJson,
            step = step,
            history = history,
        ) { actionJson ->
            if (actionJson == null) {
                showToast("No valid action from model")
                return@requestActionFromGroq
            }

            applyIntentOverrides(spokenCommand, history, actionJson)

            val action = actionJson.optString("action").lowercase(Locale.US)
            val targetId = actionJson.optString("target_id")
            val inputText = actionJson.optString("input_text")

            handleBackendAction(actionJson) { success, executedAction ->
                val previousHistoryEntry = history.lastOrNull()
                history.add(
                    "step=$step action=$executedAction target=$targetId success=$success input=${inputText.take(40)}",
                )

                val continuePlan = shouldContinuePlanning(
                    spokenCommand = spokenCommand,
                    success = success,
                    executedAction = executedAction,
                    targetId = targetId,
                    previousHistoryEntry = previousHistoryEntry,
                )
                if (continuePlan) {
                    mainHandler.postDelayed(
                        {
                            runPlanningStep(
                                spokenCommand = spokenCommand,
                                apiKey = apiKey,
                                step = step + 1,
                                history = history,
                            )
                        },
                        PLAN_STEP_DELAY_MS,
                    )
                } else {
                    Log.i(TAG, "PLAN_STOP reason=action_terminal action=$executedAction success=$success")
                }
            }
        }
    }

    private fun requestActionFromGroq(
        apiKey: String,
        spokenCommand: String,
        screenJson: String,
        step: Int,
        history: List<String>,
        onActionReady: (JSONObject?) -> Unit,
    ) {
        showToast("Sending intent to Groq")
        Log.i(TAG, "Groq request start. model=$MODEL_NAME, apiKey=${redactApiKey(apiKey)}, spokenLen=${spokenCommand.length}, screenLen=${screenJson.length}")
        val uiSections = buildUiSections(screenJson)
        val sectionHeadings = JSONArray().apply {
            uiSections.forEach { section ->
                put(
                    JSONObject().apply {
                        put("id", section.id)
                        put("heading", section.heading)
                        put("summary", section.summary)
                    },
                )
            }
        }

        val selectorPayload = JSONObject().apply {
            put("spoken_intent", spokenCommand)
            put("plan_step", step)
            put("history", JSONArray(history))
            put("sections", sectionHeadings)
        }

        callGroqForJsonObject(
            apiKey = apiKey,
            systemPrompt = sectionSelectorPrompt,
            userPayload = selectorPayload,
            requestLabel = "GROQ_SELECTOR_REQUEST_JSON",
            responseLabel = "GROQ_SELECTOR_RESPONSE_JSON",
        ) { selectorJson ->
            if (selectorJson == null) {
                onActionReady(null)
                return@callGroqForJsonObject
            }

            val selectedSection = pickSection(uiSections, selectorJson)
            val plannerPayload = JSONObject().apply {
                put("spoken_intent", spokenCommand)
                put("plan_step", step)
                put("history", JSONArray(history))
                put("selected_section", selectedSection.id)
                put("section_heading", selectedSection.heading)
                put("ui_tree", selectedSection.nodes)
            }

            callGroqForJsonObject(
                apiKey = apiKey,
                systemPrompt = systemPrompt,
                userPayload = plannerPayload,
                requestLabel = "GROQ_REQUEST_JSON",
                responseLabel = "GROQ_RESPONSE_JSON",
            ) { actionJson ->
                if (actionJson == null) {
                    onActionReady(null)
                    return@callGroqForJsonObject
                }

                Log.i(TAG, "Parsed action JSON=$actionJson")
                onActionReady(actionJson)
            }
        }
    }

    private fun callGroqForJsonObject(
        apiKey: String,
        systemPrompt: String,
        userPayload: JSONObject,
        requestLabel: String,
        responseLabel: String,
        onReady: (JSONObject?) -> Unit,
    ) {
        val requestJson = JSONObject().apply {
            put("model", MODEL_NAME)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", userPayload.toString())
                        },
                    )
                },
            )
            put("temperature", 0)
        }

        val requestBodyString = requestJson.toString()
        logLarge(requestLabel, requestBodyString)

        val body = requestBodyString
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(GROQ_CHAT_COMPLETIONS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Groq call failed", e)
                showToast("Groq request failed")
                onReady(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string().orEmpty()
                    Log.i(TAG, "Groq response code=${it.code}")
                    logLarge(responseLabel, responseBody)

                    if (!it.isSuccessful) {
                        Log.e(TAG, "Groq non-success response. code=${it.code}, body=$responseBody")
                        showToast("Groq error: ${it.code}")
                        onReady(null)
                        return
                    }

                    val payload = responseBody
                    if (payload.isBlank()) {
                        showToast("Groq returned empty response")
                        onReady(null)
                        return
                    }

                    val parsed = extractJsonObjectFromGroq(payload)
                    if (parsed == null) {
                        Log.e(TAG, "Failed to extract JSON object from Groq payload=$payload")
                        showToast("Invalid Groq response")
                        onReady(null)
                        return
                    }

                    onReady(parsed)
                }
            }
        })
    }

    private fun extractJsonObjectFromGroq(groqPayload: String): JSONObject? {
        val outer = runCatching { JSONObject(groqPayload) }.getOrNull() ?: return null
        val choices = outer.optJSONArray("choices") ?: return null
        val firstChoice = choices.optJSONObject(0) ?: return null
        val message = firstChoice.optJSONObject("message") ?: return null
        val content = message.optString("content").trim()
        if (content.isBlank()) return null

        return runCatching { JSONObject(content) }.getOrNull()
    }

    private fun handleBackendAction(
        actionJson: JSONObject,
        onComplete: ((success: Boolean, action: String) -> Unit)? = null,
    ) {
        val action = actionJson.optString("action").lowercase(Locale.US)
        val targetId = actionJson.optString("target_id")
        val inputText = actionJson.optString("input_text")

        if (isSensitiveTarget(targetId)) {
            showToast("Blocked sensitive target")
            Log.w(TAG, "Blocked sensitive target_id=$targetId")
            onComplete?.invoke(false, action)
            return
        }

        val targetLower = targetId.lowercase(Locale.US)
        val isBlockedTarget = SafetyWall.blockedKeywords.any { keyword ->
            targetLower.contains(keyword)
        }
        if (isBlockedTarget) {
            showToast("Blocked unsafe target")
            onComplete?.invoke(false, action)
            return
        }

        mainHandler.post {
            val root = rootInActiveWindow ?: return@post
            try {
                val targetNode = findNode(root, targetId)
                Log.i(
                    TAG,
                    "EXECUTION_PLAN action=$action, target_id=$targetId, input_text=${inputText.take(100)}, resolvedNode=${describeNode(targetNode)}",
                )

                val activePackage = root.packageName?.toString().orEmpty()
                val blocksOwnUiInteraction = activePackage == packageName && action in setOf("click", "type", "scroll")
                if (blocksOwnUiInteraction) {
                    Log.i(TAG, "EXECUTION_RESULT action=$action, success=false, reason=controller_ui_block")
                    showToast("Blocked control-panel self interaction")
                    onComplete?.invoke(false, action)
                    return@post
                }

                when (action) {
                    "click" -> {
                        val success = targetNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                        Log.i(TAG, "EXECUTION_RESULT action=click, target_id=$targetId, success=$success")
                        if (success) {
                            showToast("Clicked: $targetId")
                        } else {
                            showToast("Unable to click target")
                        }
                        onComplete?.invoke(success, action)
                    }
                    "type" -> {
                        val editableTarget = findEditableTarget(root, targetId, targetNode)
                        if (editableTarget != null) {
                            editableTarget.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                            val args = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    inputText,
                                )
                            }
                            val success = editableTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            Log.i(
                                TAG,
                                "EXECUTION_RESULT action=type, target_id=$targetId, effectiveTarget=${describeNode(editableTarget)}, success=$success",
                            )
                            if (success) {
                                showToast("Typed into: $targetId")
                            } else {
                                showToast("Unable to type into target")
                            }
                            onComplete?.invoke(success, action)
                        } else {
                            Log.i(TAG, "EXECUTION_RESULT action=type, target_id=$targetId, success=false, reason=no_editable_target")
                            showToast("Type target not found")
                            onComplete?.invoke(false, action)
                        }
                    }
                    "scroll" -> {
                        val targetLower = targetId.lowercase(Locale.US)
                        val useBackward = targetLower.contains("left") || targetLower.contains("up") || targetLower.contains("back")
                        val horizontalNavigation =
                            targetLower.contains("left") || targetLower.contains("right") || targetLower.contains("page")
                        val scrollAction = if (useBackward) {
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        } else {
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        }

                        val scrollNode = findScrollableNode(targetNode) ?: findFirstScrollableNode(root)
                        var success = when {
                            scrollNode != null -> scrollNode.performAction(scrollAction)
                            targetNode != null -> targetNode.performAction(scrollAction)
                            else -> root.performAction(scrollAction)
                        }

                        if (!success) {
                            success = performDirectionalSwipe(useBackward = useBackward, horizontal = horizontalNavigation)
                        }

                        Log.i(TAG, "EXECUTION_RESULT action=scroll, target_id=$targetId, success=$success")
                        if (success) {
                            showToast("Scrolled")
                        } else {
                            showToast("Unable to scroll")
                        }
                        onComplete?.invoke(success, action)
                    }
                    "launch" -> {
                        val success = launchAppByQuery(targetId)
                        Log.i(TAG, "EXECUTION_RESULT action=launch, target_id=$targetId, success=$success")
                        if (success) {
                            showToast("Opening $targetId")
                        } else {
                            showToast("App not found: $targetId")
                        }
                        onComplete?.invoke(success, action)
                    }
                    "home" -> {
                        val success = performGlobalAction(GLOBAL_ACTION_HOME)
                        Log.i(TAG, "EXECUTION_RESULT action=home, success=$success")
                        if (!success) showToast("Unable to go Home")
                        onComplete?.invoke(success, action)
                    }
                    "back" -> {
                        val success = performGlobalAction(GLOBAL_ACTION_BACK)
                        Log.i(TAG, "EXECUTION_RESULT action=back, success=$success")
                        if (!success) showToast("Unable to go Back")
                        onComplete?.invoke(success, action)
                    }
                    "wait" -> {
                        Log.i(TAG, "EXECUTION_RESULT action=wait, success=true")
                        showToast("Waiting")
                        onComplete?.invoke(true, action)
                    }
                    else -> {
                        Log.i(TAG, "EXECUTION_RESULT action=$action, success=false, reason=unsupported_action")
                        showToast("Unsupported action: $action")
                        onComplete?.invoke(false, action)
                    }
                }
            } finally {
                root.recycle()
            }
        }
    }

    private fun showToast(message: String) {
        Log.i(TAG, "STATUS $message")
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        postStatusNotification(message)
    }

    private fun redactApiKey(value: String): String {
        if (value.length <= 8) return "***"
        return "${value.take(4)}...${value.takeLast(4)}"
    }

    private fun launchAppByQuery(query: String): Boolean {
        val candidates = buildLaunchCandidates(query)
        if (candidates.isEmpty()) {
            Log.i(TAG, "APP_LAUNCH_RESULT success=false reason=empty_candidates query=$query")
            return false
        }

        val currentPackage = rootInActiveWindow?.packageName?.toString().orEmpty().lowercase(Locale.US)
        val alreadyOpen = candidates.any { candidate ->
            val compact = candidate.replace(" ", "")
            compact.isNotBlank() && currentPackage.contains(compact)
        }
        if (alreadyOpen) {
            Log.i(TAG, "APP_LAUNCH_RESULT success=true method=already_open package=$currentPackage candidates=$candidates")
            return true
        }

        val visibleClickCandidate = clickVisibleAppIcon(candidates)
        if (visibleClickCandidate != null) {
            Log.i(TAG, "APP_LAUNCH_RESULT success=true method=visible_icon target=$visibleClickCandidate")
            return true
        }

        candidates.forEach { candidate ->
            val byPackageIntent = packageManager.getLaunchIntentForPackage(candidate)
            if (byPackageIntent != null) {
                byPackageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(byPackageIntent)
                Log.i(TAG, "APP_LAUNCH_RESULT success=true method=package query=$candidate")
                return true
            }
        }

        val launcherQueryIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launchables = packageManager.queryIntentActivities(launcherQueryIntent, 0)

        val launchableMatches = launchables
            .asSequence()
            .map { info ->
                val label = normalizeUiLabel(info.loadLabel(packageManager).toString())
                val pkg = info.activityInfo.packageName.lowercase(Locale.US)
                val score = when {
                    candidates.any { it == label } -> 100
                    candidates.any { candidate -> label.contains(candidate) } -> 80
                    candidates.any { candidate -> pkg.contains(candidate) } -> 60
                    candidates.any { candidate ->
                        val compactCandidate = candidate.replace(" ", "")
                        compactCandidate.isNotBlank() && pkg.contains(compactCandidate)
                    } -> 50
                    else -> 0
                }
                Triple(score, info, label)
            }
            .filter { it.first > 0 }
            .sortedByDescending { it.first }
            .toList()

        launchableMatches.forEach { (_, info, label) ->
            val pkg = info.activityInfo.packageName
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.i(TAG, "APP_LAUNCH_RESULT success=true method=label package=$pkg label=$label")
                return true
            }
        }

        // Fallback: scan installed apps by label/package and launch if possible.
        val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val installedMatches = installed.filter { appInfo ->
            val label = packageManager.getApplicationLabel(appInfo).toString().lowercase(Locale.US)
            val pkg = appInfo.packageName.lowercase(Locale.US)
            candidates.any { candidate ->
                label == candidate || label.contains(candidate) || pkg.contains(candidate)
            }
        }

        installedMatches.forEach { appInfo ->
            val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.i(TAG, "APP_LAUNCH_RESULT success=true method=installed package=${appInfo.packageName}")
                return true
            }
        }

        Log.i(TAG, "APP_LAUNCH_RESULT success=false reason=not_found candidates=$candidates query=$query")
        return false
    }

    private fun extractDirectWebSearchQuery(spokenCommand: String): String? {
        val normalized = spokenCommand.trim().lowercase(Locale.US)
        if (!(normalized.contains("google") || normalized.contains("search"))) {
            return null
        }

        val onMatcher = SEARCH_ON_GOOGLE_PATTERN.matcher(normalized)
        if (onMatcher.matches()) {
            val phrase = onMatcher.group(1)?.trim().orEmpty()
            if (phrase.isNotBlank()) return phrase
        }

        val searchMatcher = SEARCH_GENERIC_PATTERN.matcher(normalized)
        if (searchMatcher.matches()) {
            val phrase = searchMatcher.group(2)?.trim().orEmpty()
            if (phrase.isNotBlank()) return phrase
        }

        return null
    }

    private fun launchDirectWebSearch(query: String): Boolean {
        val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val searchHandled = runCatching {
            startActivity(webSearchIntent)
            true
        }.getOrElse { false }
        if (searchHandled) return true

        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://www.google.com/search?q=$encoded"

        val genericIntent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            startActivity(genericIntent)
            true
        }.getOrElse { false }
    }

    private fun clickVisibleAppIcon(candidates: List<String>): String? {
        val root = rootInActiveWindow ?: return null

        val matchedNode = findNodeByPredicate(root) { node ->
            if (!node.isClickable) return@findNodeByPredicate false

            val text = normalizeUiLabel(node.text?.toString().orEmpty())
            val desc = normalizeUiLabel(node.contentDescription?.toString().orEmpty())
            val isFolderNode = text.startsWith("folder") || desc.startsWith("folder")
            if (isFolderNode) return@findNodeByPredicate false

            candidates.any { candidate ->
                text == candidate || desc == candidate || text.contains(candidate) || desc.contains(candidate)
            }
        }

        val clicked = matchedNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!clicked) return null

        val clickedLabel = normalizeUiLabel(
            matchedNode?.contentDescription?.toString().orEmpty().ifBlank {
                matchedNode?.text?.toString().orEmpty()
            },
        )

        return clickedLabel.ifBlank { candidates.firstOrNull() }
    }

    private fun findEditableTarget(
        root: AccessibilityNodeInfo,
        targetId: String,
        preferredNode: AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        if (preferredNode?.isEditable == true) return preferredNode

        val preferredById = if (targetId.contains(':')) {
            root.findAccessibilityNodeInfosByViewId(targetId).firstOrNull { it.isEditable }
        } else {
            null
        }
        if (preferredById != null) return preferredById

        val inputKeywords = listOf("entry", "input", "edit", "message", "compose", "chat", "search")

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var firstEditable: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val viewId = node.viewIdResourceName.orEmpty().lowercase(Locale.US)
            val text = node.text?.toString().orEmpty().lowercase(Locale.US)
            val contentDesc = node.contentDescription?.toString().orEmpty().lowercase(Locale.US)
            val className = node.className?.toString().orEmpty().lowercase(Locale.US)

            if (node.isEditable || className.contains("edittext")) {
                if (firstEditable == null) {
                    firstEditable = node
                }

                val matchesInputIntent = inputKeywords.any {
                    viewId.contains(it) || text.contains(it) || contentDesc.contains(it)
                }
                if (matchesInputIntent) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        return firstEditable
    }

    private fun normalizeLaunchQuery(query: String): String {
        var normalized = query.trim().lowercase(Locale.US)
        normalized = normalized.replace(':', ' ')
        normalized = normalized.removePrefix("the ")
        normalized = normalized.removeSuffix(" app")
        normalized = normalized.removePrefix("open ")
        normalized = normalized.removePrefix("launch ")
        normalized = normalized.removePrefix("start ")
        normalized = normalized.removePrefix("go to ")
        normalized = normalized.removePrefix("use ")
        normalized = normalized.removePrefix("in ")

        val hasIndex = normalized.indexOf(" has ")
        if (hasIndex > 0) {
            normalized = normalized.substring(0, hasIndex).trim()
        }

        normalized = normalized.replace(Regex("\\s+"), " ").trim()

        return normalized
    }

    private fun normalizeUiLabel(value: String): String {
        var normalized = value.trim().lowercase(Locale.US)
        normalized = NOTIFICATION_SUFFIX_PATTERN.matcher(normalized).replaceAll("").trim()
        return normalizeLaunchQuery(normalized)
    }

    private fun buildLaunchCandidates(query: String): List<String> {
        val raw = normalizeLaunchQuery(query)
        if (raw.isBlank()) return emptyList()

        val candidates = linkedSetOf<String>()
        fun addCandidate(value: String) {
            val normalized = normalizeLaunchQuery(value)
            if (normalized.isBlank()) return
            candidates.add(normalized)
            APP_ALIASES[normalized]?.forEach { alias ->
                val aliasNormalized = normalizeLaunchQuery(alias)
                if (aliasNormalized.isNotBlank()) {
                    candidates.add(aliasNormalized)
                }
            }
        }

        addCandidate(raw)

        val onMatcher = ON_APP_PATTERN.matcher(raw)
        if (onMatcher.matches()) {
            addCandidate(onMatcher.group(1).orEmpty())
        }

        val usingMatcher = USING_APP_PATTERN.matcher(raw)
        if (usingMatcher.matches()) {
            addCandidate(usingMatcher.group(1).orEmpty())
        }

        val words = raw.split(' ').filter { it.isNotBlank() }
        if (words.isNotEmpty()) {
            addCandidate(words.last())
            if (words.size >= 2) {
                addCandidate(words.takeLast(2).joinToString(" "))
            }
        }

        if (raw.contains("google")) {
            addCandidate("google")
            addCandidate("google search")
            addCandidate("chrome")
        }

        return candidates.toList()
    }

    private fun buildUiSections(screenJson: String): List<UiSection> {
        val fullTree = runCatching { JSONArray(screenJson) }.getOrNull() ?: return listOf(
            UiSection(
                id = "general",
                heading = "General",
                summary = "Unparsed UI payload",
                nodes = JSONArray(),
            ),
        )
        val sectionNodes = linkedMapOf(
            "composer" to JSONArray(),
            "conversation" to JSONArray(),
            "top_bar" to JSONArray(),
            "navigation" to JSONArray(),
            "actions" to JSONArray(),
            "general" to JSONArray(),
        )

        for (i in 0 until fullTree.length()) {
            val node = fullTree.optJSONObject(i) ?: continue

            val viewId = node.optString("viewIdResourceName")
            val text = node.optString("text")
            val contentDescription = node.optString("contentDescription")
            val clickable = node.optBoolean("isClickable", false)
            val editable = node.optBoolean("isEditable", false)
            val scrollable = node.optBoolean("isScrollable", false)

            val normalizedId = viewId.lowercase(Locale.US)
            val normalizedText = text.lowercase(Locale.US)
            val importantInputHint =
                normalizedId.contains("entry") ||
                    normalizedId.contains("edit") ||
                    normalizedId.contains("input") ||
                    normalizedText == "message"

            val isRelevant = clickable || editable || scrollable || importantInputHint
            if (!isRelevant) continue

            val compactNode = JSONObject().apply {
                if (viewId.isNotBlank()) put("id", viewId)
                if (text.isNotBlank()) put("text", text)
                if (contentDescription.isNotBlank()) put("desc", contentDescription)
            }

            if (compactNode.length() == 0) continue

            val sectionId = classifySection(viewId, text, contentDescription, editable)
            sectionNodes[sectionId]?.put(compactNode)
        }

        val sections = mutableListOf<UiSection>()
        sectionNodes.forEach { (id, nodes) ->
            if (nodes.length() == 0) return@forEach

            val trimmedNodes = JSONArray()
            for (i in 0 until minOf(nodes.length(), PROMPT_UI_NODE_LIMIT_PER_SECTION)) {
                trimmedNodes.put(nodes.optJSONObject(i))
            }

            sections.add(
                UiSection(
                    id = id,
                    heading = sectionHeadingFor(id),
                    summary = summarizeSection(trimmedNodes),
                    nodes = trimmedNodes,
                ),
            )
        }

        if (sections.isEmpty()) {
            return listOf(
                UiSection(
                    id = "general",
                    heading = "General",
                    summary = "Fallback section",
                    nodes = JSONArray(),
                ),
            )
        }

        return sections
    }

    private fun classifySection(
        viewId: String,
        text: String,
        contentDescription: String,
        editable: Boolean,
    ): String {
        val id = viewId.lowercase(Locale.US)
        val txt = text.lowercase(Locale.US)
        val desc = contentDescription.lowercase(Locale.US)

        if (id.contains("attach") || id.contains("camera") || id.contains("pickfiletype") || desc.contains("attach")) return "actions"
        if (editable || id.contains("entry") || id.contains("input") || id.contains("edit")) return "composer"
        if (id.contains("toolbar") || id.contains("menu") || desc == "back") return "top_bar"
        if (id.contains("workspace") || id.contains("page_indicator") || id.contains("navigation")) return "navigation"
        if (id.contains("message") || id.contains("quoted") || id.contains("sticker") || id.contains("list") || id.contains("conversation") || id.contains("contact_row")) return "conversation"
        if (desc.contains("call") || desc.contains("camera") || desc.contains("attach") || txt.contains("message")) return "actions"
        return "general"
    }

    private fun sectionHeadingFor(sectionId: String): String {
        return when (sectionId) {
            "composer" -> "Message Composer"
            "conversation" -> "Conversation Feed"
            "top_bar" -> "Top Bar"
            "navigation" -> "Navigation Area"
            "actions" -> "Quick Actions"
            else -> "General"
        }
    }

    private fun summarizeSection(nodes: JSONArray): String {
        if (nodes.length() == 0) return "No elements"

        val labels = mutableListOf<String>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val label = node.optString("id").ifBlank {
                node.optString("text").ifBlank { node.optString("desc") }
            }
            if (label.isNotBlank()) {
                labels.add(label)
            }
            if (labels.size >= SECTION_SUMMARY_LABEL_LIMIT) {
                break
            }
        }

        return labels.joinToString(separator = " | ")
    }

    private fun pickSection(
        sections: List<UiSection>,
        selectorJson: JSONObject,
    ): UiSection {
        val raw = selectorJson.optString("section").ifBlank {
            selectorJson.optString("section_id")
        }.ifBlank {
            selectorJson.optString("target_id")
        }

        val normalized = raw.trim().lowercase(Locale.US)
        val exact = sections.firstOrNull { it.id == normalized }
        if (exact != null) return exact

        val fuzzy = sections.firstOrNull { section ->
            normalized.contains(section.id) || normalized.contains(section.heading.lowercase(Locale.US))
        }
        if (fuzzy != null) return fuzzy

        return sections.first()
    }

    private fun applyIntentOverrides(
        spokenCommand: String,
        history: List<String>,
        actionJson: JSONObject,
    ) {
        val normalizedSpoken = spokenCommand.trim().lowercase(Locale.US)

        if (isLeftNavigationIntent(normalizedSpoken)) {
            actionJson.put("action", "scroll")
            actionJson.put("target_id", "left")
            return
        }

        if (isRightNavigationIntent(normalizedSpoken)) {
            actionJson.put("action", "scroll")
            actionJson.put("target_id", "right")
            return
        }

        val tellTarget = extractTellTarget(spokenCommand)
        val tellIntent = tellTarget.isNotBlank() && normalizedSpoken.contains("whatsapp") &&
            (normalizedSpoken.contains("tell") || normalizedSpoken.contains("message"))
        if (tellIntent) {
            val launchedWhatsapp = history.any {
                it.contains("action=launch") && it.contains("target=WhatsApp") && it.contains("success=true")
            }
            val clickedSearch = history.any {
                it.contains("action=click") && it.contains("target=com.whatsapp:id/my_search_bar") && it.contains("success=true")
            }
            val typedSearch = history.any {
                it.contains("action=type") && it.contains("target=com.whatsapp:id/search_input") && it.contains("success=true")
            }

            if (!launchedWhatsapp) {
                actionJson.put("action", "launch")
                actionJson.put("target_id", "WhatsApp")
                actionJson.put("input_text", "")
                return
            }

            if (!clickedSearch) {
                actionJson.put("action", "click")
                actionJson.put("target_id", "com.whatsapp:id/my_search_bar")
                actionJson.put("input_text", "")
                return
            }

            if (!typedSearch) {
                actionJson.put("action", "type")
                actionJson.put("target_id", "com.whatsapp:id/search_input")
                actionJson.put("input_text", tellTarget)
                return
            }

            actionJson.put("action", "click")
            actionJson.put("target_id", tellTarget)
            actionJson.put("input_text", "")
            return
        }

        val wantsSend = isSendIntent(normalizedSpoken)
        val typedAlready = history.any { it.contains("action=type") && it.contains("success=true") }
        val explicitTypedText = extractTypeIntentText(spokenCommand)
        val generatedText = when {
            explicitTypedText.isNotBlank() -> explicitTypedText
            normalizedSpoken.contains("poem") -> generatePoemText()
            else -> ""
        }

        if (generatedText.isNotBlank() && !typedAlready) {
            actionJson.put("action", "type")
            actionJson.put("target_id", "com.whatsapp:id/entry")
            actionJson.put("input_text", generatedText)
            return
        }

        if (wantsSend && typedAlready) {
            actionJson.put("action", "click")
            actionJson.put("target_id", "send")
            actionJson.put("input_text", "")
            return
        }

        if (explicitTypedText.isNotBlank()) {
            actionJson.put("action", "type")
            actionJson.put("input_text", explicitTypedText)

            val targetId = actionJson.optString("target_id")
            val targetLower = targetId.lowercase(Locale.US)
            val likelyBadTarget = targetLower.contains("quoted_message_frame") || targetId.isBlank()
            if (likelyBadTarget) {
                actionJson.put("target_id", "com.whatsapp:id/entry")
            }
            return
        }

        val action = actionJson.optString("action").lowercase(Locale.US)
        val targetId = actionJson.optString("target_id")
        val lowerTarget = targetId.lowercase(Locale.US)
        val attachDetour = lowerTarget.contains("attach") ||
            lowerTarget.contains("camera") ||
            lowerTarget.contains("gallery") ||
            lowerTarget.contains("pickfiletype")

        if (action == "click" && attachDetour) {
            actionJson.put("action", "click")
            actionJson.put("target_id", "com.whatsapp:id/entry")
        }
    }

    private fun extractTypeIntentText(spokenCommand: String): String {
        val matcher = TYPE_INTENT_PATTERN.matcher(spokenCommand.trim())
        if (!matcher.matches()) return ""
        return matcher.group(2)?.trim().orEmpty()
    }

    private fun extractTellTarget(spokenCommand: String): String {
        val matcher = TELL_INTENT_PATTERN.matcher(spokenCommand.trim())
        if (!matcher.matches()) return ""

        var target = matcher.group(1)?.trim().orEmpty().lowercase(Locale.US)
        target = target.replace(" and ", " ")
        target = target.replace(Regex("\\b(bhai|bro|bhaiya|bhayya|please|plz)$"), "").trim()
        if (target.isBlank()) return ""

        return target.split(' ').firstOrNull().orEmpty()
    }

    private fun isLeftNavigationIntent(spokenLower: String): Boolean {
        return spokenLower.contains("left page") ||
            spokenLower.contains("move left") ||
            spokenLower.contains("go left") ||
            spokenLower.contains("previous page")
    }

    private fun isRightNavigationIntent(spokenLower: String): Boolean {
        return spokenLower.contains("right page") ||
            spokenLower.contains("move right") ||
            spokenLower.contains("go right") ||
            spokenLower.contains("next page")
    }

    private fun isSendIntent(spokenLower: String): Boolean {
        return spokenLower.contains("send") || spokenLower.contains("bhej")
    }

    private fun generatePoemText(): String {
        return "A small moon sits on your window tonight,\nI pack my words in silver light,\nIf distance hums, let laughter stay,\nI am one soft message away."
    }

    private fun shouldContinuePlanning(
        spokenCommand: String,
        success: Boolean,
        executedAction: String,
        targetId: String,
        previousHistoryEntry: String?,
    ): Boolean {
        if (!success) return false
        if (executedAction == "wait") return false

        val oneShotIntent = extractTypeIntentText(spokenCommand).isNotBlank() ||
            isLeftNavigationIntent(spokenCommand.lowercase(Locale.US)) ||
            isRightNavigationIntent(spokenCommand.lowercase(Locale.US))
        if (oneShotIntent) return false

        val sendFinished = isSendIntent(spokenCommand.lowercase(Locale.US)) &&
            executedAction == "click" &&
            targetId.lowercase(Locale.US).contains("send")
        if (sendFinished) return false

        val repeatedStep = previousHistoryEntry?.contains("action=$executedAction target=$targetId success=true") == true
        if (repeatedStep) return false

        return true
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isScrollable) return current
            current = current.parent
        }
        return null
    }

    private fun findFirstScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) return node

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        return null
    }

    private fun performDirectionalSwipe(useBackward: Boolean, horizontal: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val path = Path()
        if (horizontal) {
            val startX = if (useBackward) width * 0.2f else width * 0.8f
            val endX = if (useBackward) width * 0.8f else width * 0.2f
            val y = height * 0.5f
            path.moveTo(startX, y)
            path.lineTo(endX, y)
        } else {
            val x = width * 0.5f
            val startY = if (useBackward) height * 0.3f else height * 0.7f
            val endY = if (useBackward) height * 0.7f else height * 0.3f
            path.moveTo(x, startY)
            path.lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 220))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    private fun ensureStatusChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            STATUS_CHANNEL_ID,
            "Voice Agent Status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Status updates for voice command execution"
        }
        manager.createNotificationChannel(channel)
    }

    private fun postStatusNotification(message: String) {
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!notificationsEnabled) {
            Log.w(TAG, "Status notification skipped: app notifications disabled")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                Log.w(TAG, "Status notification skipped: POST_NOTIFICATIONS permission not granted")
                return
            }
        }

        val builder = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Agent")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

        runCatching {
            NotificationManagerCompat.from(this).notify(STATUS_NOTIFICATION_ID, builder.build())
            Log.i(TAG, "Status notification posted")
        }.onFailure { err ->
            Log.w(TAG, "Unable to post status notification", err)
        }
    }

    private fun describeNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return "null"
        val viewId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString().orEmpty()
        val contentDesc = node.contentDescription?.toString().orEmpty()
        return "viewId=$viewId,text=$text,contentDescription=$contentDesc,clickable=${node.isClickable}"
    }

    private fun logLarge(label: String, content: String) {
        if (content.isEmpty()) {
            Log.i(TAG, "$label=<empty>")
            return
        }

        var start = 0
        var chunkIndex = 0
        while (start < content.length) {
            val end = minOf(start + LOG_CHUNK_SIZE, content.length)
            Log.i(TAG, "$label[$chunkIndex]=${content.substring(start, end)}")
            start = end
            chunkIndex += 1
        }
    }

    private fun isSensitiveTarget(targetId: String): Boolean {
        if (targetId.isBlank()) return false

        val normalized = targetId.trim()
        if (normalized.startsWith("gsk_", ignoreCase = true)) return true

        val looksLikeLongSecret = normalized.length >= 24 &&
            normalized.all { it.isLetterOrDigit() || it == '_' || it == '-' }

        return looksLikeLongSecret
    }

    private fun findNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        if (target.isBlank()) return null

        val targetLower = target.lowercase(Locale.US)

        if (targetLower == "send") {
            val sendNode = findNodeByPredicate(root) { node ->
                val viewId = node.viewIdResourceName.orEmpty().lowercase(Locale.US)
                val text = node.text?.toString().orEmpty().lowercase(Locale.US)
                val contentDesc = node.contentDescription?.toString().orEmpty().lowercase(Locale.US)
                viewId.contains("send") || text == "send" || contentDesc.contains("send")
            }
            if (sendNode != null) return sendNode
        }

        val nodesByViewId = if (target.contains(":")) {
            root.findAccessibilityNodeInfosByViewId(target)
        } else {
            emptyList()
        }
        if (nodesByViewId.isNotEmpty()) {
            return nodesByViewId.first()
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val viewId = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString().orEmpty()
            val contentDesc = node.contentDescription?.toString().orEmpty()
            val viewIdLower = viewId.lowercase(Locale.US)
            val textLower = text.lowercase(Locale.US)
            val contentDescLower = contentDesc.lowercase(Locale.US)

            val matches = viewId.equals(target, ignoreCase = true) ||
                viewId.endsWith("/$target") ||
                text.equals(target, ignoreCase = true) ||
                contentDesc.equals(target, ignoreCase = true) ||
                (targetLower.length <= 16 &&
                    (viewIdLower.contains(targetLower) || textLower.contains(targetLower) || contentDescLower.contains(targetLower)))

            if (matches) {
                return node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        return null
    }

    private fun findNodeByPredicate(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        return null
    }

    companion object {
        private const val PREFS_NAME = "voice_os_prefs"
        private const val PREF_GROQ_API_KEY = "groq_api_key"
        private const val PREF_AGENT_ENABLED = "agent_enabled"
        private const val MODEL_NAME = "llama-3.1-8b-instant"
        private const val GROQ_CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val TAG = "VoiceAgentService"
        private const val LOG_CHUNK_SIZE = 3500
        private const val PROMPT_UI_NODE_LIMIT_PER_SECTION = 45
        private const val SECTION_SUMMARY_LABEL_LIMIT = 6
        private const val MAX_PLAN_STEPS = 5
        private const val PLAN_STEP_DELAY_MS = 450L
        private const val STATUS_CHANNEL_ID = "voice_agent_status"
        private const val STATUS_NOTIFICATION_ID = 1107
        private val TYPE_INTENT_PATTERN = Pattern.compile("^(type|write|enter|input|send)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        private val TELL_INTENT_PATTERN = Pattern.compile(".*(?:tell|message)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        private val ON_APP_PATTERN = Pattern.compile(".*\\bon\\s+([a-z0-9 ._+-]{2,})$")
        private val USING_APP_PATTERN = Pattern.compile(".*\\busing\\s+([a-z0-9 ._+-]{2,})$")
        private val NOTIFICATION_SUFFIX_PATTERN = Pattern.compile("\\s+has\\s+\\d+\\s+notifications?$")
        private val SEARCH_ON_GOOGLE_PATTERN = Pattern.compile("^(?:find|search)\\s+(.+?)\\s+on\\s+google$")
        private val SEARCH_GENERIC_PATTERN = Pattern.compile("^(find|search)\\s+(.+)$")
        private val APP_ALIASES = mapOf(
            "google" to listOf("chrome", "google chrome", "com.android.chrome", "com.google.android.googlequicksearchbox"),
            "chrome" to listOf("google", "google chrome", "com.android.chrome"),
            "youtube" to listOf("yt", "com.google.android.youtube"),
            "play store" to listOf("google play", "com.android.vending"),
        )

        @Volatile
        private var activeInstance: VoiceAgentService? = null

        fun requestManualListening(): String {
            val service = activeInstance ?: return "Service not active. Enable accessibility service."
            return service.triggerVoiceCommandListening()
        }

        fun isServiceActive(): Boolean {
            return activeInstance != null
        }
    }

    private data class UiSection(
        val id: String,
        val heading: String,
        val summary: String,
        val nodes: JSONArray,
    )
}
