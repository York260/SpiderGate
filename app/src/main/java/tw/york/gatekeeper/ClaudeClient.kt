package tw.york.gatekeeper

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 呼叫 Claude API。請在背景執行緒呼叫。 */
object ClaudeClient {

    fun chat(apiKey: String, model: String, system: String, messages: List<Pair<String, String>>): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 400)
            .put("system", system)
            .put("messages", JSONArray().apply {
                messages.forEach { (role, text) ->
                    put(JSONObject().put("role", role).put("content", text))
                }
            })

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(200)}")

            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }
}
