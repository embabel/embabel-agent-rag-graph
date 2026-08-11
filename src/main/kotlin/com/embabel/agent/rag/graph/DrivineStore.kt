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

import com.embabel.agent.api.common.Embedding
import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.CONTAINER_SECTION_ID
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.SEQUENCE_NUMBER
import com.embabel.agent.rag.ingestion.RetrievableEnhancer
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.ContentRoot
import com.embabel.agent.rag.model.HierarchicalContentElement
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.graph.fulltext.FULL_TEXT_SIMILARITY_FLOOR
import com.embabel.agent.rag.graph.mappers.DefaultContentElementRowMapper
import com.embabel.agent.rag.graph.fulltext.searchRequiringIdentifiers
import com.embabel.agent.rag.graph.model.ContentElementRepositoryInfoImpl
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.agent.rag.service.EntitySearch
import com.embabel.agent.rag.service.FilteringTextSearch
import com.embabel.agent.rag.service.FilteringVectorSearch
import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.ResultExpander
import com.embabel.agent.rag.service.support.FunctionRagFacet
import com.embabel.agent.rag.service.support.RagFacet
import com.embabel.agent.rag.service.support.RagFacetProvider
import com.embabel.agent.rag.service.support.RagFacetResults
import com.embabel.agent.rag.store.EmbeddingAwareChunkingContentElementRepository
import com.embabel.agent.rag.store.ContentElementRepositoryInfo
import com.embabel.agent.rag.store.DocumentDeletionResult
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityCutoff
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.agent.rag.graph.dialect.Neo4jRagDialect
import com.embabel.agent.rag.graph.dialect.RagDialect
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.RowMapper
import org.drivine.query.QuerySpecification
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * The original graph-RAG store: persistence, search, and provisioning implemented with hand-written
 * Cypher (via Drivine's [PersistenceManager] and the per-engine [RagDialect]).
 *
 * **Deprecated — scheduled for removal.** Superseded by [GraphObjectManagerStore], which fulfils the
 * same [GraphRagStore] contract but drives persistence and retrieval through Drivine's typed
 * `GraphObjectManager` and `@NodeFragment` models rather than string-built queries. This class is kept
 * only as the A/B fallback while the replacement beds in, and will be removed once that is complete.
 */
@Deprecated(
    "The original hand-rolled-Cypher store, superseded by GraphObjectManagerStore. Kept as the A/B " +
        "comparison fallback; scheduled for removal.",
)
open class DrivineStore @JvmOverloads constructor(
    val persistenceManager: PersistenceManager,
    val properties: GraphRagServiceProperties,
    chunkerConfig: ContentChunker.Config,
    chunkTransformer: ChunkTransformer,
    embeddingService: EmbeddingService,
    platformTransactionManager: PlatformTransactionManager,
    private val cypherSearch: CypherSearch,
    private val dialect: RagDialect = Neo4jRagDialect(),
    override val enhancers: List<RetrievableEnhancer> = emptyList(),
    private val contentElementMapper: RowMapper<ContentElement> = DefaultContentElementRowMapper(),
    private val chunkFilterConverter: CypherFilterConverter = CypherFilterConverter(nodeAlias = "chunk"),
    private val queryResolver: LogicalQueryResolver = FixedLocationLogicalQueryResolver(),
) : EmbeddingAwareChunkingContentElementRepository(
    chunkerConfig = chunkerConfig,
    chunkTransformer = chunkTransformer,
    embeddingService = embeddingService,
), GraphRagStore {

    override val name get() = properties.name

    // Kept in step with GraphObjectManagerStore: this store is the A/B fallback, so retrieval must not
    // behave differently — including what the LLM is told about query syntax.
    override val luceneSyntaxNotes = """
        Full Lucene syntax: +term (required), -term (excluded), "exact phrase", term* (prefix),
        term~ (fuzzy). Prefer this tool over vector search for exact strings an embedding cannot
        represent — error codes, part numbers, identifiers, stack-trace tokens. Such tokens are
        required automatically, so you may pass the user's question as-is.
    """.trimIndent()

    override fun supportsType(type: String): Boolean {
        return type == Chunk::class.java.simpleName
    }

    /**
     * The schema this store manages: two vector indexes, two full-text indexes, one uniqueness
     * constraint. Index names are pinned to [GraphRagServiceProperties] because the search Cypher
     * binds them by name (`$vectorIndex` / `$fulltextIndex`) on Neo4j and Memgraph; on FalkorDB the
     * name is ignored and search addresses the index by label/property.
     *
     * Applied per-spec through Drivine's [org.drivine.schema.IndexManager] /
     * [org.drivine.schema.ConstraintManager], so this store only ever touches its own indexes — it
     * never collides with schema a host application manages on the same database, and it needs no
     * catalog-level version marker (a re-embed is handled explicitly by [reembedAll]).
     */
    private val vectorIndexSpecs: List<VectorIndexSpec>
        get() = listOf(
            VectorIndexSpec(
                properties.chunkNodeName, "embedding", embeddingService.dimensions,
                SimilarityFunction.COSINE, properties.contentElementIndex,
            ),
            VectorIndexSpec(
                properties.entityNodeName, "embedding", embeddingService.dimensions,
                SimilarityFunction.COSINE, properties.entityIndex,
            ),
        )

    // Named with the `{label}_{property}_fulltext` convention that GraphObjectManagerStore + Drivine's
    // `loadMatching` use, so both stores ensure the *same* (Chunk, text) index on a shared database —
    // Neo4j permits only one full-text index per property, so two different names would clobber.
    private val chunkFullTextIndexName = "${properties.chunkNodeName}_text_fulltext"

    private val fullTextIndexSpecs: List<FullTextIndexSpec>
        get() = listOf(
            FullTextIndexSpec(properties.chunkNodeName, listOf("text"), chunkFullTextIndexName),
            FullTextIndexSpec(
                properties.entityNodeName, listOf("name", "description"), properties.entityFullTextIndex,
            ),
        )

    private val uniquenessConstraintSpecs: List<UniquenessConstraintSpec>
        get() = listOf(
            UniquenessConstraintSpec(properties.entityNodeName, "id"),
        )

    private val provisioner = GraphProvisioner(persistenceManager)

    override fun provision() {
        logger.info("Provisioning schema for '{}' (dim={})", properties.name, embeddingService.dimensions)
        // ensureSchema applies each constraint through Drivine's ConstraintManager, which creates
        // FalkorDB's required backing index automatically — the old dialect could only warn-and-skip,
        // so FalkorDB uniqueness is now actually enforced.
        provisioner.ensureSchema(vectorIndexSpecs, fullTextIndexSpecs, uniquenessConstraintSpecs)
        logger.info("Provisioning complete")
    }

    override fun commit() {
        // TODO may need to do this?
    }

    override fun createInternalRelationships(root: NavigableDocument) {
        provisioner.createHasParentEdges()

        // Get all leaf section IDs from the document tree
        val leafSectionIds = root.descendants()
            .filterIsInstance<HierarchicalContentElement>()
            .map { it.id }
            .toSet() + root.id

        // Query chunks from database that belong to this document's sections
        val chunks = leafSectionIds.flatMap { parentId ->
            findChunksByParentId(parentId)
        }

        val chunksByParent = chunks.groupBy { it.parentId }

        // Create relationships for each parent group
        for ((parentId, parentChunks) in chunksByParent) {
            if (parentChunks.isEmpty()) continue

            // Create PART_OF relationships for each chunk
            for (chunk in parentChunks) {
                cypherSearch.query(
                    purpose = "Create PART_OF for chunk ${chunk.id}",
                    query = "create_part_of",
                    params = mapOf(
                        "chunkId" to chunk.id,
                        "parentId" to parentId
                    )
                )
            }

            // Create FIRST_CHUNK relationship
            val firstChunk = parentChunks.first()
            cypherSearch.query(
                purpose = "Create FIRST_CHUNK for parent $parentId",
                query = "create_first_chunk",
                params = mapOf(
                    "parentId" to parentId,
                    "firstChunkId" to firstChunk.id
                )
            )

            // Create NEXT_CHUNK relationships between consecutive chunks
            for (i in 0 until parentChunks.size - 1) {
                cypherSearch.query(
                    purpose = "Create NEXT_CHUNK ${parentChunks[i].id} -> ${parentChunks[i + 1].id}",
                    query = "create_next_chunk",
                    params = mapOf(
                        "currentChunkId" to parentChunks[i].id,
                        "nextChunkId" to parentChunks[i + 1].id
                    )
                )
            }
        }

        logger.info(
            "Created content hierarchy relationships for {} chunks across {} parents",
            chunks.size,
            chunksByParent.size
        )
    }

    override fun deleteRootAndDescendants(uri: String): DocumentDeletionResult? {
        logger.info("Deleting document with URI: {}", uri)

        try {
            val cypher = """
                MATCH (root:ContentElement {uri: ${'$'}uri})
                WHERE 'Document' IN labels(root) OR 'ContentRoot' IN labels(root)
                OPTIONAL MATCH (root)<-[:HAS_PARENT*0..]-(descendant:ContentElement)
                WITH collect(DISTINCT root) + collect(DISTINCT descendant) AS nodesToDelete
                UNWIND nodesToDelete AS node
                WITH DISTINCT node
                DETACH DELETE node
                RETURN count(*) AS deletedCount
            """.trimIndent()

            val deletedCount = cypherSearch.queryForInt(cypher, mapOf("uri" to uri))

            if (deletedCount == 0) {
                logger.warn("No document found with URI: {}", uri)
                return null
            }

            logger.info("Deleted {} elements for document with URI: {}", deletedCount, uri)
            return DocumentDeletionResult(
                rootUri = uri,
                deletedCount = deletedCount
            )
        } catch (e: Exception) {
            logger.error("Error deleting document with URI: {}", uri, e)
            throw e
        }
    }

    override fun existsRootWithUri(uri: String): Boolean {
        val statement =
            "MATCH(c) WHERE c.uri = \$uri AND ('Document' IN labels(c) OR 'ContentRoot' IN labels(c)) RETURN COUNT(c) AS count"
        val parameters = mapOf("uri" to uri)
        return try {
            val count = cypherSearch.queryForInt(statement, parameters)
            count > 0
        } catch (e: Exception) {
            logger.error("Error checking existence of root with URI: {}", uri, e)
            false
        }
    }

    override fun findContentRootByUri(uri: String): ContentRoot? {
        logger.debug("Finding root document with URI: {}", uri)

        val statement = cypherContentElementQuery(
            " WHERE c.uri = \$uri AND ('Document' IN labels(c) OR 'ContentRoot' IN labels(c)) "
        )
        val parameters = mapOf("uri" to uri)
        try {
            val spec = QuerySpecification
                .withStatement(statement)
                .bind(parameters)
                .mapWith(contentElementMapper)

            val result = persistenceManager.maybeGetOne(spec)
            logger.debug("Root document with URI {} found: {}", uri, result != null)
            return result as? ContentRoot
        } catch (e: Exception) {
            logger.error("Error finding root with URI: {}", uri, e)
            return null
        }
    }

    override fun persistChunksWithEmbeddings(
        chunks: List<Chunk>,
        embeddings: Map<String, FloatArray>
    ) {
        chunks.forEach { chunk ->
            val embedding = requireNotNull(embeddings[chunk.id]) {
                "Missing embedding for chunk ${chunk.id}"
            }
            embedRetrievable(chunk, embedding)
        }
    }

    fun embeddingFor(text: String): Embedding =
        embeddingService.embed(text)

    /**
     * Persist an embedding onto the node for [retrievable].
     *
     * Convention for "what was embedded": every embedded node carries the
     * text it was embedded from, but we only write the explicit `_text`
     * property when [Retrievable.embeddableValue] differs from the node's
     * primary `text` field. For nodes whose `text` IS the embedded value
     * (today: `:Chunk`, `:Proposition`), we skip `_text` to avoid storing
     * a duplicate copy of the body. Reembed callers reconstruct the
     * embedded value via `coalesce(n._text, n.text)`.
     */
    private fun embedRetrievable(
        retrievable: Retrievable,
        embedding: Embedding,
    ) {
        try {
            val embeddedText = retrievable.embeddableValue()
            val cypher = dialect.storeEmbeddingCypher(retrievable.labels().joinToString(":"))
            val params = mapOf(
                "id" to retrievable.id,
                "embedding" to embedding,
                "embeddingModel" to embeddingService.name,
                "embeddedText" to embeddedText,
            )
            val result = cypherSearch.query(
                purpose = "embedding",
                query = cypher,
                params = params,
            )
            val nodesUpdated = result.numberOrZero<Int>("nodesUpdated")
            if (nodesUpdated == 0) {
                logger.warn(
                    "Expected to set embedding properties, but set 0. chunkId={}, cypher={}",
                    retrievable.id,
                    cypher,
                )
            }
        } catch (e: Exception) {
            logger.warn(
                "Unable to generate embedding for retrievable id='{}': {}",
                retrievable.id,
                e.message,
                e,
            )
        }
    }

    /**
     * Drop and recreate the vector indexes, re-embedding every Chunk and Entity
     * that already has an embedding+text using whatever EmbeddingService is
     * currently injected. Use this after swapping embedding models — vector
     * indexes are dimension-bound and `IF NOT EXISTS` won't reconcile a dim
     * change, so we drop + reprovision unconditionally.
     */
    override fun reembedAll(): ReembedReport {
        logger.info(
            "reembedAll start. model={} dim={}",
            embeddingService.name,
            embeddingService.dimensions,
        )
        vectorIndexSpecs.forEach { spec ->
            persistenceManager.indexes.drop(spec)
            logger.info("Dropped vector index {}", spec.effectiveName)
        }
        val chunks = reembedNodesWithLabel("Chunk")
        val entities = reembedNodesWithLabel(properties.entityNodeName)
        provision()
        logger.info("reembedAll done. chunks={} entities={}", chunks, entities)
        return ReembedReport(chunks = chunks, entities = entities)
    }

    private fun reembedNodesWithLabel(label: String): Int {
        var total = 0
        val pageSize = 200
        val currentModel = embeddingService.name
        while (true) {
            val rows = cypherSearch.query(
                purpose = "reembed-scan-$label",
                query = """
                    MATCH (n:$label)
                    WHERE n.embedding IS NOT NULL
                      AND coalesce(n._text, n.text) IS NOT NULL
                      AND coalesce(n.embeddingModel, '') <> ${'$'}model
                    RETURN n.id AS id, coalesce(n._text, n.text) AS text
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                params = mapOf("model" to currentModel, "limit" to pageSize),
            ).items()
            if (rows.isEmpty()) break

            val texts = rows.map { it["text"] as String }
            val vectors = embeddingService.embed(texts)
            var successInPage = 0
            rows.forEachIndexed { index, row ->
                val id = row["id"] as String
                val embeddedText = texts[index]
                try {
                    cypherSearch.query(
                        purpose = "reembed-write-$label",
                        query = """
                            MATCH (n:$label {id: ${'$'}id})
                            SET n.embedding = ${dialect.embeddingLiteral("embedding")},
                                n.embeddingModel = ${'$'}embeddingModel,
                                n.embeddedAt = timestamp()
                            FOREACH (x IN CASE WHEN coalesce(n.text, '') = ${'$'}embeddedText THEN [1] ELSE [] END |
                                REMOVE n._text
                            )
                            FOREACH (x IN CASE WHEN coalesce(n.text, '') <> ${'$'}embeddedText THEN [1] ELSE [] END |
                                SET n._text = ${'$'}embeddedText
                            )
                            """.trimIndent(),
                        params = mapOf(
                            "id" to id,
                            "embedding" to vectors[index],
                            "embeddingModel" to currentModel,
                            "embeddedText" to embeddedText,
                        ),
                    )
                    successInPage++
                } catch (e: Exception) {
                    logger.warn("Failed to re-embed {} id={}: {}", label, id, e.message)
                }
            }
            total += successInPage
            logger.info("Re-embedded {} {} nodes (running total {})", successInPage, label, total)
            if (successInPage == 0) {
                logger.warn(
                    "No progress on {} re-embed; {} candidates remain unprocessed. Aborting loop.",
                    label, rows.size,
                )
                break
            }
        }
        return total
    }

    override fun expandResult(
        id: String,
        method: ResultExpander.Method,
        elementsToAdd: Int
    ): List<ContentElement> {
        return when (method) {
            ResultExpander.Method.SEQUENCE -> expandBySequence(id, elementsToAdd)
            ResultExpander.Method.ZOOM_OUT -> expandByZoomOut(id)
        }
    }

    private fun expandBySequence(id: String, elementsToAdd: Int): List<ContentElement> {
        val contentElement = findById(id)
        if (contentElement == null) {
            logger.warn("Content element with id='{}' not found for expansion", id)
            return emptyList()
        }
        if (contentElement !is Chunk) {
            logger.warn("Content element id='{}' is not a Chunk; cannot expand by sequence", id)
            return emptyList()
        }

        val containerSectionId = contentElement.metadata[CONTAINER_SECTION_ID]?.toString()
        val sequenceNumber = contentElement.metadata[SEQUENCE_NUMBER]?.toString()?.toLongOrNull()

        if (containerSectionId == null || sequenceNumber == null) {
            logger.warn(
                "Chunk id='{}' missing required metadata for sequence expansion: containerSectionId={}, sequenceNumber={}",
                id, containerSectionId, sequenceNumber
            )
            return listOf(contentElement)
        }

        val minSeq = sequenceNumber - elementsToAdd
        val maxSeq = sequenceNumber + elementsToAdd

        val statement = queryResolver.resolve("expand_by_sequence")
            ?: error("Could not load expand_by_sequence.cypher")

        val spec = QuerySpecification
            .withStatement(statement)
            .bind(
                mapOf(
                    "containerSectionId" to containerSectionId,
                    "minSeq" to minSeq,
                    "maxSeq" to maxSeq,
                )
            )
            .mapWith(contentElementMapper)

        val results = persistenceManager.query(spec)
        logger.info(
            "Expanded chunk id='{}' (seq={}) to {} chunks in section '{}'",
            id, sequenceNumber, results.size, containerSectionId
        )
        return results
    }

    // TODO: `expand_zoom_out` resolves the parent by a `parentId` **property join**, which is a
    //  relational pattern in a graph store — the `HAS_PARENT` edge already models this and following it
    //  is a pointer hop rather than an id lookup. Kept for now; consider replacing with an edge
    //  traversal (a `@GraphRelationship(HAS_PARENT, OUTGOING)` view — one hop, or recursive for full
    //  ancestry) and dropping the `parentId`-join once the GOM store's ResultExpander lands.
    private fun expandByZoomOut(id: String): List<ContentElement> {
        val statement = queryResolver.resolve("expand_zoom_out")
            ?: error("Could not load expand_zoom_out.cypher")

        val spec = QuerySpecification
            .withStatement(statement)
            .bind(mapOf("id" to id))
            .mapWith(contentElementMapper)

        val results = persistenceManager.query(spec)
        if (results.isNotEmpty()) {
            val parent = results.first()
            logger.info("Zoomed out from id='{}' to parent id='{}' ({})", id, parent.id, parent.labels())
        } else {
            logger.warn("Zoom out found no parent for content element id='{}'", id)
        }
        return results
    }

    override fun info(): ContentElementRepositoryInfo {
        val statement = """
            CALL {
              MATCH (c:Chunk)
              RETURN count(c) AS chunkCount
            }
            CALL {
              MATCH (d:Document)
              RETURN count(d) AS documentCount
            }
            CALL {
              MATCH (e:ContentElement)
              RETURN count(e) AS contentElementCount
            }
            RETURN {
              chunkCount: chunkCount,
              documentCount: documentCount,
              contentElementCount: contentElementCount
            } AS stats
        """.trimIndent()

        val results = persistenceManager.getOne(
            QuerySpecification.withStatement(statement)
                .transform(ContentElementRepositoryInfoImpl::class.java)
        )
        return results
    }

    override fun findAllChunksById(chunkIds: List<String>): Iterable<Chunk> {
        val statement = cypherContentElementQuery(" WHERE c:Chunk AND c.id IN \$ids ")
        val spec = QuerySpecification
            .withStatement(statement)
            .bind(mapOf("ids" to chunkIds))
            .mapWith(contentElementMapper)
            .filterIsInstance<Chunk>()
        return persistenceManager.query(spec)
    }

    override fun <C : ContentElement> findAll(clazz: Class<C>): Iterable<C> {
        val label = clazz.simpleName
        val statement = cypherContentElementQuery(" WHERE c:$label ")
        val spec = QuerySpecification
            .withStatement(statement)
            .mapWith(contentElementMapper)
            .filterIsInstance(clazz)
        return persistenceManager.query(spec)
    }

    override fun <C : ContentElement> count(clazz: Class<C>, filter: PropertyFilter?): Int {
        val label = clazz.simpleName
        val filterConverter = CypherFilterConverter(nodeAlias = "c")
        val filterResult = filterConverter.convert(filter)
        val whereClause = if (filterResult.isEmpty()) {
            " WHERE c:$label "
        } else {
            " WHERE c:$label AND ${filterResult.whereClause} "
        }
        val statement = "MATCH (c:ContentElement) $whereClause RETURN count(c) AS count"
        return cypherSearch.queryForInt(statement, filterResult.parameters)
    }

    override fun findById(id: String): ContentElement? {
        val statement = cypherContentElementQuery(" WHERE c.id = \$id ")
        val spec = QuerySpecification
            .withStatement(statement)
            .bind(mapOf("id" to id))
            .mapWith(contentElementMapper)
        return persistenceManager.maybeGetOne(spec)
    }

    override fun findChunksForEntity(entityId: String): List<Chunk> {
        val statement = """
            MATCH (e:Entity {id: ${'$'}entityId})<-[:HAS_ENTITY]-(chunk:Chunk)
            RETURN properties(chunk)
            """.trimIndent()
        val spec = QuerySpecification
            .withStatement(statement)
            .bind(mapOf("entityId" to entityId))
            .transform(Map::class.java)
            .map({
                Chunk.create(
                    id = it["id"] as String,
                    text = it["text"] as String,
                    urtext = it["urtext"] as? String ?: it["text"] as String,
                    parentId = it["parentId"] as String,
                    metadata = emptyMap(), //TODO Can it ever be populated?
                )
            })
        return persistenceManager.query(spec)
    }

    private fun findChunksByParentId(parentId: String): List<Chunk> {
        val statement = """
            MATCH (chunk:Chunk {parentId: ${'$'}parentId})
            RETURN properties(chunk) AS props
            ORDER BY chunk.id
            """.trimIndent()
        val spec = QuerySpecification
            .withStatement(statement)
            .bind(mapOf("parentId" to parentId))
            .transform(Map::class.java)
            .map { props ->
                @Suppress("UNCHECKED_CAST")
                val p = props as Map<String, Any?>
                Chunk.create(
                    id = p["id"] as String,
                    text = p["text"] as String,
                    urtext = p["urtext"] as? String ?: p["text"] as String,
                    parentId = p["parentId"] as String,
                    metadata = emptyMap(),
                )
            }
        return persistenceManager.query(spec)
    }

    override fun save(element: ContentElement): ContentElement {
        val cypher = queryResolver.resolve("save_content_element")
            ?: error("Could not load save_content_element.cypher")
        val additionalLabels = element.labels().filter { it != "ContentElement" }
        val properties = element.propertiesToPersist().filterValues { it != null }
        val (setClause, bindParams) = flattenToSetClause("e", properties)
        persistenceManager.query(
            QuerySpecification
                .withStatement(cypher)
                .render(mapOf(
                    "additionalLabels" to additionalLabels,
                    "setClause" to setClause,
                ))
                .bind(mapOf("id" to element.id) + bindParams)
                .transform(Map::class.java)
        )
        return element
    }

    fun search(ragRequest: RagRequest): RagFacetResults<Retrievable> {
        val embedding = embeddingFor(ragRequest.query)
        val allResults = mutableListOf<SimilarityResult<out Retrievable>>()
        if (ragRequest.contentElementSearch.types.contains(Chunk::class.java)) {
            allResults += safelyExecuteInTransaction { chunkSearch(ragRequest, embedding) }
        } else {
            logger.info("No chunk search specified, skipping chunk search")
        }

        if (ragRequest.entitySearch != null) {
            allResults += safelyExecuteInTransaction { entitySearch(ragRequest, embedding) }
        } else {
            logger.info("No entity search specified, skipping entity search")
        }

        // TODO should reward multiple matches
        val mergedResults: List<SimilarityResult<out Retrievable>> = allResults
            .distinctBy { it.match.id }
            .sortedByDescending { it.score }
            .take(ragRequest.topK)
        return RagFacetResults(
            facetName = this.name,
            results = mergedResults,
        )
    }

    override fun facets(): List<RagFacet<out Retrievable>> {
        return listOf(
            FunctionRagFacet(
                name = "DrivineRagService",
                searchFunction = ::search,
            )
        )
    }

    private fun cypherContentElementQuery(whereClause: String): String =
        """
            MATCH (c:ContentElement)
            $whereClause
            RETURN
              {
                id: c.id,
                uri: c.uri,
                text: c.text,
                parentId: c.parentId,
                ingestionDate: c.ingestionTimestamp,
                labels: labels(c),
                properties: properties(c)
              } AS result
            """.trimIndent()

    private val readonlyTransactionTemplate = TransactionTemplate(platformTransactionManager).apply {
        isReadOnly = true
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
    }

    private fun safelyExecuteInTransaction(block: () -> List<SimilarityResult<out Retrievable>>): List<SimilarityResult<out Retrievable>> {
        return try {
            readonlyTransactionTemplate.execute { block() } as List<SimilarityResult<out Retrievable>>
        } catch (e: Exception) {
            logger.error("Error during RAG search transaction", e)
            emptyList()
        }
    }

    private fun chunkSearch(
        ragRequest: RagRequest,
        embedding: Embedding,
    ): List<SimilarityResult<out Chunk>> {
        val chunkSimilarityResults = chunkSimilaritySearch(ragRequest, embedding)
        val chunkFullTextResults = chunkFullTextSearch(ragRequest)
        return chunkSimilarityResults + chunkFullTextResults
    }

    override fun <T : Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>
    ): List<SimilarityResult<T>> {
        if (clazz != Chunk::class.java) {
            throw IllegalArgumentException("DrivineStore vectorSearch only supports Chunk class, got: $clazz")
        }
        @Suppress("UNCHECKED_CAST")
        return chunkSimilaritySearch(
            request,
            embeddingFor(request.query),
        ) as List<SimilarityResult<T>>
    }

    private fun chunkSimilaritySearch(
        request: TextSimilaritySearchRequest,
        embedding: Embedding,
    ): List<SimilarityResult<Chunk>> {
        val results = cypherSearch.chunkSimilaritySearch(
            "Chunk similarity search",
            query = dialect.chunkVectorSearchCypher(),
            params = commonParameters(request) + mapOf(
                "vectorIndex" to properties.contentElementIndex,
                "chunkLabel" to properties.chunkNodeName,
                "queryVector" to embedding,
            ),
            logger = logger,
        )
        logger.info("{} chunk similarity results for query '{}'", results.size, request.query)
        return results
    }

    // TODO remove .cypher files (e.g. chunk_vector_search.cypher) once dialect queries are proven out
    internal fun chunkFullTextSearch(
        request: TextSimilaritySearchRequest,
    ): List<SimilarityResult<out Chunk>> {
        val queryTemplate = dialect.chunkFullTextSearchCypher()
        if (queryTemplate == null) {
            logger.info("Fulltext search not supported by dialect '{}', skipping", dialect.name)
            return emptyList()
        }
        val results = cypherSearch.chunkFullTextSearch(
            purpose = "Chunk full text search",
            query = queryTemplate,
            params = commonParameters(request) + mapOf(
                "fulltextIndex" to chunkFullTextIndexName,
                "chunkLabel" to properties.chunkNodeName,
                "searchText" to "\"${request.query}\"",
            ),
            logger = logger,
        )
        logger.info("{} chunk full-text results for query '{}'", results.size, request.query)
        return results
    }

    override fun <T : Retrievable> textSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>
    ): List<SimilarityResult<T>> {
        val queryTemplate = dialect.chunkFullTextSearchCypher()
            ?: throw UnsupportedOperationException(
                "Fulltext search is not supported by the ${dialect.name} dialect"
            )
        val results = searchRequiringIdentifiers(request.query, properties.requireIdentifierTerms) { searchText ->
            cypherSearch.chunkFullTextSearch(
                purpose = "Chunk full text search",
                query = queryTemplate,
                params = commonParameters(request) + mapOf(
                    "fulltextIndex" to chunkFullTextIndexName,
                    "chunkLabel" to properties.chunkNodeName,
                    "searchText" to searchText,
                ),
                logger = logger,
            )
        }
        @Suppress("UNCHECKED_CAST")
        return results as List<SimilarityResult<T>>
    }

    override fun <T : Retrievable> vectorSearchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
        metadataFilter: PropertyFilter?,
        entityFilter: EntityFilter?,
    ): List<SimilarityResult<T>> {
        if (clazz != Chunk::class.java) {
            throw IllegalArgumentException("DrivineStore vectorSearchWithFilter only supports Chunk class, got: $clazz")
        }
        // In the graph, chunk metadata is stored as node properties, so both filters can use native Cypher
        val filterResult = combineFilters(metadataFilter, entityFilter)
        logger.info(
            "Performing vector search with filter: query='{}', topK={}, metadataFilter={}, propertyFilter={}",
            request.query, request.topK, metadataFilter, entityFilter
        )
        @Suppress("UNCHECKED_CAST")
        return chunkSimilaritySearchWithFilter(
            request,
            embeddingFor(request.query),
            filterResult,
        ) as List<SimilarityResult<T>>
    }

    override fun <T : Retrievable> textSearchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
        metadataFilter: PropertyFilter?,
        entityFilter: EntityFilter?,
    ): List<SimilarityResult<T>> {
        // In the graph, chunk metadata is stored as node properties, so both filters can use native Cypher
        val filterResult = combineFilters(metadataFilter, entityFilter)
        logger.info(
            "Performing text search with filter: query='{}', topK={}, metadataFilter={}, propertyFilter={}",
            request.query, request.topK, metadataFilter, entityFilter
        )
        val queryTemplate = dialect.chunkFullTextSearchCypher()
            ?: throw UnsupportedOperationException(
                "Fulltext search is not supported by the ${dialect.name} dialect"
            )
        val results = searchRequiringIdentifiers(request.query, properties.requireIdentifierTerms) { searchText ->
            cypherSearch.chunkFullTextSearchWithFilter(
                purpose = "Chunk full text search with filter",
                query = queryTemplate,
                params = commonParameters(request) + mapOf(
                    "fulltextIndex" to chunkFullTextIndexName,
                    "chunkLabel" to properties.chunkNodeName,
                    "searchText" to searchText,
                ),
                filterResult = filterResult,
                logger = logger,
            )
        }
        @Suppress("UNCHECKED_CAST")
        return results as List<SimilarityResult<T>>
    }

    /**
     * Combines metadata and property filters into a single CypherFilterResult.
     * In the graph, chunk metadata is stored as node properties (anything not in CORE_PROPERTIES),
     * so both filter types can be translated to native Cypher WHERE clauses.
     */
    private fun combineFilters(
        metadataFilter: PropertyFilter?,
        entityFilter: EntityFilter?,
    ): CypherFilterResult {
        val combinedFilter = when {
            metadataFilter != null && entityFilter != null ->
                PropertyFilter.And(listOf(metadataFilter, entityFilter))

            metadataFilter != null -> metadataFilter
            entityFilter != null -> entityFilter
            else -> null
        }
        return chunkFilterConverter.convert(combinedFilter)
    }

    private fun chunkSimilaritySearchWithFilter(
        request: TextSimilaritySearchRequest,
        embedding: Embedding,
        filterResult: CypherFilterResult,
    ): List<SimilarityResult<Chunk>> {
        val results = cypherSearch.chunkSimilaritySearchWithFilter(
            "Chunk similarity search with filter",
            query = dialect.chunkVectorSearchCypher(),
            params = commonParameters(request) + mapOf(
                "vectorIndex" to properties.contentElementIndex,
                "chunkLabel" to properties.chunkNodeName,
                "queryVector" to embedding,
            ),
            filterResult = filterResult,
            logger = logger,
        )
        logger.info("{} chunk similarity results for query '{}' with filter", results.size, request.query)
        return results
    }

    private fun entitySearch(
        ragRequest: RagRequest,
        embedding: FloatArray,
    ): List<SimilarityResult<out Retrievable>> {
        val allEntityResults = mutableListOf<SimilarityResult<out Retrievable>>()
        val labels = ragRequest.entitySearch?.labels ?: error("No entity search specified")
        val entityResults = entityVectorSearch(
            ragRequest,
            embedding,
            labels,
        )
        allEntityResults += entityResults
        logger.info("{} entity vector results for query '{}'", entityResults.size, ragRequest.query)
        val entityFullTextQuery = dialect.entityFullTextSearchCypher()
        val entityFullTextResults = if (entityFullTextQuery != null) {
            searchRequiringIdentifiers(ragRequest.query, properties.requireIdentifierTerms) { searchText ->
                cypherSearch.entityFullTextSearch(
                    purpose = "Entity full text search",
                    query = entityFullTextQuery,
                    params = commonParameters(ragRequest) + mapOf(
                        "fulltextIndex" to properties.entityFullTextIndex,
                        "entityNodeName" to properties.entityNodeName,
                        "searchText" to searchText,
                        "labels" to labels,
                        // Overrides the cosine-calibrated value commonParameters carried in. Entity
                        // full-text is BM25-scored, where that number means nothing — the entity
                        // vector search above keeps it. See FULL_TEXT_SIMILARITY_FLOOR.
                        "similarityThreshold" to FULL_TEXT_SIMILARITY_FLOOR,
                    ),
                    logger = logger,
                )
            }
        } else {
            logger.info("Entity fulltext search not supported by dialect '{}', skipping", dialect.name)
            emptyList()
        }
        logger.info("{} entity full-text results for query '{}'", entityFullTextResults.size, ragRequest.query)
        allEntityResults += entityFullTextResults

        if (ragRequest.entitySearch?.generateQueries == true) {
            val cypherResults =
                generateAndExecuteCypher(ragRequest, ragRequest.entitySearch!!).also { cypherResults ->
                    logger.info("{} Cypher results for query '{}'", cypherResults.size, ragRequest.query)
                }
            allEntityResults += cypherResults
        } else {
            logger.info("No query generation specified, skipping Cypher generation and execution")
        }
        logger.info("{} total entity results for query '{}'", entityFullTextResults.size, ragRequest.query)
        return allEntityResults
    }

    fun entityVectorSearch(
        request: SimilarityCutoff,
        embedding: FloatArray,
        labels: Set<String>,
    ): List<SimilarityResult<out NamedEntityData>> {
        return cypherSearch.entityDataSimilaritySearch(
            purpose = "Mapped entity search",
            query = dialect.entityVectorSearchCypher(),
            params = commonParameters(request) + mapOf(
                "index" to properties.entityIndex,
                "entityNodeName" to properties.entityNodeName,
                "queryVector" to embedding,
                "labels" to labels,
            ),
            logger,
        )
    }

    private fun generateAndExecuteCypher(
        request: RagRequest,
        entitySearch: EntitySearch,
    ): List<SimilarityResult<out Retrievable>> {
        TODO("Not yet implemented")
//        val schema = schemaResolver.getSchema(entitySearch)
//        if (schema == null) {
//            logger.info("No schema found for entity search {}, skipping Cypher execution", entitySearch)
//            return emptyList()
//        }
//
//        val cypherRagQueryGenerator = SchemaDrivenCypherRagQueryGenerator(
//            modelProvider,
//            schema,
//        )
//        val cypher = cypherRagQueryGenerator.generateQuery(request = request)
//        logger.info("Generated Cypher query: $cypher")
//
//        val cypherResults = readonlyTransactionTemplate.execute {
//            executeGeneratedCypher(cypher)
//        } ?: Result.failure(
//            IllegalStateException("Transaction failed or returned null while executing Cypher query: $cypher")
//        )
//        if (cypherResults.isSuccess) {
//            val results = cypherResults.getOrThrow()
//            if (results.isNotEmpty()) {
//                logger.info("Cypher query executed successfully, results: {}", results)
//                return results.map {
//                    // Most similar as we found them by a query
//                    SimpleSimilaritySearchResult(
//                        it,
//                        score = 1.0,
//                    )
//                }
//            }
//        }
//        return emptyList()
    }

//    /**
//     * Execute generate Cypher query, being sure to handle exceptions gracefully.
//     */
//    private fun executeGeneratedCypher(
//        query: CypherQuery,
//    ): Result<List<EntityData>> {
//        TODO("Not yet implemented")
//        try {
//            return Result.success(
//                ogmCypherSearch.queryForEntities(
//                    purpose = "cypherGeneratedQuery",
//                    query = query.query
//                )
//            )
//        } catch (e: Exception) {
//            logger.error("Error executing generated query: $query", e)
//            return Result.failure(e)
//        }
//    }

    private fun commonParameters(request: SimilarityCutoff) = mapOf(
        "topK" to request.topK,
        "similarityThreshold" to request.similarityThreshold,
    )
}

/**
 * Outcome of [DrivineStore.reembedAll].
 */
data class ReembedReport(
    val chunks: Int,
    val entities: Int,
)
