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
import com.embabel.agent.rag.graph.model.ChunkExpandView
import com.embabel.agent.rag.graph.model.ChunkNode
import com.embabel.agent.rag.graph.model.ContainerSectionNode
import com.embabel.agent.rag.graph.model.ContentElementNode
import com.embabel.agent.rag.graph.model.ContentElementRepositoryInfoImpl
import com.embabel.agent.rag.graph.model.DocumentNode
import com.embabel.agent.rag.graph.model.LeafSectionNode
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
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.support.FunctionRagFacet
import com.embabel.agent.rag.service.support.RagFacet
import com.embabel.agent.rag.service.support.RagFacetResults
import com.embabel.agent.rag.store.ContentElementRepositoryInfo
import com.embabel.agent.rag.store.DocumentDeletionResult
import com.embabel.agent.rag.store.EmbeddingAwareChunkingContentElementRepository
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.count
import org.drivine.manager.load
import org.drivine.manager.loadAll
import org.drivine.manager.loadMatching
import org.drivine.manager.loadNearest
import org.drivine.query.dsl.instanceOf
import org.drivine.query.dsl.query
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec

/** Window size for [GraphObjectManagerStore.reembedChunks] — bounds heap and per-request embedding size. */
private const val REEMBED_BATCH_SIZE = 256

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
 * Graph mutation (relationship creation, [deleteRootAndDescendants]) and multi-hop traversal
 * ([findChunksForEntity]) are expressed in Cypher through the [queryForRows] / [queryForInt] /
 * [executeCypher] helpers on [PersistenceManager].
 */
class GraphObjectManagerStore(
    private val gom: GraphObjectManager,
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
    override val luceneSyntaxNotes = "Full support"
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
    }

    /**
     * The chunk vector / full-text index specs. The index *name* is left unset so Drivine derives its
     * convention (`{label}_{property}_vector` / `{label}_{property}_fulltext`) — the same name
     * `gom.loadNearest` / `gom.loadMatching` resolve — so provisioning and search agree with no name
     * literal to keep in sync.
     */
    private val chunkVectorIndex = VectorIndexSpec(
        properties.chunkNodeName, "embedding", embeddingService.dimensions, SimilarityFunction.COSINE,
    )
    private val chunkFullTextIndex = FullTextIndexSpec(properties.chunkNodeName, listOf("text"))

    private val provisioner = GraphProvisioner(persistenceManager)

    override fun provision() {
        logger.info("Provisioning (GraphObjectManager store) for '{}' (dim={})", properties.name, embeddingService.dimensions)
        provisioner.ensureSchema(
            vectorIndexes = listOf(chunkVectorIndex),
            fullTextIndexes = listOf(chunkFullTextIndex),
            constraints = listOf(UniquenessConstraintSpec(properties.entityNodeName, "id")),
        )
        // Transitional: loudly flag chunks whose free-form metadata is in the legacy flat layout (which
        // this store's metadata filters can't see). Remove with LegacyChunkMetadataCheck once migrated.
        provisioner.warnOnLegacyChunkMetadata(properties.chunkNodeName, ChunkNode.KNOWN_FLAT_PROPERTIES)
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

    /** Metadata-filtered vector search: `loadNearest` plus the [applyFilter] `where { }`. */
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
        return gom.loadMatching(
            ChunkNode::class.java, ChunkNodeQueryDsl.INSTANCE,
            request.query, request.topK, request.similarityThreshold,
        ) {
            where { query.applyFilters(metadataFilter, entityFilter) }
        }.map { SimilarityResult.create(it.value.toCoreType(), it.score) }.asResultsOf()
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
     */
    private fun chunkFullTextSearch(query: String, topK: Int, threshold: Double): List<SimilarityResult<out Chunk>> =
        if (query.isBlank()) emptyList()
        else gom.loadMatching<ChunkNode>(query, topK, threshold)
            .map { SimilarityResult.create(it.value.toCoreType(), it.score) }

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
                    chunkFullTextSearch(ragRequest.query, ragRequest.topK, ragRequest.similarityThreshold)
            }.getOrElse { e ->
                logger.error("Error during gom-store facet search for '{}'", ragRequest.query, e)
                emptyList()
            }
        }
        val merged = results.distinctBy { it.match.id }.sortedByDescending { it.score }.take(ragRequest.topK)
        return RagFacetResults(facetName = name, results = merged)
    }

    override fun reembedAll(): ReembedReport {
        logger.info("reembedAll (gom store) start. model={} dim={}", embeddingService.name, embeddingService.dimensions)
        persistenceManager.indexes.drop(chunkVectorIndex)
        val chunks = reembedChunks()
        provision()
        logger.info("reembedAll (gom store) done. chunks={}", chunks)
        return ReembedReport(chunks = chunks, entities = 0)
    }

    /**
     * Re-embed every persisted chunk: load the [ChunkNode]s, recompute embeddings from their text, and
     * save them back through the object manager (Drivine rewrites the engine-native vector).
     */
    private fun reembedChunks(): Int {
        val chunks = gom.loadAll<ChunkNode>().filter { it.text.isNotBlank() }
        if (chunks.isEmpty()) return 0
        // Re-embed and save one window at a time so we never hold a second full copy of every chunk, nor
        // send one oversized embedding request — each batch is embedded, saved, and released.
        chunks.chunked(REEMBED_BATCH_SIZE).forEach { batch ->
            val vectors = embeddingService.embed(batch.map { it.text })
            gom.saveAll(batch.mapIndexed { i, node -> node.copy(embedding = vectors[i].toList()) })
        }
        return chunks.size
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

    // Root deletion is a HAS_PARENT cascade, expressed in Cypher.
    override fun deleteRootAndDescendants(uri: String): DocumentDeletionResult? {
        val deletedCount = persistenceManager.queryForInt(
            purpose = "Delete root and descendants (gom store)",
            cypher = $$"""
            MATCH (root:ContentElement {uri: $uri})
            WHERE 'Document' IN labels(root) OR 'ContentRoot' IN labels(root)
            OPTIONAL MATCH (root)<-[:HAS_PARENT*0..]-(descendant:ContentElement)
            WITH collect(DISTINCT root) + collect(DISTINCT descendant) AS nodesToDelete
            UNWIND nodesToDelete AS node
            WITH DISTINCT node
            DETACH DELETE node
            RETURN count(*) AS deletedCount
            """.trimIndent(),
            params = mapOf("uri" to uri),
        )
        if (deletedCount == 0) return null
        return DocumentDeletionResult(rootUri = uri, deletedCount = deletedCount)
    }

    // Entity → chunk is a relationship traversal — kept as Cypher; entities are not modelled yet.
    override fun findChunksForEntity(entityId: String): List<Chunk> {
        val ids = persistenceManager.queryForRows(
            purpose = "find-chunks-for-entity (gom store)",
            // A label can't be a bound parameter in Cypher — it's structural — so Drivine's `render`
            // `$(…)` inlines the trusted chunk label while the entity id stays a bound `$param`.
            cypher = $$"""
                MATCH (e {id: $entityId})<-[:HAS_ENTITY]-(chunk:$($chunkLabel))
                RETURN chunk.id AS id
            """.trimIndent(),
            params = mapOf("entityId" to entityId),
            render = mapOf("chunkLabel" to properties.chunkNodeName),
        ).mapNotNull { it["id"] as? String }
        return findAllChunksById(ids).toList()
    }

    // Node counts per fragment's labels (Chunk / Document / ContentElement).
    override fun info(): ContentElementRepositoryInfo = ContentElementRepositoryInfoImpl(
        chunkCount = gom.count<ChunkNode>().toInt(),
        documentCount = gom.count<DocumentNode>().toInt(),
        contentElementCount = gom.count<ContentElementNode>().toInt(),
    )

    override fun createInternalRelationships(root: NavigableDocument) {
        // HAS_PARENT edges — the content hierarchy the recursive ContentTreeView walks.
        provisioner.createHasParentEdges()
        // NEXT_CHUNK edges between consecutive chunks within each container section (ordered by
        // sequence_number) — the chain ChunkExpandView walks for sequence expansion. Scoped to the root
        // being ingested (`root_document_id`) so ingesting N documents is O(chunks-per-doc), not a whole-
        // graph rescan per document.
        persistenceManager.executeCypher(
            purpose = "Create NEXT_CHUNK relationships (gom store)",
            cypher = $$"""
                MATCH (c:$($chunkLabel))
                WHERE c.root_document_id = $rootId
                  AND c.container_section_id IS NOT NULL AND c.sequence_number IS NOT NULL
                WITH c.container_section_id AS sec, c ORDER BY c.sequence_number
                WITH sec, collect(c) AS chunks
                UNWIND range(0, size(chunks) - 2) AS i
                WITH chunks[i] AS a, chunks[i + 1] AS b
                MERGE (a)-[:NEXT_CHUNK]->(b)
            """.trimIndent(),
            params = mapOf("rootId" to root.id),
            render = mapOf("chunkLabel" to properties.chunkNodeName),
        )
    }

    // ----- ResultExpander: context expansion via edge traversal -----

    override fun expandResult(id: String, method: ResultExpander.Method, elementsToAdd: Int): List<ContentElement> =
        when (method) {
            ResultExpander.Method.ZOOM_OUT -> zoomOut(id)
            ResultExpander.Method.SEQUENCE -> expandBySequence(id, elementsToAdd)
        }

    /** Follow `HAS_PARENT` one hop to the typed parent — the [ZoomOutView] traversal. */
    private fun zoomOut(id: String): List<ContentElement> =
        listOfNotNull(
            gom.loadAll<ZoomOutView> { where { element.id eq id } }.firstOrNull()?.parent?.toCoreType(),
        )

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
        }.firstOrNull() ?: return emptyList()
        return (view.preceding + view.chunk + view.following)
            .sortedBy { it.sequenceNumber ?: 0L }
            .map { it.toCoreType() }
    }

    override fun commit() {}
}
