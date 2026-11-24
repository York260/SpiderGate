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
            .put("max_tokens", 1000)
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

            val json = JSONObject(text)
            val content = json.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            val out = sb.toString().trim()
            // 空白回覆當成失敗：畫面會顯示原因，這一輪也不算數，可以再送一次
            if (out.isEmpty()) throw IOException("守門員回了空白（stop_reason=${json.optString("stop_reason")}）")
            return out
        } finally {
            conn.disconnect()
        }
    }
}
