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
package com.embabel.agent.rag.graph

import com.embabel.agent.filter.ObjectFilter
import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.filter.EntityFilter

/**
 * Result of converting a [PropertyFilter] or [EntityFilter] to Cypher WHERE clause components.
 *
 * @property whereClause The Cypher WHERE clause fragment (without the WHERE keyword).
 *                       Empty string if no filter was provided.
 * @property parameters Map of parameter names to values for safe parameterized queries.
 */
data class CypherFilterResult(
    val whereClause: String,
    val parameters: Map<String, Any>,
) {
    companion object {
        val EMPTY = CypherFilterResult("", emptyMap())
    }

    /**
     * Returns true if this result represents no filter (empty WHERE clause).
     */
    fun isEmpty(): Boolean = whereClause.isEmpty()

    /**
     * Prepends " AND " and the where clause to an existing WHERE clause.
     * Returns the original if this filter is empty.
     */
    fun appendTo(existingWhereClause: String): String {
        if (isEmpty()) return existingWhereClause
        return if (existingWhereClause.isBlank()) {
            whereClause
        } else {
            "$existingWhereClause AND $whereClause"
        }
    }
}

/**
 * Converts [PropertyFilter] and [EntityFilter] expressions to Cypher WHERE clause components.
 *
 * Generates parameterized queries to prevent Cypher injection.
 * Parameters are prefixed with "_filter_" to avoid conflicts with other query parameters.
 *
 * ## Property Filter Usage
 *
 * ```kotlin
 * val converter = CypherFilterConverter(nodeAlias = "e")
 * val filter = PropertyFilter.eq("owner", "alice") and PropertyFilter.gte("score", 0.8)
 * val result = converter.convert(filter)
 *
 * // result.whereClause = "e.owner = $_filter_0 AND e.score >= $_filter_1"
 * // result.parameters = mapOf("_filter_0" to "alice", "_filter_1" to 0.8)
 * ```
 *
 * ## Entity Filter Usage
 *
 * Entity filters extend property filters with label-based filtering:
 *
 * ```kotlin
 * val converter = CypherFilterConverter(nodeAlias = "e")
 * val filter = EntityFilter.hasAnyLabel("Person", "Organization") and PropertyFilter.eq("status", "active")
 * val result = converter.convert(filter)
 *
 * // result.whereClause = "(ANY(label IN labels(e) WHERE label IN $_filter_0)) AND (e.status = $_filter_1)"
 * // result.parameters = mapOf("_filter_0" to listOf("Person", "Organization"), "_filter_1" to "active")
 * ```
 *
 * ## Kotlin DSL example
 *
 * ```kotlin
 * import com.embabel.agent.rag.filter.PropertyFilter.Companion.eq
 * import com.embabel.agent.rag.filter.PropertyFilter.Companion.gte
 * import com.embabel.agent.rag.filter.EntityFilter.Companion.hasAnyLabel
 *
 * val filter = (eq("owner", userId) and gte("score", 0.7)) or eq("role", "admin")
 * val entityFilter = hasAnyLabel("Person") and eq("status", "active")
 * val result = converter.convert(filter)
 * ```
 *
 * @property nodeAlias The Cypher node alias to use in property access (e.g., "e" for "e.property").
 * @property paramPrefix Prefix for generated parameter names (default: "_filter_").
 */
class CypherFilterConverter(
    private val nodeAlias: String = "e",
    private val paramPrefix: String = "_filter_",
) {

    /**
     * Converts a [PropertyFilter] to Cypher WHERE clause components.
     *
     * @param filter The filter to convert, or null for no filtering.
     * @return [CypherFilterResult] with the WHERE clause and parameters.
     */
    fun convert(filter: PropertyFilter?): CypherFilterResult {
        if (filter == null) return CypherFilterResult.EMPTY

        val parameters = mutableMapOf<String, Any>()
        val paramCounter = ParamCounter()
        val whereClause = convertFilter(filter, parameters, paramCounter)
        return CypherFilterResult(whereClause, parameters)
    }

    private class ParamCounter {
        private var count = 0
        fun next(): Int = count++
    }

    private fun convertFilter(
        filter: PropertyFilter,
        params: MutableMap<String, Any>,
        counter: ParamCounter,
    ): String = when (filter) {
        is PropertyFilter.Eq -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value
            "$nodeAlias.${filter.key} = \$$paramName"
        }

        is PropertyFilter.Ne -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value
            "$nodeAlias.${filter.key} <> \$$paramName"
        }

        is PropertyFilter.Gt -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value
            "$nodeAlias.${filter.key} > \$$paramName"
        }

        is PropertyFilter.Gte -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value
            "$nodeAlias.${filter.key} >= \$$paramName"
        }

        is PropertyFilter.Lt -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value
            "$nodeAlias.${filter.key} < \$$paramName"
        }

        is PropertyFilter.Lte -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value
            "$nodeAlias.${filter.key} <= \$$paramName"
        }

        is PropertyFilter.In -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.values
            "$nodeAlias.${filter.key} IN \$$paramName"
        }

        is PropertyFilter.Nin -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.values
            "NOT $nodeAlias.${filter.key} IN \$$paramName"
        }

        is PropertyFilter.HasElement -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value
            "\$$paramName IN $nodeAlias.${filter.key}"
        }

        is PropertyFilter.Contains -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value
            "$nodeAlias.${filter.key} CONTAINS \$$paramName"
        }

        is PropertyFilter.ContainsIgnoreCase -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value.lowercase()
            "toLower($nodeAlias.${filter.key}) CONTAINS \$$paramName"
        }

        is PropertyFilter.EqIgnoreCase -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value.lowercase()
            "toLower($nodeAlias.${filter.key}) = \$$paramName"
        }

        is PropertyFilter.StartsWith -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value
            "$nodeAlias.${filter.key} STARTS WITH \$$paramName"
        }

        is PropertyFilter.EndsWith -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.value
            "$nodeAlias.${filter.key} ENDS WITH \$$paramName"
        }

        is PropertyFilter.Like -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.pattern
            "$nodeAlias.${filter.key} =~ \$$paramName"
        }

        is PropertyFilter.And -> {
            val clauses = filter.filters.map { convertFilter(it, params, counter) }
            if (clauses.size == 1) {
                clauses.first()
            } else {
                clauses.joinToString(" AND ") { "($it)" }
            }
        }

        is PropertyFilter.Or -> {
            val clauses = filter.filters.map { convertFilter(it, params, counter) }
            if (clauses.size == 1) {
                clauses.first()
            } else {
                clauses.joinToString(" OR ") { "($it)" }
            }
        }

        is PropertyFilter.Not -> {
            val inner = convertFilter(filter.filter, params, counter)
            "NOT ($inner)"
        }

        is EntityFilter.HasAnyLabel -> {
            val paramName = "$paramPrefix${counter.next()}"
            params[paramName] = filter.labels.toList()
            "ANY(label IN labels($nodeAlias) WHERE label IN \$$paramName)"
        }

        is ObjectFilter -> {
            // ObjectFilter is a marker interface, not directly convertible to Cypher.
            // It should be handled at a higher level where the actual filter type is known.
            throw IllegalArgumentException("Cannot convert ObjectFilter directly to Cypher. Handle specific filter types instead.")
        }

        // Any other PropertyFilter subtype (e.g. embabel 1.0's element-membership filters) has no
        // node-property WHERE form — the Cypher converter can't express it.
        else -> throw IllegalArgumentException(
            "Unsupported PropertyFilter for Cypher conversion: ${filter::class.simpleName}",
        )
    }
}
