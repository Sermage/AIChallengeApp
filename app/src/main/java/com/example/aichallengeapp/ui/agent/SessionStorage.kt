package com.example.aichallengeapp.ui.agent

import android.content.Context
import com.example.aichallengeapp.data.AgentStateSnapshot
import com.example.aichallengeapp.data.AgentStep
import com.example.aichallengeapp.data.ContextStrategy
import com.example.aichallengeapp.data.MessageObj
import com.example.aichallengeapp.data.StepType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatConfig(
    val maxTokens: Int?,
    val contextHistorySize: Int,
    val maxContextTokens: Int,
    val strategy: ContextStrategy,
    val summarizeEvery: Int,
)

data class ChatSession(
    val id: Long,
    val agentName: String,
    val timestamp: Long,
    val messages: List<ChatMessage>,
    val config: ChatConfig? = null,
    val branches: List<DialogBranch> = emptyList(),
    val currentBranchId: String? = null,
) {
    val displayDate: String
        get() = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))

    val preview: String
        get() = messages.firstOrNull { it.isUser }?.text?.take(80) ?: ""

    val messageCount: Int
        get() = messages.size
}

object SessionStorage {

    private const val FILE_NAME = "agent_sessions.json"
    private const val MAX_SESSIONS = 30

    fun save(context: Context, session: ChatSession) {
        val sessions = loadAll(context).toMutableList()
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx >= 0) sessions[idx] = session else sessions.add(0, session)
        getFile(context).writeText(serializeAll(sessions.take(MAX_SESSIONS)))
    }

    fun loadAll(context: Context): List<ChatSession> {
        val file = getFile(context)
        if (!file.exists()) return emptyList()
        return runCatching { deserializeAll(file.readText()) }.getOrDefault(emptyList())
    }

    private fun getFile(context: Context) = File(context.filesDir, FILE_NAME)

    // ── Сериализация ──────────────────────────────

    private fun serializeAll(sessions: List<ChatSession>): String =
        JSONArray().also { arr -> sessions.forEach { arr.put(serializeSession(it)) } }.toString()

    private fun serializeSession(s: ChatSession) = JSONObject().apply {
        put("id", s.id)
        put("agentName", s.agentName)
        put("timestamp", s.timestamp)
        put(
            "messages",
            JSONArray().also { arr -> s.messages.forEach { arr.put(serializeMessage(it)) } })
        s.config?.let { put("config", serializeConfig(it)) }
        if (s.branches.isNotEmpty()) {
            put(
                "branches",
                JSONArray().also { arr -> s.branches.forEach { arr.put(serializeBranch(it)) } })
            s.currentBranchId?.let { put("currentBranchId", it) }
        }
    }

    private fun serializeConfig(c: ChatConfig) = JSONObject().apply {
        c.maxTokens?.let { put("maxTokens", it) }
        put("contextHistorySize", c.contextHistorySize)
        put("maxContextTokens", c.maxContextTokens)
        put("strategy", c.strategy.name)
        put("summarizeEvery", c.summarizeEvery)
    }

    private fun serializeBranch(b: DialogBranch) = JSONObject().apply {
        put("id", b.id)
        put("name", b.name)
        b.parentId?.let { put("parentId", it) }
        put("createdAt", b.createdAt)
        put(
            "messages",
            JSONArray().also { arr -> b.messages.forEach { arr.put(serializeMessage(it)) } })
        put("agentState", serializeAgentState(b.agentState))
    }

    private fun serializeAgentState(s: AgentStateSnapshot) = JSONObject().apply {
        put("history", JSONArray().also { arr ->
            s.history.forEach { msg ->
                arr.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                    msg.attachments?.let {
                        put(
                            "attachments",
                            JSONArray().also { a -> it.forEach(a::put) })
                    }
                })
            }
        })
        s.summary?.let { put("summary", it) }
        s.facts?.let { put("facts", it) }
        put("foldedCount", s.foldedCount)
    }

    private fun serializeMessage(m: ChatMessage) = JSONObject().apply {
        put("id", m.id)
        put("isUser", m.isUser)
        put("text", m.text)
        put("totalTokens", m.totalTokens)
        put("elapsedMs", m.elapsedMs)
        put("requestTokens", m.requestTokens)
        put("completionTokens", m.completionTokens)
        put("historyTokens", m.historyTokens)
        m.noContextAnswer?.let { put("noContextAnswer", it) }
        put("noContextTokens", m.noContextTokens)
        m.factsSnapshot?.let { put("factsSnapshot", it) }
        put("factsTokens", m.factsTokens)
        m.branchId?.let { put("branchId", it) }
        if (m.attachmentNames.isNotEmpty()) {
            put("attachmentNames", JSONArray().also { arr -> m.attachmentNames.forEach(arr::put) })
        }
        put("steps", JSONArray().also { arr ->
            m.steps.forEach { step ->
                arr.put(JSONObject().apply {
                    put("type", step.type.name)
                    put("content", step.content)
                    put("tokens", step.tokens)
                })
            }
        })
    }

    // ── Десериализация ────────────────────────────

    private fun deserializeAll(json: String): List<ChatSession> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { deserializeSession(arr.getJSONObject(it)) }
    }

    private fun deserializeSession(obj: JSONObject): ChatSession {
        val msgs = obj.getJSONArray("messages")
        val branchesArr = obj.optJSONArray("branches")
        return ChatSession(
            id = obj.getLong("id"),
            agentName = obj.getString("agentName"),
            timestamp = obj.getLong("timestamp"),
            messages = (0 until msgs.length()).map { deserializeMessage(msgs.getJSONObject(it)) },
            config = obj.optJSONObject("config")?.let { deserializeConfig(it) },
            branches = branchesArr?.let { arr ->
                (0 until arr.length()).map { deserializeBranch(arr.getJSONObject(it)) }
            } ?: emptyList(),
            currentBranchId = obj.optString("currentBranchId").takeIf { it.isNotEmpty() },
        )
    }

    private fun deserializeConfig(obj: JSONObject): ChatConfig {
        val strategyName = obj.optString("strategy", ContextStrategy.SLIDING_WINDOW.name)
        return ChatConfig(
            maxTokens = if (obj.has("maxTokens")) obj.getInt("maxTokens") else null,
            contextHistorySize = obj.optInt("contextHistorySize", 4),
            maxContextTokens = obj.optInt("maxContextTokens", 32768),
            strategy = runCatching { ContextStrategy.valueOf(strategyName) }
                .getOrDefault(ContextStrategy.SLIDING_WINDOW),
            summarizeEvery = obj.optInt("summarizeEvery", 10),
        )
    }

    private fun deserializeBranch(obj: JSONObject): DialogBranch {
        val msgs = obj.getJSONArray("messages")
        return DialogBranch(
            id = obj.getString("id"),
            name = obj.getString("name"),
            parentId = obj.optString("parentId").takeIf { it.isNotEmpty() },
            createdAt = obj.getLong("createdAt"),
            messages = (0 until msgs.length()).map { deserializeMessage(msgs.getJSONObject(it)) },
            agentState = deserializeAgentState(obj.getJSONObject("agentState")),
        )
    }

    private fun deserializeAgentState(obj: JSONObject): AgentStateSnapshot {
        val histArr = obj.getJSONArray("history")
        return AgentStateSnapshot(
            history = (0 until histArr.length()).map {
                val m = histArr.getJSONObject(it)
                MessageObj(
                    role = m.getString("role"),
                    content = m.getString("content"),
                    attachments = m.optJSONArray("attachments")?.let { arr ->
                        (0 until arr.length()).map { i -> arr.getString(i) }
                    },
                )
            },
            summary = obj.optString("summary").takeIf { it.isNotEmpty() },
            facts = obj.optString("facts").takeIf { it.isNotEmpty() },
            foldedCount = obj.optInt("foldedCount", 0),
        )
    }

    private fun deserializeMessage(obj: JSONObject): ChatMessage {
        val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
        return ChatMessage(
            id = obj.getLong("id"),
            isUser = obj.getBoolean("isUser"),
            text = obj.getString("text"),
            totalTokens = obj.optInt("totalTokens", 0),
            elapsedMs = obj.optLong("elapsedMs", 0),
            requestTokens = obj.optInt("requestTokens", 0),
            completionTokens = obj.optInt("completionTokens", 0),
            historyTokens = obj.optInt("historyTokens", 0),
            noContextAnswer = obj.optString("noContextAnswer").takeIf { it.isNotEmpty() },
            noContextTokens = obj.optInt("noContextTokens", 0),
            factsSnapshot = obj.optString("factsSnapshot").takeIf { it.isNotEmpty() },
            factsTokens = obj.optInt("factsTokens", 0),
            branchId = obj.optString("branchId").takeIf { it.isNotEmpty() },
            attachmentNames = obj.optJSONArray("attachmentNames")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            steps = (0 until stepsArr.length()).map {
                val s = stepsArr.getJSONObject(it)
                AgentStep(
                    type = runCatching { StepType.valueOf(s.getString("type")) }.getOrDefault(
                        StepType.ANSWER
                    ),
                    content = s.getString("content"),
                    tokens = s.optInt("tokens", 0),
                )
            },
        )
    }
}
