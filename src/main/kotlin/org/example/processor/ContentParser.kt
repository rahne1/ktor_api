package org.example.processor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContentParser {


    suspend fun parseContent(content: ByteArray): Any = withContext(Dispatchers.IO) {


    }
}
