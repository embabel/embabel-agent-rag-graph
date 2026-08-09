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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.RelationshipDirection
import com.embabel.agent.rag.graph.mappers.NamedEntityDataRowMapper
import com.embabel.agent.rag.graph.mappers.NamedEntityDataSimilarityMapper
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.NativeFinder
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.agent.rag.service.support.ChainedNativeFinder
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.util.loggerFor
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.RowMapper
import org.drivine.query.QuerySpecification
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec

/**
 * Drivine-based implementation of [NamedEntityDataRepository] for Cypher graph databases.
 *
 * Provides CRUD operations and search capabilities for [NamedEntityData] entities
 * stored in the graph. Queries nodes directly without fetching relationships.
 *
 * Supports:
 * - Basic CRUD: [save], [findById], [findByLabel], [delete]
 * - Full-text search via fulltext indexes
 * - Vector similarity search via vector indexes
 * - Relationship creation between entities
 *
 * ## Indexing
 * This repository relies on vector and full-text indexes created on nodes with the
 * [GraphRagServiceProperties.entityNodeName] label (default: "Entity"). It provisions them
 * itself at construction time (see [verifyIndexes]) rather than depending on a store to have
 * done it: [DrivineStore] declares the same two indexes, but an application whose primary store
 * is [GraphObjectManagerStore] never constructs one and would leave every entity search failing
 * against an index nobody created.
 *
 * When saving entities, this repository automatically ensures that
 * [GraphRagServiceProperties.entityNodeName] is included in the node's labels,
 * regardless of whether it was present in [NamedEntityData.labels]. This guarantees
 * that all saved entities are properly indexed for search operations.
 *
 * @param persistenceManager Drivine persistence manager for graph database operations
 * @param properties Configuration properties including index names and entity node label
 * @param embeddingService Service for generating embeddings for vector search
 * @param queryResolver Resolver for loading Cypher queries from external files
 * @param namedEntityDataMapper Row mapper for converting query results to [NamedEntityData]
 * @param namedEntityDataSimilarityMapper Row mapper for similarity search results
 * @param verifyIndexes If true (default), ensures the required entity indexes exist at
 *        construction time, creating them if they are missing and warning if they cannot be
 *        created. Set false for narrowed copies and for tests with no live database.
 */
data class DrivineNamedEntityDataRepository @JvmOverloads constructor(
    private val persistenceManager: PersistenceManager,
    private val properties: GraphRagServiceProperties,
    override val dataDictionary: DataDictionary,
    private val embeddingService: EmbeddingService,
    private val graphObjectManager: GraphObjectManager? = null,
    override val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val queryResolver: LogicalQueryResolver = FixedLocationLogicalQueryResolver(),
    private val namedEntityDataMapper: RowMapper<NamedEntityData> = NamedEntityDataRowMapper(),
    private val namedEntityDataSimilarityMapper: RowMapper<SimilarityResult<NamedEntityData>> = NamedEntityDataSimilarityMapper(),
    private val filterConverter: CypherFilterConverter = CypherFilterConverter(nodeAlias = "n"),
    private val verifyIndexes: Boolean = true,
    /**
     * Optional Cypher WHERE clause to narrow all queries.
     * Use 'n' as the node alias. Set via [narrowedBy] or [scopedToContext].
     */
    private val narrowingClause: String? = null,
    private val additionalNativeFinder: NativeFinder = NativeFinder.NONE,
) : NamedEntityDataRepository {

    private val logger = loggerFor<DrivineNamedEntityDataRepository>()

    override val nativeFinder: NativeFinder = run {
        val drivine = graphObjectManager?.let { DrivineNativeFinder(it) }
        when {
            drivine != null && additionalNativeFinder !== NativeFinder.NONE ->
                ChainedNativeFinder(drivine, additionalNativeFinder)
            drivine != null -> drivine
            additionalNativeFinder !== NativeFinder.NONE -> additionalNativeFinder
            else -> NativeFinder.NONE
        }
    }

    /**
     * Return a copy with an additional [NativeFinder] chained after the built-in Drivine finder.
     * Use this to provide custom native lookups for types not handled by Drivine.
     */
    fun withAdditionalNativeFinder(additional: NativeFinder): DrivineNamedEntityDataRepository =
        copy(additionalNativeFinder = additional)

    init {
        if (verifyIndexes && narrowingClause == null) {
            // Only the root repository owns the schema; narrowed copies are views of it.
            ensureRequiredIndexes()
        }
    }

    /**
     * Create a narrowed view of this repository that adds an extra Cypher constraint to all queries.
     *
     * The constraint is injected into WHERE clauses of vector search, text search, and label queries.
     * Use the node alias 'n' to refer to the entity node.
     *
     * Example:
     * ```kotlin
     * // Only entities created after a certain date
     * val recent = repo.narrowedBy("n.createdAt > datetime('2024-01-01')")
     *
     * // Only entities with a specific property
     * val active = repo.narrowedBy("n.status = 'active'")
     * ```
     *
     * @param cypherConstraint A literal Cypher expression to add to WHERE clauses (use 'n' for entity node)
     * @return A narrowed repository that applies the constraint to all queries
     */
    fun narrowedBy(cypherConstraint: String): DrivineNamedEntityDataRepository = copy(
        narrowingClause = if (this.narrowingClause != null) {
            "(${this.narrowingClause}) AND ($cypherConstraint)"
        } else {
            cypherConstraint
        },
        verifyIndexes = false,
    )

    /**
     * Create a context-scoped view of this repository.
     *
     * Only returns entities that are mentioned in propositions belonging to the specified context.
     * Uses the relationship pattern: Entity <-[:MENTIONS]- Proposition
     *
     * Example:
     * ```kotlin
     * val userScoped = repo.inContext(user.contextId)
     * val contacts = userScoped.findByLabel("Contact") // Only contacts mentioned by this user
     * ```
     *
     * @param contextId The context ID to scope queries to
     * @return A narrowed repository that only returns entities mentioned in the context
     */
    override fun withContextScope(contextId: String): DrivineNamedEntityDataRepository =
        narrowedBy("EXISTS { (n)<-[:MENTIONS]-(:Proposition {contextId: '$contextId'}) }")

    /**
     * The entity schema this repository's searches bind by name: [entityVectorSearch] passes
     * `properties.entityIndex` to `db.index.vector.queryNodes` and the text search passes
     * `properties.entityFullTextIndex`. Nothing else in a deployment need create them — the only
     * other declaration is [DrivineStore]'s, and an application whose primary store is
     * [GraphObjectManagerStore] never constructs one (that store models no entities, so it
     * provisions no entity indexes — see [GraphProvisioner]). This repository is the component
     * that requires these indexes, so it is the component that creates them.
     */
    private val entityVectorIndex
        get() = VectorIndexSpec(
            properties.entityNodeName, "embedding", embeddingService.dimensions,
            SimilarityFunction.COSINE, properties.entityIndex,
        )

    private val entityFullTextIndex
        get() = FullTextIndexSpec(
            properties.entityNodeName, listOf("name", "description"), properties.entityFullTextIndex,
        )

    /**
     * Create the entity indexes if they are absent, idempotently — [GraphProvisioner.ensureSchema]
     * matches an existing index by `(label, properties)`, so a database already carrying them is
     * left alone.
     *
     * Never fatal. A read-only database, a driver that cannot answer yet, or a user without schema
     * privileges are all reasons an application can still boot and serve everything that is not
     * entity search; failing construction here would take the whole context down with it. The
     * warning names the two indexes so the operator can create them by hand.
     */
    private fun ensureRequiredIndexes() {
        try {
            GraphProvisioner(persistenceManager).ensureSchema(
                vectorIndexes = listOf(entityVectorIndex),
                fullTextIndexes = listOf(entityFullTextIndex),
                constraints = emptyList(),
            )
        } catch (e: Exception) {
            logger.warn(
                "Could not ensure entity indexes {} and {}: {}. Entity search will fail until they exist.",
                properties.entityIndex, properties.entityFullTextIndex, e.message,
            )
        }
    }

    override val luceneSyntaxNotes: String
        get() = "Full support"

    override fun createRelationship(
        a: RetrievableIdentifier,
        b: RetrievableIdentifier,
        relationship: RelationshipData
    ) {
        logger.debug(
            "Creating relationship: ({} {})-[:{}]->({} {})",
            a.type, a.id, relationship.name, b.type, b.id
        )
        val statement = resolveQuery("create_named_entity_relationship")
        val relProps = relationship.properties.filterValues { it != null }
        val (setClause, relBindParams) = flattenToSetClause("r", relProps)
        persistenceManager.execute(
            QuerySpecification
                .withStatement(statement)
                .render(mapOf(
                    "relType" to relationship.name,
                    "setClause" to setClause,
                ))
                .bind(mapOf(
                    "fromId" to a.id,
                    "fromType" to a.type,
                    "toId" to b.id,
                    "toType" to b.type,
                ) + relBindParams)
        )
        logger.debug("Created relationship: ({} {})-[:{}]->({} {})", a.type, a.id, relationship.name, b.type, b.id)
    }

    override fun mergeRelationship(
        a: RetrievableIdentifier,
        b: RetrievableIdentifier,
        relationship: RelationshipData
    ) {
        logger.debug(
            "Merging relationship: ({} {})-[:{}]->({} {})",
            a.type, a.id, relationship.name, b.type, b.id
        )
        val statement = resolveQuery("merge_named_entity_relationship")
        val relProps = relationship.properties.filterValues { it != null }
        val (setClause, relBindParams) = flattenToSetClause("r", relProps)
        persistenceManager.execute(
            QuerySpecification
                .withStatement(statement)
                .render(mapOf(
                    "relType" to relationship.name,
                    "setClause" to setClause,
                ))
                .bind(mapOf(
                    "fromId" to a.id,
                    "fromType" to a.type,
                    "toId" to b.id,
                    "toType" to b.type,
                ) + relBindParams)
        )
        logger.debug("Merged relationship: ({} {})-[:{}]->({} {})", a.type, a.id, relationship.name, b.type, b.id)
    }

    override fun delete(id: String): Boolean {
        logger.debug("Deleting entity with id: {}", id)
        val statement = """
            MATCH (e:${properties.entityNodeName} {id: ${'$'}id})
            DETACH DELETE e
            RETURN count(e) AS deletedCount
        """.trimIndent()

        val deletedCount = persistenceManager.getOne(
            QuerySpecification
                .withStatement(statement)
                .bind(mapOf("id" to id))
                .transform(Int::class.java)
        )
        val deleted = deletedCount > 0
        logger.debug("Delete entity id={}: deleted={}", id, deleted)
        return deleted
    }

    override fun findById(id: String): NamedEntityData? {
        logger.debug("Finding entity by id: {}", id)
        val statement = namedEntityQuery("n.id = \$id")
        return persistenceManager.maybeGetOne(
            QuerySpecification
                .withStatement(statement)
                .bind(mapOf("id" to id))
                .mapWith(namedEntityDataMapper)
        )
    }


    override fun findByLabel(label: String): List<NamedEntityData> {
        logger.debug("Finding entities by label: {}", label)
        val statement = namedEntityQuery("\$label IN labels(n)")
        return persistenceManager.query(
            QuerySpecification
                .withStatement(statement)
                .bind(mapOf("label" to label))
                .mapWith(namedEntityDataMapper)
        )
    }

    override fun find(label: String, filter: PropertyFilter?): List<NamedEntityData> {
        logger.debug("Finding entities by label: {} with filter: {}", label, filter)
        val filterResult = filterConverter.convert(filter)
        val whereClause = filterResult.appendTo("\$label IN labels(n)")
        val statement = namedEntityQuery(whereClause)
        return persistenceManager.query(
            QuerySpecification
                .withStatement(statement)
                .bind(mapOf("label" to label) + filterResult.parameters)
                .mapWith(namedEntityDataMapper)
        )
    }

    override fun find(
        labels: EntityFilter.HasAnyLabel,
        filter: PropertyFilter?
    ): List<NamedEntityData> {
        logger.debug("Finding entities by labels: {} with filter: {}", labels.labels, filter)
        val labelFilterResult = filterConverter.convert(labels)
        val propertyFilterResult = filterConverter.convert(filter)
        val whereClause = propertyFilterResult.appendTo(labelFilterResult.whereClause)
        val statement = namedEntityQuery(whereClause)
        return persistenceManager.query(
            QuerySpecification
                .withStatement(statement)
                .bind(labelFilterResult.parameters + propertyFilterResult.parameters)
                .mapWith(namedEntityDataMapper)
        )
    }

    override fun findRelated(
        source: RetrievableIdentifier,
        relationshipName: String,
        direction: RelationshipDirection,
    ): List<NamedEntityData> {
        logger.debug(
            "Finding related entities: source={}, relationship={}, direction={}",
            source,
            relationshipName,
            direction
        )
        val sourceLabel = source.type
        val statement = when (direction) {
            RelationshipDirection.OUTGOING -> """
                MATCH (source:$sourceLabel {id: ${'$'}entityId})-[:$relationshipName]->(target:${properties.entityNodeName})
                RETURN {
                    id: target.id,
                    name: COALESCE(target.name, ''),
                    description: COALESCE(target.description, ''),
                    labels: labels(target),
                    properties: properties(target)
                } AS result
            """.trimIndent()

            RelationshipDirection.INCOMING -> """
                MATCH (source:$sourceLabel {id: ${'$'}entityId})<-[:$relationshipName]-(target:${properties.entityNodeName})
                RETURN {
                    id: target.id,
                    name: COALESCE(target.name, ''),
                    description: COALESCE(target.description, ''),
                    labels: labels(target),
                    properties: properties(target)
                } AS result
            """.trimIndent()

            RelationshipDirection.BOTH -> """
                MATCH (source:$sourceLabel {id: ${'$'}entityId})-[:$relationshipName]-(target:${properties.entityNodeName})
                RETURN {
                    id: target.id,
                    name: COALESCE(target.name, ''),
                    description: COALESCE(target.description, ''),
                    labels: labels(target),
                    properties: properties(target)
                } AS result
            """.trimIndent()
        }

        val results = persistenceManager.query(
            QuerySpecification
                .withStatement(statement)
                .bind(mapOf("entityId" to source.id))
                .mapWith(namedEntityDataMapper)
        )
        logger.debug("Found {} related entities for source={}, relationship={}", results.size, source, relationshipName)
        return results
    }

    /**
     * NOTE: This method does not currently write `n.embedding`. The entity
     * vector index is provisioned by [DrivineStore.provision] but no write
     * path populates it yet. If you add embedding writes here, follow the
     * `_text` convention documented on `DrivineStore.embedRetrievable`:
     * persist `n._text = embeddableValue()` only when it differs from
     * `n.text`, so reembed can replay via `coalesce(n._text, n.text)`.
     */
    override fun save(entity: NamedEntityData): NamedEntityData {
        logger.debug("Saving entity: id={}, name={}", entity.id, entity.name)
        // Expand labels using the DataDictionary type hierarchy.
        // E.g., if entity has label "Musician" and Musician extends Person,
        // the expanded labels will include both "Musician" and "Person".
        // Filter to only include labels that are known domain types,
        // excluding infrastructure labels (e.g., "Retrievable", "Embeddable")
        // from framework interfaces in the type hierarchy.
        val knownDomainLabels = dataDictionary.jvmTypes.map { it.ownLabel }.toSet()
        val expandedLabels = entity.labels().flatMap { label ->
            val jvmType = dataDictionary.jvmTypes.find { it.ownLabel == label }
            if (jvmType != null) {
                jvmType.labels.filter { it in knownDomainLabels }
            } else {
                setOf(label)
            }
        }.toSet()
        val labels = (expandedLabels + properties.entityNodeName).distinct()
        logger.debug("Saved entity '{}' with labels: {} (expanded from {})", entity.name, labels, entity.labels())
        // MERGE only on base entity label + id to avoid creating duplicates when labels change.
        // Then SET additional labels separately.
        val additionalLabels = labels.filter { it != properties.entityNodeName }
        val setLabelsClause = if (additionalLabels.isNotEmpty()) {
            "SET e:" + additionalLabels.joinToString(":")
        } else {
            ""
        }
        val entityProps = entity.properties.filterValues { it != null }
        val (setPropsClause, propBindParams) = flattenToSetClause("e", entityProps)
        val statement = """
            MERGE (e:${properties.entityNodeName} {id: ${'$'}id})
            $setLabelsClause
            SET e.name = ${'$'}name,
                e.description = ${'$'}description,
                e.lastModifiedDate = timestamp()
            $setPropsClause
            RETURN {
                id: e.id,
                name: e.name,
                description: e.description,
                labels: labels(e),
                properties: properties(e)
            } AS result
        """.trimIndent()

        val params = mapOf(
            "id" to entity.id,
            "name" to entity.name,
            "description" to entity.description,
        ) + propBindParams

        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(statement)
                .bind(params)
                .mapWith(namedEntityDataMapper)
        )
    }


    override fun textSearch(
        request: TextSimilaritySearchRequest,
        metadataFilter: PropertyFilter?,
        entityFilter: EntityFilter?,
    ): List<SimilarityResult<NamedEntityData>> {
        logger.info(
            "Executing text search: query='{}', topK={}, metadataFilter={}, propertyFilter={}",
            request.query, request.topK, metadataFilter, entityFilter
        )
        val baseStatement = resolveQuery("named_entity_fulltext_search")
        // For NamedEntityData, both metadata and properties are node properties in the graph
        // so we can combine them into a single Cypher filter
        val combinedFilterResult = combineFilters(metadataFilter, entityFilter)
        val statement = injectFilterIntoQuery(baseStatement, combinedFilterResult, "n")

        val params = mapOf(
            "fulltextIndex" to properties.entityFullTextIndex,
            "searchText" to request.query,
            "similarityThreshold" to request.similarityThreshold,
            "topK" to request.topK,
            "entityNodeName" to properties.entityNodeName,
        ) + combinedFilterResult.parameters

        val results = persistenceManager.query(
            QuerySpecification
                .withStatement(statement)
                .bind(params)
                .mapWith(namedEntityDataSimilarityMapper)
        )
        logger.info("{} text search results for query '{}'", results.size, request.query)
        return results
    }

    override fun vectorSearch(
        request: TextSimilaritySearchRequest,
        metadataFilter: PropertyFilter?,
        entityFilter: EntityFilter?,
    ): List<SimilarityResult<NamedEntityData>> {
        val embedding = embeddingService.embed(request.query)
        logger.info(
            "Executing vector search: query='{}', topK={}, metadataFilter={}, propertyFilter={}",
            request.query, request.topK, metadataFilter, entityFilter
        )
        val baseStatement = resolveQuery("named_entity_vector_search")
        // For NamedEntityData, both metadata and properties are node properties in the graph
        // so we can combine them into a single Cypher filter
        val combinedFilterResult = combineFilters(metadataFilter, entityFilter)
        val statement = injectFilterIntoQuery(baseStatement, combinedFilterResult, "n")

        val params = mapOf(
            "vectorIndex" to properties.entityIndex,
            "queryVector" to embedding,
            "similarityThreshold" to request.similarityThreshold,
            "topK" to request.topK,
            "entityNodeName" to properties.entityNodeName,
        ) + combinedFilterResult.parameters

        val results = persistenceManager.query(
            QuerySpecification
                .withStatement(statement)
                .bind(params)
                .mapWith(namedEntityDataSimilarityMapper)
        )
        logger.info("{} vector search results for query '{}'", results.size, request.query)
        return results
    }

    /**
     * Combines metadata and property filters into a single CypherFilterResult.
     * For NamedEntityData in the graph, both are stored as node properties,
     * so both can be translated to native Cypher WHERE clauses.
     */
    private fun combineFilters(
        metadataFilter: PropertyFilter?,
        propertyFilter: PropertyFilter?,
    ): CypherFilterResult {
        val combinedFilter = when {
            metadataFilter != null && propertyFilter != null ->
                PropertyFilter.And(listOf(metadataFilter, propertyFilter))

            metadataFilter != null -> metadataFilter
            propertyFilter != null -> propertyFilter
            else -> null
        }
        return filterConverter.convert(combinedFilter)
    }

    private fun namedEntityQuery(whereClause: String): String {
        val fullWhereClause = applyNarrowing(whereClause)
        return """
        MATCH (n:${properties.entityNodeName})
        WHERE $fullWhereClause
        RETURN {
            id: n.id,
            name: COALESCE(n.name, ''),
            description: COALESCE(n.description, ''),
            labels: labels(n),
            properties: properties(n)
        } AS result
        """.trimIndent()
    }

    /**
     * Applies the narrowing clause to an existing WHERE clause.
     */
    private fun applyNarrowing(whereClause: String): String =
        if (narrowingClause != null) {
            "($whereClause) AND ($narrowingClause)"
        } else {
            whereClause
        }

    private fun resolveQuery(name: String): String =
        queryResolver.resolve(name) ?: error("Could not resolve query: $name")

    /**
     * Injects filter WHERE clause conditions into a Cypher query.
     *
     * This method finds the first WHERE clause in the query and appends the filter
     * conditions with AND. If the filter is empty, the query is returned unchanged.
     *
     * @param query The original Cypher query
     * @param filterResult The converted filter result
     * @param nodeAlias The node alias used in the query (for logging/debugging)
     * @return The modified query with filter conditions injected
     */
    private fun injectFilterIntoQuery(
        query: String,
        filterResult: CypherFilterResult,
        @Suppress("UNUSED_PARAMETER") nodeAlias: String,
    ): String {
        if (filterResult.isEmpty()) return query

        // Find WHERE clause and inject filter conditions
        // Pattern: find "WHERE " followed by conditions, then inject our filter with AND
        val wherePattern = Regex("(?i)(WHERE\\s+)", RegexOption.MULTILINE)
        val match = wherePattern.find(query)

        return if (match != null) {
            // Insert filter conditions after WHERE keyword with AND
            val insertPoint = match.range.last + 1
            val filterClause = "(${filterResult.whereClause}) AND "
            query.substring(0, insertPoint) + filterClause + query.substring(insertPoint)
        } else {
            // No WHERE clause found - this shouldn't happen for our queries but handle gracefully
            logger.warn("No WHERE clause found in query, appending filter at end: {}", query.take(100))
            query
        }
    }
}
