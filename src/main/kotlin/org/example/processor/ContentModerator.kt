package org.example.processor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.ModerationResponse
import java.net.HttpURLConnection
import java.net.URL

class ContentModerator {

    suspend fun moderateContent(content: ByteArray): Any = withContext(Dispatchers.IO) {
        val url = URL("hi")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        val jsonPayload = """
            {
                "content: "$content"
            }
        """.trimIndent()

        connection.outputStream.use { os ->
            os.write(jsonPayload.toByteArray())
            os.flush()
        }

        return@withContext if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.bufferedReader().use { reader ->
                val responseJson = reader.readText()
                parseModerationResponse(responseJson)
            }
        } else {
            ModerationResponse(isNegative = true, toxicityScore = null, sentiment = "error")
        }
    }

    private fun parseModerationResponse(responseJson: String) {
        println(responseJson)
    }
}
