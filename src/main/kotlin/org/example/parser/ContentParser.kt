package org.example.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException


@Serializable
data class Warnings(val warnings: Map<String, Double>) {
    operator fun get(key: String): Double? = warnings[key]
    fun values(): List<Double> = warnings.values.toList()
    override fun toString(): String {
        return warnings.entries.joinToString(",") { (key, value) -> "$key=${"%.10f".format(value)}" } + "'}"
    }
}

data class Response(
    val isNegative: Boolean, val toxicityScore: Double
)

class ContentParser {
    companion object {
        private const val TOXICITY_THRESHOLD = 0.7
        private const val COMMAND_TIMEOUT_SECONDS = 30L
    }

    suspend fun parseContent(content: ByteArray, contentType: String): Response = withContext(Dispatchers.IO) {
        require(content.isNotEmpty()) { "Content cannot be empty" }
        require(contentType.isNotBlank()) { "Content type cannot be blank" }

        val decodedContent = Base64.getEncoder().encodeToString(content)
        val command = buildCommand(contentType, decodedContent)

        val process = ProcessBuilder("bash", "-c", command).start()
        try {
            val result = process.inputStream.bufferedReader().use { it.readText() }
            val errorOutput = process.errorStream.bufferedReader().use { it.readText() }

            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw TimeoutException("Command execution timed out after $COMMAND_TIMEOUT_SECONDS seconds")
            }

            if (process.exitValue() != 0) {
                throw RuntimeException("Command execution failed with exit code: ${process.exitValue()}. Error: $errorOutput")
            }
            val warnings = parseResult(result)
            val maxToxicityScore = warnings.values().maxOrNull() ?: 0.0

             Response(
                isNegative = maxToxicityScore > TOXICITY_THRESHOLD, toxicityScore = maxToxicityScore
            )
        } finally {
            process.destroy()
        }
    }

    private fun buildCommand(contentType: String, decodedContent: String): String =
        "cd ./processor/ && source .venv/bin/activate && python3 content_processor.py ${contentType.escapeShellArg()} ${decodedContent.escapeShellArg()}"

    private fun String.escapeShellArg(): String = "'${replace("'", "'\\''")}'"


    private fun parseResult(result: String): Warnings {
        val warningsMap = result.split(",").mapNotNull { pair ->
            val parts = pair.split(":")
            if (parts.size == 2) {
                val key = parts[0].trim().trim('\'', '"')
                val value = parts[1].trim().trim('\'', '"')
                value.toDoubleOrNull()?.let { key to it }
            } else {
                null
            }
        }.toMap()

        return Warnings(warningsMap)
    }


}
