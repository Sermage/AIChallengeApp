package com.example.aichallengeapp.ui

import android.content.Context
import com.example.aichallengeapp.data.AgentStep
import com.example.aichallengeapp.data.StepType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatSession(
    val id: Long,
    val agentName: String,
    val timestamp: Long,
    val messages: List<ChatMessage>,
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
    }

    private fun serializeMessage(m: ChatMessage) = JSONObject().apply {
        put("id", m.id)
        put("isUser", m.isUser)
        put("text", m.text)
        put("totalTokens", m.totalTokens)
        put("elapsedMs", m.elapsedMs)
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
        return ChatSession(
            id = obj.getLong("id"),
            agentName = obj.getString("agentName"),
            timestamp = obj.getLong("timestamp"),
            messages = (0 until msgs.length()).map { deserializeMessage(msgs.getJSONObject(it)) },
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
