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

import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.graph.model.ChunkNodeQueryDsl
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.WhereBuilder
import org.drivine.query.dsl.query
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Renders [applyFilter] to Cypher with no database, so every [PropertyFilter] shape a caller can build
 * is proven translatable here rather than at the first query that uses it.
 *
 * [PropertyFilter.HasElement] is why this test exists: it threw `UnsupportedOperationException`, and the
 * one filter that builds it — a document-sharing ownership predicate, `own chunks OR shared with me` —
 * is on every keyword and vector document search. Nothing failed at build time; the search failed at
 * runtime, and the integration suite that would have caught it was excluded from CI.
 */
class ChunkPropertyFilterTranslationTest {

    private val grammar = Neo4j5Grammar(ApocSortMapsEmitter())

    private fun render(filter: PropertyFilter): Pair<String?, Map<String, Any?>> {
        val builder = WhereBuilder(ChunkNodeQueryDsl.INSTANCE)
        val block: context(WhereBuilder<ChunkNodeQueryDsl>) () -> Unit = { query.applyFilter(filter) }
        block(builder)
        return CypherGenerator.buildWhereClause(builder.conditions, null, grammar).whereClause to
            CypherGenerator.extractBindings(builder.conditions, null)
    }

    @Test
    fun `HasElement renders reversed membership - the bound value left, the list property right`() {
        val (where, bindings) = render(PropertyFilter.HasElement("visibleTo", "bob"))
        assertEquals("\$param_n_metadata_visibleTo_0 IN n.`metadata.visibleTo`", where)
        assertEquals("bob", bindings["param_n_metadata_visibleTo_0"])
    }

    @Test
    fun `the document-sharing ownership predicate translates whole - own chunks OR shared with me`() {
        val (where, bindings) = render(
            PropertyFilter.Or(
                listOf(
                    PropertyFilter.Eq("ingestedBy", "bob"),
                    PropertyFilter.HasElement("visibleTo", "bob"),
                ),
            ),
        )
        assertEquals(
            "(n.`metadata.ingestedBy` = \$param_n_metadata_ingestedBy_0 " +
                "OR \$param_n_metadata_visibleTo_1 IN n.`metadata.visibleTo`)",
            where,
        )
        assertEquals("bob", bindings["param_n_metadata_ingestedBy_0"])
        assertEquals("bob", bindings["param_n_metadata_visibleTo_1"])
    }
}
