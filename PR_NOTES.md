# GraphObjectManager-powered ToolishRAG (Omg! Very, very load-bearing)

⚠️ Contains sharp insights - protective eyeware recommended.

**BLUF:** **What if we had the sexiest graph-rag in the business?**

Strangles the hand-rolled Cypher store (`DrivineStore`) with a new `GraphObjectManagerStore` which implements a new `GraphRagStore`. `GraphRagStore` is union interface representing the RAG surface — persistence, vector search, full-text search, metadata-filtered search, context expansion.

```kotlin
interface GraphRagStore :
    ChunkingContentElementRepository,
    CoreSearchOperations,
    FilteringVectorSearch,
    FilteringTextSearch,
    ResultExpander,
    RagFacetProvider {

    fun reembedAll(): ReembedReport
}
```

The new store leans much more heavily on Drivine's typed `GraphObjectManager` and `@NodeFragment` models instead of string-built queries, 100% on the retrieval side. On the write-side Cypher is only for *mutation* and multi-hop *traversal* - Drivine lets you mix and match abstractions as needed.

A shared characterization spec runs against both on every engine (Neo4j, FalkorDB, MemGraph), and the whole thing has been validated against the Guide chatbot on a freshly re-ingested graph.

## Summary

- **`GraphObjectManagerStore`** — the new store. Chunk-focused, backed by `gom.save`/`gom.saveAll`/`gom.load`/`gom.loadAll`/`gom.loadNearest`/`gom.loadMatching`/`gom.count` and the generated query DSL. Full source below.
- **`@NodeFragment` models** — `ChunkNode`, `LeafSectionNode`, `ContainerSectionNode`, `DocumentNode`, all under the sealed `ContentElementNode` base (writes subtype labels + the shared `ContentElement` label). Plus the `@GraphView` traversals: `ZoomOutView` (one `HAS_PARENT` hop) and `ChunkExpandView` (bounded `NEXT_CHUNK` walk).
- **`GraphProvisioner`** — Shared by both stores: schema application through Drivine's index/constraint managers, and `HAS_PARENT` edge creation. Each store still owns its own spec list.
- **`GraphRagStore`** — the union interface both stores satisfy, so the host app A/B-swaps them behind one type.
- **`CypherOps`** — `executeCypher` / `queryForRows` / `queryForInt` extensions on `PersistenceManager`, each purpose-logged, for the handful of places that stay in Cypher.
- **`ChunkPropertyFilters`** — translates an embabel `PropertyFilter` into a typed Drivine `where { }` via model-aware `predicateOn` key resolution.

The old `DrivineStore` is scheduled for deletion, but we can keep it around for A/B testing for some time.

## Retrieval

This is the load-bearing bit ( And the load being born is heavy AF, yet GoM doesn't even break a sweat).

- **Unfiltered vector search** → `gom.loadNearest<ChunkNode>(vector, topK, threshold)`. Resolves the chunk vector index by label/property; no hand-written Cypher.
- **Unfiltered full-text search** → `gom.loadMatching<ChunkNode>(query, topK, threshold)`. Scored, normalized, cross-engine.
- **Metadata/entity-filtered search** → the filtered `loadNearest`/`loadMatching` forms with a `where { query.applyFilters(metadataFilter, entityFilter) }`, where `applyFilters` compiles the embabel `PropertyFilter` tree into typed predicates.
- **Existence / lookup by uri** → the base-fragment DSL: `gom.count<ContentElementNode> { where { query.uri eq uri; query.instanceOf<DocumentNode>() } }`. Fully reified — the generated `INSTANCE` is auto-injected, no `::class.java`.
- **Context expansion** → edge traversal through `@GraphView`s: `ZoomOutView` for zoom-out, `ChunkExpandView` for sequence-window expansion (the walk is bounded at query time via `depth(...)`, so there's no over-read).

## What stays in Cypher, and why

Only two categories, both funnelled through the purpose-logged `PersistenceManager` helpers:

- **Mutation** — `HAS_PARENT` / `NEXT_CHUNK` edge creation and `deleteRootAndDescendants` (a `HAS_PARENT*0..` cascade). The DSL is a query tool, not a graph-rewrite tool.
- **Traversal** — `findChunksForEntity` (a `HAS_ENTITY` hop); entities aren't modelled by this store yet.

Where a label has to be injected into Cypher (labels can't be bound parameters — they're structural), we use Drivine's `render` `$(…)` to inline the *trusted* label while the actual data stays a bound `$param`.

```CypherSearch``` is also scheduled for deletion - this interface was an artifact older Neo4j OGM efforts and contains stubbed methods that remained as TODO until now. New RAG surface doesn't use it.

## Index Management

Replaces older manual index management with Drivine's index management features (and fixes some bugs).

Drivine's `ensure` matches an index by `(label, properties)` and *ignores the name*, so an index created by the other store under a different name satisfies `ensure` but search would miss it. `GraphProvisioner` decides: on a name mismatch it recreates under the convention name (node data + embeddings survive). A store switch becomes a one-time, self-healing index rebuild; once names match, subsequent runs are a no-op. This makes the next deploy migrate itself, once, successfully.

## Testing

A shared `AbstractRagSearchCharacterizationTest` runs the same behavioural spec against **both** stores across **Neo4j, FalkorDB, and Memgraph** — when the GOM store is green on every engine, it's a proven drop-in for the hand-rolled one. Plus focused tests for filtered search, expand traversals, content-tree views, and per-engine `ChunkNode` persistence (embeddings land in the engine-native vector type — `vecf32` on FalkorDB — automatically).

## Live validation

A/B-swapped into the Guide chatbot (embabel `1.0.0-SNAPSHOT`, Drivine `0.0.71`, docs bumped to `1.0.0`), made default via config. Nuked the DB, re-ingested clean: **23/23 URLs, 0 failures, 1,962 chunks, 3,162 content elements, embeddings present**. Sign-in works, retrieval works — `loadNearest` firing with real 384-dim query embeddings (ONNX local) against the freshly-provisioned index, zero errors during query testing. Confirmed: it RAGs like a boss. 🤙

_Since this run the branch has rebased onto embabel `1.5.0-SNAPSHOT` and Drivine `0.0.73` — the latter adds the `NullPolicy` (IGNORE-default) merge-patch save that fixes the embedding-clobber a re-save could otherwise cause. Unit + Neo4j integration tests are green on those versions; a live re-validation of the Guide A/B on `1.5.0` / `0.0.73` is still pending._

## `GraphObjectManagerStore` in full (check this out!)

```kotlin
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

    /** Chunk full-text search shared by [textSearch] and the [facets] search function. */
    private fun chunkFullTextSearch(query: String, topK: Int, threshold: Double): List<SimilarityResult<out Chunk>> =
        gom.loadMatching<ChunkNode>(query, topK, threshold)
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
```
