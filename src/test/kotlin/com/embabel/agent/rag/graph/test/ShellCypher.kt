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

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory

/**
 * Runs the ad-hoc Cypher the test shell types at the database: counts, listings and schema
 * procedures that are about inspecting a graph by hand, not about the store's own reads.
 */
class ShellCypher(private val persistenceManager: PersistenceManager) {

    private val logger = LoggerFactory.getLogger(ShellCypher::class.java)

    fun query(purpose: String, query: String, params: Map<String, *>): QueryResult {
        logger.info("[{}] query\n\tparams: {}\n{}", purpose, params, query)
        @Suppress("UNCHECKED_CAST")
        val rows = persistenceManager.query(
            QuerySpecification.withStatement(query).bind(params).transform(Map::class.java)
        ) as List<Map<String, Any>>
        return QueryResult(rows)
    }

    fun queryForInt(query: String, params: Map<String, *> = emptyMap<String, Any>()): Int =
        persistenceManager.getOne(QuerySpecification.withStatement(query).bind(params).transform(Int::class.java))
}
