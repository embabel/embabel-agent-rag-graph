/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.rag.graph.test

/**
 * The rows a shell query returned.
 * Provides access to result rows as a list of maps.
 */
class QueryResult(
    private val rows: List<Map<String, Any>>
) : Iterable<Map<String, Any>> by rows {

    /**
     * Get all result rows.
     */
    fun items(): List<Map<String, Any>> = rows

    /**
     * Get a single row from the result set, or null if empty.
     */
    fun singleOrNull(): Map<String, Any>? = rows.singleOrNull()

    inline fun <reified T : Number> numberOrZero(key: String): T {
        val v = items().firstOrNull()?.get(key) as? Number ?: return zeroOf()
        return when (T::class) {
            Int::class -> v.toInt() as T
            Long::class -> v.toLong() as T
            Float::class -> v.toFloat() as T
            Double::class -> v.toDouble() as T
            Short::class -> v.toShort() as T
            Byte::class -> v.toByte() as T
            else -> error("Unsupported number type: ${T::class}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Number> zeroOf(): T =
        when (T::class) {
            Int::class -> 0 as T
            Long::class -> 0L as T
            Float::class -> 0f as T
            Double::class -> 0.0 as T
            Short::class -> 0.toShort() as T
            Byte::class -> 0.toByte() as T
            else -> error("Unsupported number type: ${T::class}")
        }
}
