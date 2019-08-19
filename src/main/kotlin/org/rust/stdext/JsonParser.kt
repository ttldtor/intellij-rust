/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.stdext

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.JsonReader
import java.io.StringReader

object JsonUtil {
    private val PARSER: JsonParser = JsonParser()

    fun tryParseJsonObject(text: String, isLenient: Boolean = true): JsonObject? {
        val reader = JsonReader(StringReader(text))
            .apply { this.isLenient = isLenient }
        return try {
            reader.use { PARSER.parse(it) as? JsonObject }
        } catch (e: JsonSyntaxException) {
            null
        }
    }
}
