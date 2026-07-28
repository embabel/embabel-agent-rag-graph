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

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory

/**
 * The purpose-logged, parameterized inline-Cypher helpers the [GraphObjectManagerStore] and
 * [GraphProvisioner] need for the handful of things Drivine's object API can't express (mutation /
 * multi-hop traversal) — hoisted straight onto [PersistenceManager] so they don't have to depend on the
 * broad [CypherSearch] surface. Inline Cypher only (no resource-name resolution — that's [CypherSearch]'s
 * job for [DrivineStore]).
 */
private val cypherOpsLogger = LoggerFactory.getLogger("com.embabel.agent.rag.graph.CypherOps")

private fun log(purpose: String, cypher: String, params: Map<String, Any?>) {
    // Purpose at INFO (one concise line per call); the full query + bound params at DEBUG so INFO logs
    // stay readable and params aren't echoed by default.
    cypherOpsLogger.info("[{}] executing inline Cypher", purpose)
    cypherOpsLogger.debug("[{}] params: {}\n{}", purpose, params, cypher)
}

// [render] carries `$(key)` template values that Drivine inlines into the query text — for the parts of
// Cypher that *cannot* be a bound parameter (labels, relationship types), portably across engines that
// lack Neo4j 5's native `$()`. Values here are trusted (config-derived), never end-user input.

/** Run inline [cypher] for effect (a write / MERGE / DETACH DELETE with no result to read). */
fun PersistenceManager.executeCypher(
    purpose: String,
    cypher: String,
    params: Map<String, Any?> = emptyMap(),
    render: Map<String, Any> = emptyMap(),
) {
    log(purpose, cypher, params)
    execute(QuerySpecification.withStatement(cypher).bind(params).render(render))
}

/**
 * Run inline [cypher] that returns a single **map-valued** column (`RETURN {k: v} AS row`), read as one
 * map per row. Note the narrow contract: Drivine unwraps a single *scalar* column to the bare value and
 * returns a multi-column record as a list, and `transform(Map)` can convert neither — for a scalar column
 * (`RETURN x AS x`) use [queryForScalars] instead.
 */
@Suppress("UNCHECKED_CAST")
fun PersistenceManager.queryForRows(
    purpose: String,
    cypher: String,
    params: Map<String, Any?> = emptyMap(),
    render: Map<String, Any> = emptyMap(),
): List<Map<String, Any>> {
    log(purpose, cypher, params)
    return query(QuerySpecification.withStatement(cypher).bind(params).render(render).transform(Map::class.java)) as List<Map<String, Any>>
}

/**
 * Run inline [cypher] that returns a single **scalar** column (`RETURN x AS x`), one value per row.
 * Drivine unwraps a single-column record to the bare value, so such a result is read as scalars of [type]
 * — passing it to [queryForRows] (which needs a map-valued column) throws a Jackson mismatch.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Any> PersistenceManager.queryForScalars(
    purpose: String,
    cypher: String,
    type: Class<T>,
    params: Map<String, Any?> = emptyMap(),
    render: Map<String, Any> = emptyMap(),
): List<T> {
    log(purpose, cypher, params)
    return query(QuerySpecification.withStatement(cypher).bind(params).render(render).transform(type)) as List<T>
}

/** Run inline [cypher] that returns a single integer (e.g. a `count(*)`). */
fun PersistenceManager.queryForInt(
    purpose: String,
    cypher: String,
    params: Map<String, Any?> = emptyMap(),
    render: Map<String, Any> = emptyMap(),
): Int {
    log(purpose, cypher, params)
    return getOne(QuerySpecification.withStatement(cypher).bind(params).render(render).transform(Int::class.java))
}
