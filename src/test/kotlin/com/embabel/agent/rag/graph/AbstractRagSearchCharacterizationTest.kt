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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.common.core.types.SimilarityResult
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Cross-engine executable **spec** for the retrieval paths — vector search, full-text search, and
 * `reembedAll` — asserting behaviour, not implementation: "a full-text search for a seeded token
 * returns that chunk", "a vector search for a chunk's own text retrieves it", "search still works
 * after reembedAll". Behaviour transitively proves the index exists under the name the search binds:
 * a wrongly-named, absent, or unpopulated index makes the search throw or return nothing.
 *
 * The spec is written against the [RagStoreUnderTest] seam, so the **same** contract runs against
 * both store implementations — the current hand-rolled [DrivineStore] and the
 * [GraphObjectManager][org.drivine.manager.GraphObjectManager]-backed store — on each of Neo4j,
 * FalkorDB, and Memgraph. When the new store's column of that matrix is fully green it is a proven
 * drop-in for the old one.
 *
 * Requires a [DeterministicEmbeddingModel] so that identical text embeds identically — that is what
 * makes the vector-search assertion a real retrieval check rather than "it executed".
 */
abstract class AbstractRagSearchCharacterizationTest {

    protected val logger = LoggerFactory.getLogger(javaClass)

    /** The store under test, wired with a [DeterministicEmbeddingModel] and the engine's dialect. */
    protected abstract val store: RagStoreUnderTest

    /** The persistence manager for the same database, used only for test cleanup. */
    protected abstract val persistenceManager: PersistenceManager

    /** Human-readable engine name for messages. */
    protected abstract val engineName: String

    /**
     * Whether `@AfterEach` deletes this test's nodes. Default true. **Memgraph overrides to false**:
     * its HNSW vector index does not evict a `DETACH DELETE`d node (the stale entry survives even an
     * index recreate), so a later test's `vector_search` dereferences a tombstone and throws "get
     * properties from a deleted object". Every test uses fresh UUIDs, so simply *not* deleting on
     * Memgraph leaves the index holding only live nodes — the approach Drivine's own suite uses.
     */
    protected open val cleansUpByDeletion: Boolean = true

    private val trackedNodeIds = mutableListOf<String>()

    @BeforeEach
    fun provisionSchema() {
        trackedNodeIds.clear()
        // Provision up front: the indexes must exist before any search. Idempotent across tests.
        store.provision()
    }

    @AfterEach
    fun cleanUp() {
        if (cleansUpByDeletion && trackedNodeIds.isNotEmpty()) {
            persistenceManager.execute(
                QuerySpecification.withStatement("MATCH (n) WHERE n.id IN \$ids DETACH DELETE n")
                    .bind(mapOf("ids" to trackedNodeIds))
            )
        }
    }

    private fun track(vararg ids: String) = trackedNodeIds.addAll(ids)

    /**
     * Ingest a single-section document through the real public ingestion path
     * ([DrivineStore.writeAndChunkDocument]), which chunks *and embeds* the text. Returns the
     * persisted, embedded chunks.
     */
    protected fun seedDocument(text: String): List<Chunk> {
        val leafId = UUID.randomUUID().toString()
        val docId = UUID.randomUUID().toString()
        // The chunker derives chunk text from a leaf section's content (LeafSection.content == text),
        // so seed a leaf with real body text rather than a bare container section (which yields
        // empty-text chunks — a latent hole the count-only ingestion assertions never caught).
        val leaf = LeafSection(
            id = leafId,
            title = "Characterization Section",
            text = text,
            parentId = docId,
        )
        val doc = MaterializedDocument(
            id = docId,
            uri = "test://characterization-${engineName.lowercase()}-$docId",
            title = "Characterization Document",
            children = listOf(leaf),
        )
        track(docId, leafId)

        val chunkIds = store.writeAndChunkDocument(doc)
        track(*chunkIds.toTypedArray())

        val chunks = store.findAllChunksById(chunkIds).toList()
        assertTrue(chunks.isNotEmpty(), "[$engineName] ingestion should have produced at least one embedded chunk")
        assertTrue(
            chunks.any { it.text.isNotBlank() },
            "[$engineName] ingested chunk should carry non-empty text",
        )
        return chunks
    }

    /**
     * A distinctive word taken from the actual persisted chunk text — the chunker derives chunk text
     * from a leaf section's content, so rather than assume a specific token survives ingestion we
     * search for a word we can see is really in the indexed text. Matching a *seeded* chunk id is what
     * the assertion checks, so the word need only be present, not globally unique.
     */
    protected fun distinctiveTokenFrom(chunks: List<Chunk>): String =
        chunks.asSequence()
            .flatMap { Regex("[A-Za-z]{6,}").findAll(it.text).map { m -> m.value } }
            .firstOrNull()
            ?: error("[$engineName] no searchable word found in persisted chunk text: ${chunks.map { it.text.take(80) }}")

    @Suppress("UNCHECKED_CAST")
    private fun chunkOf(result: SimilarityResult<*>): Chunk = result.match as Chunk

    /**
     * Full-text (Lucene) indexes on all three engines populate **asynchronously** — a query issued
     * immediately after the write can miss the just-indexed node (vector indexes do not show this
     * lag). Poll briefly so the characterization is not flaky. Engine-agnostic: no `db.awaitIndexes`.
     */
    private fun textSearchWithRetry(
        token: String,
        attempts: Int = 40,
        delayMs: Long = 250,
    ): List<SimilarityResult<out Chunk>> {
        var last = emptyList<SimilarityResult<out Chunk>>()
        repeat(attempts) {
            last = store.textSearchChunks(token)
            if (last.isNotEmpty()) return last
            Thread.sleep(delayMs)
        }
        return last
    }

    @Test
    fun `provision is idempotent`() {
        // @BeforeEach already provisioned once; a second call must not throw on any engine.
        store.provision()
    }

    @Test
    fun `full-text search retrieves a chunk by a rare seeded token`() {
        val chunks = seedDocument(
            "An introduction to graph retrieval covering embeddings, chunking and characterization. " +
                "This paragraph carries enough distinctive words to be found by a full-text search.",
        )
        val seededIds = chunks.map { it.id }.toSet()
        val token = distinctiveTokenFrom(chunks)

        val results = textSearchWithRetry(token)

        assertTrue(
            results.any { chunkOf(it).id in seededIds },
            "[$engineName] full-text search for '$token' should return a seeded chunk; got ${results.size} results",
        )
    }

    @Test
    fun `vector search retrieves the semantically-identical chunk`() {
        val chunks = seedDocument(
            "Vector retrieval characterization body. This paragraph is embedded deterministically " +
                "so that querying with its own text returns it with similarity 1.0.",
        )
        val target = chunks.first()

        val results = store.vectorSearchChunks(target.text)

        assertTrue(
            results.any { chunkOf(it).id == target.id },
            "[$engineName] vector search for a chunk's own text should retrieve that chunk; got ${results.size} results",
        )
    }

    @Test
    fun `reembedAll rebuilds indexes and search still works`() {
        val chunks = seedDocument(
            "Content that will survive a re-embed. This paragraph mentions retrieval and embeddings " +
                "so it can be located again after the indexes are rebuilt.",
        )
        val token = distinctiveTokenFrom(chunks)

        // The migration re-points this at SchemaManager.recreateAll(...). Today it drops and recreates
        // the vector indexes and re-provisions. The behaviour we must preserve: it completes on every
        // engine (the current DROP INDEX is Neo4j-only — a known gap) and search works afterwards.
        val report = store.reembedAll()
        logger.info("[{}] reembedAll report: {}", engineName, report)

        val results = textSearchWithRetry(token)
        assertTrue(
            results.isNotEmpty(),
            "[$engineName] full-text search should still return results after reembedAll",
        )
    }
}
