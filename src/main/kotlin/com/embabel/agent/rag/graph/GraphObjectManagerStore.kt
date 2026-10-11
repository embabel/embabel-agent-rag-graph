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
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.graph.fulltext.FULL_TEXT_SIMILARITY_FLOOR
import com.embabel.agent.rag.graph.fulltext.CompositeRequiredTermExtractor
import com.embabel.agent.rag.graph.fulltext.RequiredTermExtractor
import com.embabel.agent.rag.graph.fulltext.searchPreparedQuery
import com.embabel.agent.rag.graph.fulltext.syntaxNotesFor
import com.embabel.agent.rag.graph.model.ChunkExpandView
import com.embabel.agent.rag.graph.model.ChunkNode
import com.embabel.agent.rag.graph.model.ChunkPlaceFragment
import com.embabel.agent.rag.graph.model.ContentElementFragment
import com.embabel.agent.rag.graph.model.NextChunkLinkView
import com.embabel.agent.rag.graph.model.ParentLinkView
import com.embabel.agent.rag.graph.model.ContainerSectionNode
import com.embabel.agent.rag.graph.model.ContentElementNode
import com.embabel.agent.rag.graph.model.ContentTreeView
import com.embabel.agent.rag.graph.model.ContentElementRepositoryInfoImpl
import com.embabel.agent.rag.graph.model.DocumentNode
import com.embabel.agent.rag.graph.model.LeafSectionHeading
import com.embabel.agent.rag.graph.model.LeafSectionNode
import com.embabel.agent.rag.graph.model.SectionHeadingNode
import com.embabel.agent.rag.graph.model.ZoomOutView
// Generated Drivine query DSL for the @NodeFragment models: the `loadAll` / `count` { where { } / depth() }
// extensions, the `chunk` / `element` root accessors, and `ChunkNodeQueryDsl` for the filtered search forms.
import com.embabel.agent.rag.graph.model.ChunkNodeQueryDsl
import com.embabel.agent.rag.graph.model.chunk
import com.embabel.agent.rag.graph.model.count
import com.embabel.agent.rag.graph.model.element
import com.embabel.agent.rag.graph.model.loadAll
import com.embabel.agent.rag.service.ResultExpander
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.ingestion.RetrievableEnhancer
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContainerSection
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.ContentRoot
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.model.NavigableSection
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.support.FunctionRagFacet
import com.embabel.agent.rag.service.support.RagFacet
import com.embabel.agent.rag.service.support.RagFacetResults
import com.embabel.agent.rag.store.ContentElementRepositoryInfo
import com.embabel.agent.rag.store.DocumentDeletionResult
import com.embabel.agent.rag.store.EmbeddingAwareChunkingContentElementRepository
import com.embabel.agent.rag.store.EmbeddingBatchGenerator
import com.embabel.agent.rag.store.EmbeddingBatchResult
import com.embabel.agent.rag.store.EmbeddingIncompleteException
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.drivine.manager.CascadeType
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.delete
import org.drivine.manager.NullPolicy
import org.drivine.manager.PersistenceManager
import org.drivine.manager.count
import org.drivine.manager.load
import org.drivine.manager.loadAll
import org.drivine.manager.loadMatching
import org.drivine.manager.loadNearest
import org.drivine.query.dsl.instanceOf
import org.drivine.query.dsl.query
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec

/**
 * The `k` in `score / (score + k)`, the saturation that maps a raw BM25 score onto `[0, 1)`: a raw
 * score equal to `k` lands on 0.5. Used here only to explain a threshold, not to compute a score.
 */
private const val FULL_TEXT_BM25_K: Double = 3.0

/** Window size for [GraphObjectManagerStore.reembedAll] — bounds heap and per-request embedding size. */
private const val REEMBED_BATCH_SIZE = 256

/**
 * A threshold above this requires a raw full-text score above [FULL_TEXT_BM25_K] — already
 * a strong match. Empty results above it are far more likely a mis-set threshold than an empty corpus.
 */
private const val FULL_TEXT_SUPPRESSION_WARNING_THRESHOLD: Double = 0.5


/**
 * A [Chunk]-focused RAG store backed by Drivine's [GraphObjectManager] and the `@NodeFragment` models
 * ([ChunkNode] / [LeafSectionNode] / [ContainerSectionNode] / [DocumentNode]).
 *
 * Content structure and embeddings are persisted with `gom.save` / `gom.saveAll` (embeddings land in the
 * engine's native vector type — e.g. `vecf32` on FalkorDB — automatically). Retrieval runs through the
 * object manager: vector search is `gom.loadNearest`, full-text search is `gom.loadMatching`, and
 * metadata-filtered search adds a typed `where { }` that compiles an embabel [PropertyFilter] into
 * Drivine predicates ([applyFilter]). Context expansion walks typed graph views — [ZoomOutView]
 * (`HAS_PARENT`) and [ChunkExpandView] (`NEXT_CHUNK`).
 *
 * The hierarchy is written the same way: `HAS_PARENT` and `NEXT_CHUNK` edges by saving link views
 * ([ParentLinkView], [NextChunkLinkView]), a section's place by saving the section.
 *
 * A document is deleted the same way, as a cascade down [ContentTreeView].
 *
 * **Go through the object manager first.** It writes the Cypher for whichever engine is
 * configured, so a read or write expressed as a fragment, a view or the query DSL runs on Neo4j,
 * FalkorDB and Memgraph alike; a hand-written statement runs wherever its author tried it. Write
 * Cypher here only when the object manager has no reasonable way to say it, for features or for
 * speed, and say why at the site. When Drivine falls short, the better fix is usually in Drivine.
 *
 * One thing is still Cypher: the entity-to-chunk traversal ([findChunksForEntity]). The entity's
 * label is configuration ([GraphRagServiceProperties.entityNodeName]) and a fragment's labels are
 * fixed at compile time, so there is no fragment to hang the relationship on.
 */
class GraphObjectManagerStore(
    private val gom: StatelessGraphObjectManager,
    private val persistenceManager: PersistenceManager,
    private val properties: GraphRagServiceProperties,
    chunkerConfig: ContentChunker.Config,
    chunkTransformer: ChunkTransformer,
    embeddingService: EmbeddingService,
) : EmbeddingAwareChunkingContentElementRepository(
    chunkerConfig = chunkerConfig,
    chunkTransformer = chunkTransformer,
    embeddingService = embeddingService,
), GraphRagStore {

    override val name get() = properties.name
    override val enhancers: List<RetrievableEnhancer> = emptyList()
    // Derived from the mode so the two cannot drift: see syntaxNotesFor.
    override val luceneSyntaxNotes get() = syntaxNotesFor(properties.queryMode)

    /**
     * Picks the terms LITERAL mode requires. Swap in a document-frequency or model-backed
     * implementation where the lexical rules are too narrow — see [RequiredTermExtractor].
     */
    var requiredTermExtractor: RequiredTermExtractor = CompositeRequiredTermExtractor()
    override fun supportsType(type: String): Boolean = type == Chunk::class.java.simpleName

    init {
        // The chunk label is the compile-time constant "Chunk" on the ChunkNode @NodeFragment, but the
        // index specs, NEXT_CHUNK edges and findChunksForEntity address the node via
        // properties.chunkNodeName. If those diverge, gom reads/writes "Chunk" while indexes and edges
        // live on another label — silent empty results. Pin them equal until the fragment label is made
        // configurable end to end.
        require(properties.chunkNodeName == "Chunk") {
            "GraphObjectManagerStore is pinned to the ChunkNode fragment label \"Chunk\", but " +
                "chunkNodeName='${properties.chunkNodeName}'. The @NodeFragment label is not configurable; " +
                "a mismatch yields silent empty results."
        }
        properties.validate()
    }

    /**
     * The chunk vector / full-text index specs. The index *name* is left unset so Drivine derives its
     * convention (`{label}_{property}_vector` / `{label}_{property}_fulltext`) — the same name
     * `gom.loadNearest` / `gom.loadMatching` resolve — so provisioning and search agree with no name
     * literal to keep in sync.
     */
    // `by lazy`, because `dimensions` interrogates the embedding service and a deployment may
    // legitimately have none yet — one whose provider key arrives at first-run setup rather
    // than at boot. Reading it in a constructor-body initializer made merely CONSTRUCTING the
    // store fail there, which took down the whole application context; every consumer of an
    // absent embedding service should fail at the point of use instead. The dimension is only
    // meaningful when an index is actually provisioned or searched, which is when this now
    // resolves.
    /**
     * Recomputed on every read, NEVER cached.
     *
     * `by lazy` here was a bug with a long fuse. The spec was built once, at the first
     * provisioning — when the deployment's model was whatever it booted with — and then reused
     * for the life of the process. [reembedAll] drops the index by identity (which ignores width,
     * so that part worked), rewrites every chunk at the new model's width, and calls [provision]
     * to remake the index — from the cached spec, at the OLD width.
     *
     * The result was chunks holding 3072-wide vectors under an index declaring 1536, with nothing
     * reporting it: observed on a live appliance changing text-embedding-3-small to
     * text-embedding-3-large. A spec that names a width must be stated with a `get()`, never cached.
     *
     * The cost of not caching is one object per access. The cost of caching it was an index that
     * cannot describe what is stored.
     */
    private val chunkVectorIndex: VectorIndexSpec
        get() = VectorIndexSpec(
            properties.chunkNodeName, "embedding", embeddingService.dimensions, SimilarityFunction.COSINE,
        )
    private val chunkFullTextIndex = FullTextIndexSpec(properties.chunkNodeName, listOf("text"))

    // Mirrors outline's `where` and `orderBy`, so a document's sections are read off the index in order.
    private val sectionOrderIndex = RangeIndexSpec("ContentElement", listOf("root_document_id", "ordinal"))

    // What chunksOf looks a section's chunks up by: the leaf they were cut from, or the section holding them.
    private val chunkSectionIndexes = listOf(
        RangeIndexSpec(properties.chunkNodeName, listOf("leaf_section_id")),
        RangeIndexSpec(properties.chunkNodeName, listOf("container_section_id")),
    )

    private val provisioner = GraphProvisioner(persistenceManager)

    override fun provision() {
        logger.info("Provisioning (GraphObjectManager store) for '{}' (dim={})", properties.name, embeddingService.dimensions)
        provisioner.ensureSchema(
            vectorIndexes = listOf(chunkVectorIndex),
            fullTextIndexes = listOf(chunkFullTextIndex),
            constraints = listOf(UniquenessConstraintSpec(properties.entityNodeName, "id")),
            rangeIndexes = listOf(sectionOrderIndex) + chunkSectionIndexes,
        )
        logger.info("Provisioning complete")
    }

    // ----- Persistence via GraphObjectManager + models -----

    override fun save(element: ContentElement): ContentElement {
        when (element) {
            // A ChunkNode built from a core Chunk carries a null embedding (the vector is written
            // separately by persistChunksWithEmbeddings). Drivine's default save is a merge-patch
            // (NullPolicy.IGNORE) — it skips null fields — so re-saving a chunk's structure/text never
            // clears its stored embedding. Pass NullPolicy.CLEAR only to deliberately overwrite with nulls.
            is Chunk -> gom.save(ChunkNode.from(element))
            is LeafSection -> gom.save(LeafSectionNode.from(element))
            is MaterializedDocument -> gom.save(DocumentNode.from(element))
            is ContainerSection -> gom.save(ContainerSectionNode.from(element))
            else -> throw UnsupportedOperationException(
                "GraphObjectManagerStore has no model for ${element::class.simpleName} yet",
            )
        }
        return element
    }

    override fun persistChunksWithEmbeddings(chunks: List<Chunk>, embeddings: Map<String, FloatArray>) {
        val nodes = chunks.map { ChunkNode.from(it, embeddings[it.id]?.toList()) }
        gom.saveAll(nodes)
    }

    override fun findAllChunksById(chunkIds: List<String>): Iterable<Chunk> =
        gom.loadAll<ChunkNode> {
            where { query.id inList chunkIds }
        }.map { it.toCoreType() }

    // ----- Search: CoreSearchOperations / FilteringVectorSearch / FilteringTextSearch -----

    /**
     * Unfiltered vector search: [gom.loadNearest] resolves the chunk vector index by label/property and
     * returns typed [ChunkNode]s, honouring [request]'s `topK` and `similarityThreshold`.
     */
    override fun <T : Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        require(clazz == Chunk::class.java) {
            "GraphObjectManagerStore vectorSearch only supports Chunk, got: $clazz"
        }
        return chunkVectorSearch(request.query, request.topK, request.similarityThreshold).asResultsOf()
    }

    /** Unfiltered full-text search via [gom.loadMatching] — scored, normalized, cross-engine. */
    override fun <T : Retrievable> textSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        require(clazz == Chunk::class.java) {
            "GraphObjectManagerStore textSearch only supports Chunk, got: $clazz"
        }
        return chunkFullTextSearch(request.query, request.topK, request.similarityThreshold).asResultsOf()
    }

    /**
     * Metadata-filtered vector search: `loadNearest` plus the [applyFilter] `where { }`.
     *
     * Over-fetches the index beam, because this is the path where `topK` does not mean what the caller
     * thinks. The predicates apply *after* the index yields, so asking for exactly `topK` returns
     * roughly `topK × selectivity` rows drawn from the globally-nearest rather than the nearest in
     * scope. `searchK` widens the beam and moves the trim after the filter; Drivine then re-ranks the
     * survivors by exact similarity, which is what makes the wider beam correct and not merely wider.
     * The multiplier is [GraphRagServiceProperties.filteredSearchOverFetch] — policy belongs here,
     * where the selectivity is knowable, rather than in Drivine.
     */
    override fun <T : Retrievable> vectorSearchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
        metadataFilter: PropertyFilter?,
        entityFilter: EntityFilter?,
    ): List<SimilarityResult<T>> {
        require(clazz == Chunk::class.java) {
            "GraphObjectManagerStore vectorSearchWithFilter only supports Chunk, got: $clazz"
        }
        val vector = embeddingService.embed(request.query).toList()
        return gom.loadNearest(
            ChunkNode::class.java, ChunkNodeQueryDsl.INSTANCE,
            vector, request.topK, request.similarityThreshold,
            searchK = properties.filteredSearchK(request.topK),
        ) {
            where { query.applyFilters(metadataFilter, entityFilter) }
        }.map { SimilarityResult.create(it.value.toCoreType(), it.score) }.asResultsOf()
    }

    /** Metadata-filtered full-text search: `loadMatching` plus the [applyFilter] `where { }`. */
    override fun <T : Retrievable> textSearchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
        metadataFilter: PropertyFilter?,
        entityFilter: EntityFilter?,
    ): List<SimilarityResult<T>> {
        require(clazz == Chunk::class.java) {
            "GraphObjectManagerStore textSearchWithFilter only supports Chunk, got: $clazz"
        }
        // A blank query would reach Lucene and throw a ParseException — empty-in, empty-out (see chunkFullTextSearch).
        if (request.query.isBlank()) return emptyList()
        return requiringIdentifiers(request.query) { searchText ->
            gom.loadMatching(
                ChunkNode::class.java, ChunkNodeQueryDsl.INSTANCE,
                searchText, request.topK, request.similarityThreshold,
            ) {
                where { query.applyFilters(metadataFilter, entityFilter) }
            }.map { SimilarityResult.create(it.value.toCoreType(), it.score) }
        }.asResultsOf()
    }

    /**
     * The search surface is generic over [T], but this store only ever serves [Chunk] (each public
     * method guards on `clazz == Chunk`). [T] is erased at runtime, so re-typing the chunk results to
     * `List<SimilarityResult<T>>` is an unavoidable unchecked cast — funnelled through this one spot
     * rather than repeated at every return.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Retrievable> List<SimilarityResult<out Chunk>>.asResultsOf(): List<SimilarityResult<T>> =
        this as List<SimilarityResult<T>>

    /** Chunk vector search shared by [vectorSearch] and the [facets] search function. */
    private fun chunkVectorSearch(query: String, topK: Int, threshold: Double): List<SimilarityResult<out Chunk>> {
        val vector = embeddingService.embed(query).toList()
        return gom.loadNearest<ChunkNode>(vector, topK, threshold)
            .map { SimilarityResult.create(it.value.toCoreType(), it.score) }
    }

    /**
     * Chunk full-text search shared by [textSearch] and the [facets] search function. A blank query
     * returns no results rather than reaching Lucene — an empty query string is ordinary input (an LLM
     * driving the tool surface can emit one), and the full-text parser would otherwise throw a
     * `ParseException`. Empty (not match-all) is the correct answer.
     *
     * Identifier-shaped tokens are promoted to required terms first — see [requiringIdentifiers].
     */
    private fun chunkFullTextSearch(query: String, topK: Int, threshold: Double): List<SimilarityResult<out Chunk>> {
        if (query.isBlank()) return emptyList()
        return requiringIdentifiers(query) { runFullTextSearch(it, topK, threshold) }
    }

    private fun <T> requiringIdentifiers(query: String, search: (String) -> List<T>): List<T> =
        searchPreparedQuery(query, properties.queryMode, requiredTermExtractor, search)

    private fun runFullTextSearch(query: String, topK: Int, threshold: Double): List<SimilarityResult<out Chunk>> =
        gom.loadMatching<ChunkNode>(query, topK, threshold)
            .map { SimilarityResult.create(it.value.toCoreType(), it.score) }
            .also { warnIfThresholdSuppressedResults(query, threshold, it.size) }

    /**
     * Explain an empty full-text result set that the caller's threshold most likely caused.
     *
     * Full-text scores are normalized as `score/(score + [FULL_TEXT_BM25_K])`.
     * A caller carrying a cosine-calibrated threshold ([RagRequest] defaults to 0.8) now filters
     * everything out where it previously filtered nothing — say so rather than returning a silent
     * empty list.
     *
     * Deliberately local rather than shared with rag-core's `Bm25Normalization`: this module
     * compiles against the published agent artifact, which need not carry that class yet.
     */
    private fun warnIfThresholdSuppressedResults(query: String, threshold: Double, resultCount: Int) {
        if (resultCount == 0 && threshold > FULL_TEXT_SUPPRESSION_WARNING_THRESHOLD) {
            logger.warn(
                """
                Full-text search for '{}' returned no results with similarityThreshold={}.
                Full-text scores are normalized to [0, 1) as score/(score+{}), so a threshold this high
                demands a very strong raw match — thresholds calibrated for cosine similarity do not
                transfer. Lower or omit the threshold and let topK rank.
                """.trimIndent(),
                query, threshold, FULL_TEXT_BM25_K,
            )
        }
    }

    // ----- RagFacetProvider -----

    override fun facets(): List<RagFacet<out Retrievable>> = listOf(
        FunctionRagFacet(name = "GraphObjectManagerRagService", searchFunction = ::search),
    )

    /**
     * Facet search: chunk vector + full-text, merged by score. Entity search is intentionally omitted —
     * entities are not modelled by this store yet (see [findChunksForEntity]).
     */
    fun search(ragRequest: RagRequest): RagFacetResults<Retrievable> {
        val results = mutableListOf<SimilarityResult<out Retrievable>>()
        if (ragRequest.contentElementSearch.types.contains(Chunk::class.java)) {
            results += runCatching {
                chunkVectorSearch(ragRequest.query, ragRequest.topK, ragRequest.similarityThreshold) +
                    // Vector keeps the caller's threshold; full-text cannot use it. See
                    // FULL_TEXT_SIMILARITY_FLOOR for the measurements behind the asymmetry.
                    chunkFullTextSearch(ragRequest.query, ragRequest.topK, FULL_TEXT_SIMILARITY_FLOOR)
            }.getOrElse { e ->
                logger.error("Error during gom-store facet search for '{}'", ragRequest.query, e)
                emptyList()
            }
        }
        val merged = results.distinctBy { it.match.id }.sortedByDescending { it.score }.take(ragRequest.topK)
        return RagFacetResults(facetName = name, results = merged)
    }

    /**
     * Re-embed every chunk with the current model, then rebuild the chunk vector index.
     *
     * The index is rebuilt even if re-embedding fails part way, so vector search is never left without
     * one. A failed embedding batch is retried in halves (see [EmbeddingBatchGenerator]). A chunk that
     * still can't be embedded is saved with no vector, so a stale vector from the previous model never
     * sits under an index declaring the new width; it still matches full-text search.
     *
     * @throws EmbeddingIncompleteException after the index is rebuilt, if some chunks could not be
     * embedded. The others are saved. Calling this again re-embeds every chunk, so it is a safe retry.
     */
    override fun reembedAll(onProgress: (done: Int, total: Int) -> Unit): ReembedReport {
        logger.info("reembedAll (gom store) start. model={} dim={}", embeddingService.name, embeddingService.dimensions)
        persistenceManager.indexes.drop(chunkVectorIndex)
        val outcome = try {
            reembedEveryChunk(onProgress)
        } finally {
            provision()
        }
        logger.info(
            "reembedAll (gom store) done. chunks={} missing={}",
            outcome.embedded,
            outcome.missingChunkIds.size,
        )
        outcome.failure()?.let { throw it }
        return ReembedReport(chunks = outcome.embedded, entities = 0)
    }

    /**
     * Re-embed every persisted chunk: load the [ChunkNode]s, recompute embeddings from their text, and
     * save them back through the object manager (Drivine rewrites the engine-native vector).
     *
     * One window of [REEMBED_BATCH_SIZE] chunks at a time: the ids are listed first and each window is
     * loaded by id, so no more than one window of chunks, each with its old vector, is ever on the heap.
     * Once a window embeds nothing at all, the service may be unavailable, so the next window is probed
     * with a single chunk before it is sent. After each failed probe, the number of windows
     * skipped before the next probe doubles (1, 2, 4, …), so a dead service costs a call per doubling
     * rather than a window of calls each time. A probe that succeeds resumes normal re-embedding, so a
     * run of chunks the model always rejects stops only its own window rather than every window after it.
     */
    private fun reembedEveryChunk(onProgress: (done: Int, total: Int) -> Unit): ReembedOutcome {
        val ids = allChunkIds()
        onProgress(0, ids.size)
        return ids.chunked(REEMBED_BATCH_SIZE).asSequence()
            .fold(ReembedOutcome() to 0) { (outcome, walked), window ->
                reembedWindow(outcome, loadChunks(window).filter { it.text.isNotBlank() })
                    .also { onProgress(walked + window.size, ids.size) } to walked + window.size
            }.first
    }

    /** One window of the walk: embedded, or cleared and counted as missing when the model is failing. */
    private fun reembedWindow(outcome: ReembedOutcome, window: List<ChunkNode>): ReembedOutcome = when {
        window.isEmpty() -> outcome
        outcome.windowsToSkip > 0 -> {
            clearEmbeddings(window)
            outcome.skipped(window.map { it.id })
        }
        outcome.suspect -> {
            val failure = probe(window.first())
            if (failure == null) {
                outcome.plus(embedWindow(window))
            } else {
                clearEmbeddings(window)
                outcome.probeFailed(window.map { it.id }, failure)
            }
        }
        else -> outcome.plus(embedWindow(window))
    }

    /** Every chunk's id, read as [ChunkPlaceFragment]s: no text and no vector, so the list is small however large the store. */
    private fun allChunkIds(): List<String> =
        gom.loadAll<ChunkPlaceFragment>().map { it.id }

    private fun loadChunks(ids: List<String>): List<ChunkNode> =
        gom.loadAll<ChunkNode> { where { query.id inList ids } }

    /** Embed and save one window, clearing the vector of any chunk that could not be embedded. */
    private fun embedWindow(window: List<ChunkNode>): EmbeddingBatchResult {
        val result = EmbeddingBatchGenerator.embedInBatches(
            embeddingService,
            window.map { it.toCoreType() },
            chunkerConfig.embeddingBatchSize,
            logger,
        )
        val (embedded, failed) = window.partition { it.id in result.embeddings }
        gom.saveAll(embedded.map { it.copy(embedding = result.embeddings.getValue(it.id).toList()) })
        clearEmbeddings(failed)
        return result
    }

    /** One call with one chunk: null if the service embedded it, otherwise the failure. */
    private fun probe(chunk: ChunkNode): Throwable? =
        runCatching { embeddingService.embed(listOf(chunk.toCoreType().embeddableValue())) }
            .exceptionOrNull()
            ?.also { logger.warn("reembedAll: probe with chunk {} failed: {}", chunk.id, it.message) }

    /**
     * Remove the stored vector from [nodes]. The default save skips null fields, so a null embedding
     * alone would leave the previous model's vector in place; [NullPolicy.CLEAR] writes the null. Safe
     * here because every node was loaded whole.
     */
    private fun clearEmbeddings(nodes: List<ChunkNode>) {
        if (nodes.isNotEmpty()) {
            gom.saveAll(nodes.map { it.copy(embedding = null) }, nullPolicy = NullPolicy.CLEAR)
        }
    }

    /** What [reembedEveryChunk] did, window by window, and whether the next window is probed or skipped. */
    private data class ReembedOutcome(
        val embedded: Int = 0,
        val missingChunkIds: List<String> = emptyList(),
        val cause: Throwable? = null,
        /** The last window embedded nothing, or its probe failed: probe before sending the next. */
        val suspect: Boolean = false,
        val windowsToSkip: Int = 0,
        /** Windows to skip after the next failed probe. */
        val skipAfterProbe: Int = 1,
    ) {
        fun plus(result: EmbeddingBatchResult) = copy(
            embedded = embedded + result.embeddings.size,
            missingChunkIds = missingChunkIds + result.missingChunkIds,
            cause = result.cause ?: cause,
            suspect = result.embeddings.isEmpty() && !result.isComplete,
            skipAfterProbe = if (result.embeddings.isEmpty()) skipAfterProbe else 1,
        )

        fun skipped(ids: List<String>) = copy(missingChunkIds = missingChunkIds + ids, windowsToSkip = windowsToSkip - 1)

        fun probeFailed(ids: List<String>, failure: Throwable) = copy(
            missingChunkIds = missingChunkIds + ids,
            cause = failure,
            windowsToSkip = skipAfterProbe,
            skipAfterProbe = skipAfterProbe * 2,
        )

        fun failure(): EmbeddingIncompleteException? =
            if (missingChunkIds.isEmpty()) null
            else EmbeddingIncompleteException(
                missingChunkIds = missingChunkIds,
                embeddedCount = embedded,
                cause = requireNotNull(cause) { "missing embeddings must carry the failure that caused them" },
            )
    }

    /** Load any persisted content element, dispatched to the right model by its labels. */
    override fun findById(id: String): ContentElement? =
        gom.load<ContentElementNode>(id)?.toCoreType()

    override fun <C : ContentElement> findAll(clazz: Class<C>): Iterable<C> {
        // Push the type filter into the query (instanceOf<NodeType>()) instead of loading every
        // ContentElement and filtering in heap. Falls back to a full scan for a core type with no
        // dedicated fragment; filterIsInstance stays as the correctness backstop either way.
        val nodes = when (clazz) {
            Chunk::class.java -> gom.loadAll<ContentElementNode> { where { query.instanceOf<ChunkNode>() } }
            LeafSection::class.java -> gom.loadAll<ContentElementNode> { where { query.instanceOf<LeafSectionNode>() } }
            ContainerSection::class.java -> gom.loadAll<ContentElementNode> { where { query.instanceOf<ContainerSectionNode>() } }
            MaterializedDocument::class.java, ContentRoot::class.java ->
                gom.loadAll<ContentElementNode> { where { query.instanceOf<DocumentNode>() } }
            else -> gom.loadAll<ContentElementNode>()
        }
        return nodes.map { it.toCoreType() }.filterIsInstance(clazz)
    }

    /**
     * Count chunks in the database, with [filter] pushed into the query. The interface default counts
     * [findAll], which for chunks is every stored vector and its text, filtered in memory — a document
     * list showing a chunk total held the whole corpus on the heap to produce one number. Other types
     * keep the default: there are few of them and they carry no vectors. A filter with no graph
     * translation also falls back to it, rather than failing a count that works today.
     */
    override fun <C : ContentElement> count(clazz: Class<C>, filter: PropertyFilter?): Int {
        if (clazz != Chunk::class.java) return super<EmbeddingAwareChunkingContentElementRepository>.count(clazz, filter)
        return try {
            gom.count(ChunkNode::class.java, ChunkNodeQueryDsl.INSTANCE) {
                where { filter?.let { query.applyFilter(it) } }
            }.toInt()
        } catch (e: UnsupportedOperationException) {
            logger.warn("count: no graph translation for {}; counting chunks in memory", filter, e)
            super<EmbeddingAwareChunkingContentElementRepository>.count(clazz, filter)
        }
    }

    /**
     * Whether a document root with this `uri` exists: a count over [ContentElementNode], filtered by `uri`
     * and narrowed to documents with `instanceOf<DocumentNode>()`.
     */
    override fun existsRootWithUri(uri: String): Boolean =
        gom.count<ContentElementNode> {
            where {
                query.uri eq uri
                query.instanceOf<DocumentNode>()
            }
        } > 0

    /**
     * Find the content root by `uri`: filter the polymorphic [ContentElementNode] by `uri` and narrow to
     * documents with `instanceOf<DocumentNode>()` (labels `Document` + `ContentRoot`).
     */
    override fun findContentRootByUri(uri: String): ContentRoot? =
        gom.loadAll<ContentElementNode> {
            where {
                query.uri eq uri
                query.instanceOf<DocumentNode>()
            }
        }.firstOrNull()?.toCoreType() as? ContentRoot

    /**
     * Delete the document at [uri] and everything under it: a cascade down [ContentTreeView]'s
     * `HAS_PARENT` children, done in the database. It follows the edges, not a property, so a
     * document stored before sections named their document is removed whole as well.
     */
    override fun deleteRootAndDescendants(uri: String): DocumentDeletionResult? {
        val root = findContentRootByUri(uri) ?: return null
        val deletedCount = gom.delete<ContentTreeView>(root.id, CascadeType.DELETE_ALL)
        logger.debug("Deleted {} content elements of '{}'", deletedCount, uri)
        return DocumentDeletionResult(rootUri = uri, deletedCount = deletedCount)
    }

    // Entity → chunk is a relationship traversal — kept as Cypher; entities are not modelled yet.
    override fun findChunksForEntity(entityId: String): List<Chunk> {
        val ids = persistenceManager.queryForScalars(
            purpose = "find-chunks-for-entity (gom store)",
            // A label can't be a bound parameter in Cypher — it's structural — so Drivine's `render`
            // `$(…)` inlines the trusted chunk label while the entity id stays a bound `$param`. The
            // result is a single scalar column (`chunk.id`), so it must be read with queryForScalars —
            // queryForRows can't map a scalar-column result.
            cypher = $$"""
                MATCH (e {id: $entityId})<-[:HAS_ENTITY]-(chunk:$($chunkLabel))
                RETURN chunk.id AS id
            """.trimIndent(),
            type = String::class.java,
            params = mapOf("entityId" to entityId),
            render = mapOf("chunkLabel" to properties.chunkNodeName),
        )
        return findAllChunksById(ids).toList()
    }

    // Node counts per fragment's labels (Chunk / Document / ContentElement).
    override fun info(): ContentElementRepositoryInfo = ContentElementRepositoryInfoImpl(
        chunkCount = gom.count<ChunkNode>().toInt(),
        documentCount = gom.count<DocumentNode>().toInt(),
        contentElementCount = gom.count<ContentElementNode>().toInt(),
    )

    override fun createInternalRelationships(root: NavigableDocument) {
        val sections = readingOrder(root, depth = 1)
        val chunks = gom.loadAll<ChunkPlaceFragment> { where { query.rootDocumentId eq root.id } }
        linkToParents(sections, chunks)
        linkConsecutiveChunks(chunks)
        stampReadingOrder(root, sections)
    }

    /**
     * `HAS_PARENT` from every section and chunk of this document to what it sits inside — the
     * hierarchy [ZoomOutView] and the recursive tree views walk. Only this document's elements, so
     * ingesting one document costs its own size and not the graph's.
     */
    private fun linkToParents(sections: List<PlacedSection>, chunks: List<ChunkPlaceFragment>) {
        val parentIds = sections.map { it.section.id to it.section.parentId } + chunks.map { it.id to it.parentId }
        val links = parentIds.mapNotNull { (id, parentId) ->
            parentId?.let { ParentLinkView(ContentElementFragment(id), ContentElementFragment(it)) }
        }
        gom.saveAll(links)
    }

    /**
     * `NEXT_CHUNK` between consecutive chunks of each container section, in `sequence_number`
     * order — the chain [ChunkExpandView] walks.
     */
    private fun linkConsecutiveChunks(chunks: List<ChunkPlaceFragment>) {
        val links = chunks
            .filter { it.containerSectionId != null && it.sequenceNumber != null }
            .groupBy { it.containerSectionId }
            .values
            .flatMap { inSection -> inSection.sortedBy { it.sequenceNumber }.zipWithNext(::NextChunkLinkView) }
        gom.saveAll(links)
    }

    /**
     * Number every section of [root] in reading order — a section before the sections inside it,
     * and those before its next sibling — and say which document it belongs to. Nothing else
     * records either: `HAS_PARENT` says what a section belongs to, not where it comes, and a
     * chunk's `sequence_number` restarts in each container.
     *
     * TODO(https://github.com/embabel/embabel-agent/issues/2110): this is every section's SECOND
     * save. `writeAndChunkDocument` is final in embabel-agent and has already called [save] on each
     * section, one at a time and with no position, before it hands the tree to
     * [createInternalRelationships]. The cost is one extra write per section at ingest; nothing is
     * wrong in what is stored. When embabel-agent lets a store save a document's structure in one
     * step, save the sections ONCE there, with their place, and delete this function along with
     * the per-section branches of [save] it makes redundant.
     */
    private fun stampReadingOrder(root: NavigableDocument, sections: List<PlacedSection>) {
        logger.debug("Stamping reading order on {} sections of '{}'", sections.size, root.uri)
        val placed = sections.mapIndexedNotNull { index, (section, depth) ->
            when (section) {
                is LeafSection ->
                    LeafSectionNode.from(section).copy(rootDocumentId = root.id, ordinal = index.toLong(), depth = depth)
                is ContainerSection ->
                    ContainerSectionNode.from(section).copy(rootDocumentId = root.id, ordinal = index.toLong(), depth = depth)
                else -> null.also { logger.warn("No model for section {} ({}); it gets no place", section.id, section::class.simpleName) }
            }
        }
        gom.saveAll(placed.filterIsInstance<LeafSectionNode>())
        gom.saveAll(placed.filterIsInstance<ContainerSectionNode>())
    }

    private data class PlacedSection(val section: NavigableSection, val depth: Long)

    // NavigableContainerSection.descendants() yields a container's children before any grandchild,
    // which is level order, not the order the document reads in.
    private fun readingOrder(section: NavigableSection, depth: Long): List<PlacedSection> =
        section.children.flatMap { listOf(PlacedSection(it, depth)) + readingOrder(it, depth + 1) }

    override fun outline(uri: String, skip: Int, limit: Int?): List<DocumentSection> {
        val root = findContentRootByUri(uri) ?: return emptyList()
        val headings = gom.loadAll<SectionHeadingNode> {
            where {
                query.rootDocumentId eq root.id
                query.ordinal.isNotNull()
            }
            // The document first, though the `where` already fixes it: the index is the pair, and
            // an ordering is only read off an index that covers exactly what it orders by.
            orderBy {
                query.rootDocumentId.asc()
                query.ordinal.asc()
            }
            if (skip > 0) skip(skip)
            limit?.let { limit(it) }
        }
        return headings.map {
            DocumentSection(
                id = it.id,
                title = it.title,
                parentId = it.parentId,
                depth = it.depth?.toInt(),
                leaf = it is LeafSectionHeading,
                ordinal = it.ordinal,
            )
        }
    }

    override fun chunksOf(sectionId: String): List<Chunk> {
        val cutFromIt = gom.loadAll<ChunkPlaceFragment> { where { query.leafSectionId eq sectionId } }
        // Chunks of several short sections together name no leaf, only the section that holds them.
        val sharedInIt = gom.loadAll<ChunkPlaceFragment> {
            where {
                query.containerSectionId eq sectionId
                query.leafSectionId.isNull()
            }
        }
        // Found by where they sit, which is light; then loaded whole, in the order they were cut.
        val inOrder = (cutFromIt + sharedInIt).sortedBy { it.sequenceNumber ?: 0L }.map { it.id }
        val chunks = findAllChunksById(inOrder).associateBy { it.id }
        return inOrder.mapNotNull(chunks::get)
    }

    // ----- ResultExpander: context expansion via edge traversal -----

    override fun expandResult(id: String, method: ResultExpander.Method, elementsToAdd: Int): List<ContentElement> =
        when (method) {
            ResultExpander.Method.ZOOM_OUT -> zoomOut(id)
            ResultExpander.Method.SEQUENCE -> expandBySequence(id, elementsToAdd)
        }

    /**
     * Follow `HAS_PARENT` one hop to the typed parent — the [ZoomOutView] traversal.
     *
     * An element whose `parentId` names a real parent but which has no `HAS_PARENT` edge expands to
     * nothing. That is not "no parent", it is an element that never went through
     * [createInternalRelationships] — so say which, rather than return an empty list that reads like
     * a root (embabel/embabel-agent-rag-graph#35).
     */
    private fun zoomOut(id: String): List<ContentElement> {
        val view = gom.loadAll<ZoomOutView> { where { element.id eq id } }.firstOrNull()
        if (view == null) {
            logger.warn("zoomOut: no content element with id='{}'", id)
            return emptyList()
        }
        val parent = view.parent
        if (parent == null) {
            logger.debug(
                "zoomOut: element id='{}' has no HAS_PARENT edge — either it is a root, or its edges " +
                    "were never written (createInternalRelationships)",
                id,
            )
            return emptyList()
        }
        return listOf(parent.toCoreType())
    }

    /**
     * Walk the `NEXT_CHUNK` chain ±[elementsToAdd] from the anchor (both directions) and return the
     * window including the anchor, ordered by sequence — the [ChunkExpandView] traversal, bounded to
     * [elementsToAdd] via `depth(...)`.
     */
    private fun expandBySequence(id: String, elementsToAdd: Int): List<ContentElement> {
        val view = gom.loadAll<ChunkExpandView> {
            depth("following", elementsToAdd)
            depth("preceding", elementsToAdd)
            where { chunk.id eq id }
        }.firstOrNull()
        if (view == null) {
            logger.warn("expandBySequence: no chunk with id='{}'", id)
            return emptyList()
        }
        if (view.preceding.isEmpty() && view.following.isEmpty()) {
            // The anchor alone. Legitimate for a one-chunk section; otherwise the NEXT_CHUNK chain was
            // never written, and a silent single-element window is indistinguishable from one.
            logger.debug(
                "expandBySequence: chunk id='{}' has no NEXT_CHUNK neighbours — a single-chunk section, " +
                    "or edges that were never written (createInternalRelationships)",
                id,
            )
        }
        return (view.preceding + view.chunk + view.following)
            .sortedBy { it.sequenceNumber ?: 0L }
            .map { it.toCoreType() }
    }

    override fun commit() {}
}
