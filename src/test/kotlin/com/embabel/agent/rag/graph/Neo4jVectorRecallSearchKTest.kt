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
import com.embabel.agent.rag.graph.model.ChunkNode
import com.embabel.agent.rag.graph.model.ChunkNodeQueryDsl
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.query.transform
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What `k` means to a Lucene-backed vector index, measured rather than assumed — and measured through
 * the API the application actually calls, not hand-written probe Cypher.
 *
 * `k` is the HNSW search beam width, not just a row count: the result queue IS the candidate queue.
 * Two effects compound on the filtered path (`loadNearest` + `where { }`). The index under-recalls at
 * practical `k`; and the filter applies AFTER the index yields, so a tenant-scoped caller receives
 * roughly `k × selectivity` rows, drawn from the globally-nearest rather than the nearest in scope.
 *
 * A third effect appears only once you over-fetch. The index's *yielded* score is approximate — Neo4j
 * 2026.04 creates vector indexes with `vector.quantization.enabled: true` — so trimming a wide beam by
 * that score discards true matches the beam already found. Over-fetching buys row count; over-fetching
 * **plus an exact re-rank** buys row correctness. Drivine performs that re-rank itself whenever
 * `searchK` is set (`Neo4j5Grammar.vectorSearchHead`), which is what these tests pin end to end.
 *
 * Assertions are on RELATIONSHIPS (a wider beam must not lose true matches; the scoped caller's rows
 * must fill up) rather than hardcoded ranks, so they stay meaningful if the corpus or index shifts.
 * The numbers are printed, because the size of the gap is the interesting part.
 *
 * ## Isolation
 *
 * The probe owns its label and its index, and deliberately does NOT carry the `Chunk` label: other
 * suites' `reembedAll` drops and recreates the shared chunk vector index mid-run, which would look
 * like a recall failure here, and 9k probe vectors in the shared index would perturb those suites in
 * turn.
 *
 * That is possible because a vector search does not re-`MATCH` the fragment's label —
 * `FragmentVectorSearchBuilder` projects whatever node the index `CALL` yields. So a `RecallProbe`
 * node carrying `ChunkNode`'s properties projects as a `ChunkNode`, and `partitionLabel` (drivine
 * 0.0.79) aims the read at this probe's own index at runtime.
 *
 * The index name is not free: `partitionLabel` re-derives it as `${label}_${property}_vector`, which
 * is exactly `VectorIndexSpec.defaultName()`. Hence `RecallProbe_embedding_vector`.
 */
@SpringBootTest(classes = [Neo4jVectorRecallSearchKTest.Config::class])
@ActiveProfiles("neo4j")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(900)
class Neo4jVectorRecallSearchKTest {

    @Configuration
    @Profile("neo4j")
    @EnableDrivine
    @EnableDrivineTestConfig
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    class Config {
        @Bean("graph")
        fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("graph")

        @Bean
        fun graphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager = factory.get("graph")
    }

    @Autowired
    @Qualifier("graph")
    lateinit var pm: PersistenceManager

    @Autowired
    lateinit var gom: GraphObjectManager

    private companion object {
        const val LABEL = "RecallProbe"

        /** Must equal `VectorIndexSpec.defaultName()`, or `partitionLabel` will not resolve it. */
        const val INDEX = "${LABEL}_embedding_vector"
        const val DIMS = 1536
        const val CORPUS = 9_000
        const val CLUSTERED = 2_000
        const val TOP_K = 40
        const val OVER_FETCH = 200
        const val TENANT = "a"
    }

    private val rng = Random(20260814)

    /** Named `queryVector`, not `query`: inside a `where { }` block `query` is the DSL receiver. */
    private lateinit var queryVector: List<Float>

    @BeforeAll
    fun seedCorpus() {
        pm.execute(QuerySpecification.withStatement("MATCH (n:$LABEL) DETACH DELETE n"))

        // Quantization is pinned, not inherited. It is the single setting most likely to move under a
        // Neo4j upgrade, and it is the one this measurement is most sensitive to: it is exactly why the
        // index's yielded score is approximate, and therefore why trimming a widened beam by that score
        // loses true matches the beam already found. An unpinned default would let a patch bump silently
        // change what the numbers below mean.
        //
        // Pinned ON, matching the current server default, so the harness keeps measuring the
        // configuration production actually runs rather than a more favourable one.
        //
        // Going through VectorIndexSpec rather than hand-written DDL also derives the index name via
        // defaultName(), which is the same derivation `partitionLabel` uses on the read — so the two
        // cannot drift apart.
        pm.indexes.ensure(
            VectorIndexSpec(
                LABEL, "embedding", DIMS, SimilarityFunction.COSINE,
                engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = true)),
            ),
        )

        // One tight cluster, plus a spread remainder. The query sits just off the cluster centre,
        // which is what makes navigation settle inside it. A hash-seeded fake embedding would not
        // reproduce this at all: its vectors are near-orthogonal, and near-orthogonal vectors cannot
        // saturate a neighbour list, which is the mechanism under test.
        val centre = unit(List(DIMS) { rng.nextDouble(-1.0, 1.0) })
        queryVector = unit(centre.map { it + rng.nextDouble(-0.01, 0.01) })

        // ChunkNode-shaped, so the rows are readable through `gom`. Only id/text/urtext/parentId are
        // non-null on the fragment; `tenant` goes to the `metadata.` @PropertyBag, which is where
        // production's scoping keys (`source`, …) live too.
        //
        // Generated per batch, not up front. 9,000 x 1536 boxed Floats held at once is ~14M objects and
        // a few hundred MB live, on a JVM whose heap defaults to a fraction of the host's RAM and which
        // is already sharing the box with a Neo4j container. Per batch, peak stays around 18 MB.
        (0 until CORPUS).chunked(500).forEach { ids ->
            val batch = ids.map { i ->
                val v = if (i < CLUSTERED) {
                    unit(centre.map { it + rng.nextDouble(-0.02, 0.02) })
                } else {
                    unit(List(DIMS) { rng.nextDouble(-1.0, 1.0) })
                }
                mapOf(
                    "id" to "probe-$i",
                    "text" to "probe chunk $i",
                    "urtext" to "probe chunk $i",
                    "parentId" to "probe-parent",
                    "embedding" to v,
                    "tenant" to if (i % 2 == 0) TENANT else "b",
                )
            }
            pm.execute(
                QuerySpecification.withStatement(
                    """
                    UNWIND ${'$'}rows AS r
                    CREATE (n:$LABEL {
                        id: r.id, text: r.text, urtext: r.urtext, parentId: r.parentId,
                        embedding: r.embedding, `metadata.tenant`: r.tenant
                    })
                    """.trimIndent(),
                ).bind(mapOf("rows" to batch)),
            )
        }
        pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(300)"))
    }

    @AfterAll
    fun cleanUp() {
        pm.execute(QuerySpecification.withStatement("DROP INDEX $INDEX IF EXISTS"))
        pm.execute(QuerySpecification.withStatement("MATCH (n:$LABEL) DETACH DELETE n"))
    }

    // -------------------------------------------------------------- ground truth (no index at all)

    /** Every vector scored, sorted. There is deliberately no API for this — it is the control. */
    private fun exhaustiveTopIds(limit: Int, tenant: String? = null): List<String> {
        val scope = if (tenant != null) "WHERE n.`metadata.tenant` = ${'$'}tenant" else ""
        return pm.query(
            QuerySpecification.withStatement(
                """
                MATCH (n:$LABEL) $scope
                WITH n, vector.similarity.cosine(n.embedding, ${'$'}q) AS score
                ORDER BY score DESC LIMIT ${'$'}limit
                RETURN n.id AS id
                """.trimIndent(),
            ).bind(mapOf("q" to queryVector, "limit" to limit, "tenant" to tenant)).transform<String>(),
        )
    }

    // -------------------------------------------------------------- through the real API

    /** Unfiltered read through `gom`, aimed at this probe's own index by [LABEL] at runtime. */
    private fun apiIds(searchK: Int?): List<String> =
        gom.loadNearest(
            ChunkNode::class.java,
            queryVector,
            TOP_K,
            null, // threshold: the RagRequest default of 0.8 would truncate and corrupt the metric
            searchK,
            LABEL,
        ).map { it.value.id }

    /** The production shape: `loadNearest` plus a translated [PropertyFilter] `where { }`. */
    private fun apiFilteredIds(searchK: Int?): List<String> =
        gom.loadNearest(
            ChunkNode::class.java,
            ChunkNodeQueryDsl.INSTANCE,
            queryVector,
            TOP_K,
            null,
            searchK,
            LABEL,
        ) {
            where { query.applyFilters(PropertyFilter.Eq("tenant", TENANT), null) }
        }.map { it.value.id }

    // -------------------------------------------------------------- tests

    @Test
    fun `over-fetching through gom recovers recall the index loses at practical k`() {
        val truth = exhaustiveTopIds(TOP_K).toSet()

        val narrow = apiIds(searchK = null).toSet()
        val wide = apiIds(searchK = OVER_FETCH).toSet()

        val recallNarrow = truth.intersect(narrow).size.toDouble() / TOP_K
        val recallWide = truth.intersect(wide).size.toDouble() / TOP_K

        println("[gom] recall@$TOP_K  searchK=null -> $recallNarrow   searchK=$OVER_FETCH -> $recallWide")

        assertEquals(TOP_K, narrow.size, "an unfiltered read fills topK either way")
        assertEquals(TOP_K, wide.size, "an unfiltered read fills topK either way")
        assertTrue(
            recallWide >= recallNarrow,
            "a wider beam must not lose true matches: $recallWide < $recallNarrow",
        )
        // Drivine re-ranks the over-fetched pool by exact cosine, so the wide read is not merely
        // wider - it is correctly ordered. That is what makes 1.0 reachable rather than ~0.95.
        assertEquals(
            1.0, recallWide,
            "an over-fetched, exactly re-ranked read should return the true top $TOP_K",
        )
    }

    @Test
    fun `a filtered read under-delivers at k and fills up under searchK`() {
        val truth = exhaustiveTopIds(TOP_K, tenant = TENANT).toSet()

        val narrow = apiFilteredIds(searchK = null)
        val wide = apiFilteredIds(searchK = OVER_FETCH)

        val recallNarrow = truth.intersect(narrow.toSet()).size.toDouble() / TOP_K
        val recallWide = truth.intersect(wide.toSet()).size.toDouble() / TOP_K

        println(
            "[gom] tenant-filtered rows: searchK=null -> ${narrow.size}/$TOP_K (recall $recallNarrow)   " +
                "searchK=$OVER_FETCH -> ${wide.size}/$TOP_K (recall $recallWide)",
        )

        // The contract break: the caller asked for TOP_K and the post-yield filter cannot fill it,
        // because the rows it needed were never returned by the index.
        assertTrue(
            narrow.size < TOP_K,
            "a post-yield filter over $TOP_K index rows cannot fill $TOP_K slots - got ${narrow.size}",
        )
        assertEquals(
            TOP_K, wide.size,
            "over-fetching to $OVER_FETCH should fill all $TOP_K scoped slots",
        )
        assertTrue(
            recallWide > recallNarrow,
            "over-fetching must raise scoped recall: $recallWide !> $recallNarrow",
        )
    }

    /**
     * The `metadata.` @PropertyBag must survive a vector search, not just a `load`.
     *
     * `FragmentQueryBuilder` counts `propertyBags.isNotEmpty()` as a reason to project via
     * `properties(n).*`; `FragmentVectorSearchBuilder` and `FragmentFullTextSearchBuilder` do not,
     * so they take the explicit field-mapping branch — and a bag has no single property to map.
     */
    @Test
    fun `the metadata property bag survives a vector search`() {
        val viaSearch = gom.loadNearest(ChunkNode::class.java, queryVector, 1, null, null, LABEL).single().value

        // On-disk truth. `load` cannot be the control here: it emits MATCH (n:Chunk {id: …}) and the
        // probe deliberately carries no Chunk label.
        val onDisk = pm.query(
            QuerySpecification.withStatement(
                "MATCH (n:$LABEL {id: ${'$'}id}) RETURN n.`metadata.tenant` AS t",
            ).bind(mapOf("id" to viaSearch.id)).transform<String>(),
        ).single()

        println("[gom] metadata on disk         -> tenant=$onDisk")
        println("[gom] metadata via loadNearest -> ${viaSearch.freeFormMetadata}")

        assertEquals(TENANT, onDisk, "precondition: the bagged key is on disk")
        assertEquals(
            TENANT, viaSearch.freeFormMetadata["tenant"],
            "the bagged scoping key must survive the vector search projection",
        )
    }

    private fun unit(v: List<Double>): List<Float> {
        val norm = sqrt(v.sumOf { it * it })
        return v.map { (it / norm).toFloat() }
    }
}
