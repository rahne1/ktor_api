package org.example.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class Warnings(
    val warnings: Map<String, Double>
) {
    operator fun get(key: String): Double? = warnings[key]
    override fun toString(): String {
        return warnings.entries.joinToString(", ") { (key, value) ->
            "$key=$value"
        }
    }
}

data class ModerationResponse(
    val isNegative: Boolean,
    val toxicityScore: Double?,
)

class ContentParser {
    suspend fun parseContent(content: ByteArray, contentType: String) = withContext(Dispatchers.IO) {
        val decodedContent = Base64.getEncoder().encodeToString(content)
        val builder = ProcessBuilder(
            "bash",
            "-c",
            "cd ./processor/ && source .venv/bin/activate && python3 content_processor.py $contentType $decodedContent"
        )
        var process: Process? = null

        try {
            process = builder.start()
            val result = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("Command execution failed with exit code: $exitCode")
            }
            val resultDict = parseResult(result)
            println(resultDict)
        } catch (e: Exception) {
            throw RuntimeException("Error executing command", e)
        } finally {
            process?.destroy()
        }
    }

    private fun parseResult(result: String): Warnings {
        val warningsMap = mutableMapOf<String, Double>()
        val pairs = result.split(", ")

        for (pair in pairs) {
            val (key, value) = pair.split(": ")
            val cleanKey = key.trim('\'')
            val doubleValue = value.toDoubleOrNull()
            if (doubleValue != null) {
                warningsMap[cleanKey] = doubleValue
            }
        }

        return Warnings(warningsMap)
    }
}