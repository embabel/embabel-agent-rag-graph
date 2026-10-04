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
import com.embabel.agent.rag.graph.fulltext.CompositeRequiredTermExtractor
import com.embabel.agent.rag.graph.fulltext.searchPreparedQuery
import com.embabel.agent.rag.graph.fulltext.syntaxNotesFor
import com.embabel.agent.rag.graph.model.EntityInContextView
import com.embabel.agent.rag.graph.model.EntityInContextViewQueryDsl
import com.embabel.agent.rag.graph.model.EntityNode
import com.embabel.agent.rag.graph.model.EntityNodeQueryDsl
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.RelationshipDirection
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.NativeFinder
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.drivine.annotation.Direction
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.NodeRef
import org.drivine.manager.RelateMode
import org.drivine.manager.Scored
import org.drivine.model.FragmentModel
import org.drivine.query.dsl.ResolvableNodeReference
import org.drivine.query.dsl.WhereBuilder
import org.drivine.query.dsl.any
import org.drivine.query.dsl.hasAnyLabel
import org.drivine.query.dsl.query
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/** A predicate on the entity node, written against whichever reference the query names it by. */
private typealias EntityPredicate = context(WhereBuilder<*>) ResolvableNodeReference.() -> Unit

/**
 * A [NamedEntityDataRepository] that reads and writes through Drivine's [GraphObjectManager], which
 * writes the statements for the configured engine — so it holds no Cypher, and behaves the same on
 * Neo4j, FalkorDB and Memgraph.
 *
 * It stores entities exactly as [DrivineNamedEntityDataRepository] does — one node under the entity
 * label, its type labels as labels, its properties as plain node properties — so the two can run
 * over the same data, and moving from one to the other needs no migration.
 *
 * A save adds an entity's labels and removes none, as the Cypher repository's does.
 *
 * Where it differs from [DrivineNamedEntityDataRepository]:
 *  - the entity label and the two entity index names are the defaults; a configuration that changes
 *    [GraphRagServiceProperties.entityNodeName], `entityIndex` or `entityFullTextIndex` is rejected
 *    at construction;
 *  - a full-text score is the engine's score normalised by Drivine, not divided by the best score of
 *    the result, so the best hit does not always score 1.0;
 *  - a node joined to the source by several relationships of one type is returned once by
 *    [findRelated];
 *  - a label or property name is always quoted, so one holding a space or a backtick is stored
 *    as given;
 *  - there is no `narrowedBy`: the only narrowing is [withContextScope].
 *
 * @param entitySchema owns the entity indexes the searches use — see [EntitySchemaProvisioner]
 */
class GraphObjectManagerEntityRepository private constructor(
    private val gom: GraphObjectManager,
    private val properties: GraphRagServiceProperties,
    override val dataDictionary: DataDictionary,
    private val embeddingService: EmbeddingService,
    private val entitySchema: EntitySchemaProvisioner,
    override val objectMapper: ObjectMapper,
    private val contextId: String?,
) : NamedEntityDataRepository {

    constructor(
        gom: GraphObjectManager,
        properties: GraphRagServiceProperties,
        dataDictionary: DataDictionary,
        embeddingService: EmbeddingService,
        entitySchema: EntitySchemaProvisioner,
        objectMapper: ObjectMapper = jacksonObjectMapper(),
    ) : this(gom, properties, dataDictionary, embeddingService, entitySchema, objectMapper, contextId = null)

    private val logger = LoggerFactory.getLogger(GraphObjectManagerEntityRepository::class.java)

    /** The entity's flat bag: it says which of an entity's properties are open ones. */
    private val openProperties = FragmentModel.from(EntityNode::class.java).propertyBags.single()

    override val nativeFinder: NativeFinder = DrivineNativeFinder(gom)

    init {
        requireDefault("entityNodeName", properties.entityNodeName, EntityNode.LABEL)
        requireDefault("entityIndex", properties.entityIndex, EntityNode.VECTOR_INDEX)
        requireDefault("entityFullTextIndex", properties.entityFullTextIndex, EntityNode.FULL_TEXT_INDEX)
        // Only the root repository owns the schema; a context-scoped one is a view of it.
        if (contextId == null) entitySchema.ensureOnce()
    }

    /** The entity label and index names are fixed on [EntityNode]; a configuration that differs cannot be honoured. */
    private fun requireDefault(setting: String, configured: String, fixed: String) = require(configured == fixed) {
        """
        GraphObjectManagerEntityRepository uses '$fixed' for $setting, but it is configured as
        '$configured'. Leave it at the default, or use DrivineNamedEntityDataRepository.
        """.trimIndent()
    }

    // Derived from the mode so behaviour and what the LLM is told cannot drift: see syntaxNotesFor.
    override val luceneSyntaxNotes get() = syntaxNotesFor(properties.queryMode)

    /**
     * A view of this repository that returns only entities a proposition of [contextId] mentions.
     * Writes are not scoped.
     */
    override fun withContextScope(contextId: String): GraphObjectManagerEntityRepository =
        GraphObjectManagerEntityRepository(
            gom, properties, dataDictionary, embeddingService, entitySchema, objectMapper, contextId,
        )

    // ----- write -----

    /**
     * Stores [entity] under its id, with the entity label and each of its labels widened through
     * the data dictionary — a label the dictionary knows brings its ancestors' labels, one it does
     * not know is written as it is. Labels and properties the stored node already has are kept.
     *
     * Does not write an embedding: nothing embeds entities yet.
     */
    override fun save(entity: NamedEntityData): NamedEntityData {
        val labels = widenedLabels(entity.labels())
        logger.debug("Saving entity '{}' with labels {} (from {})", entity.id, labels, entity.labels())
        gom.save(
            EntityNode(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                lastModifiedDate = System.currentTimeMillis(),
                labels = labels,
                properties = entity.properties.filterKeys(openProperties::owns),
            ),
        )
        // Read back, so the answer carries what the node already had as well as what was just written.
        return requireNotNull(gom.load(entity.id, EntityNode::class.java)) {
            "Entity '${entity.id}' was saved and then not found"
        }.toNamedEntityData()
    }

    /** Framework interfaces in a type's hierarchy are not domain types, so they are not labels. */
    private fun widenedLabels(labels: Set<String>): Set<String> {
        val domainLabels = dataDictionary.jvmTypes.map { it.ownLabel }.toSet()
        return labels.flatMap { label ->
            dataDictionary.jvmTypes.find { it.ownLabel == label }?.labels?.filter { it in domainLabels } ?: setOf(label)
        }.toSet()
    }

    override fun delete(id: String): Boolean = gom.delete(id, EntityNode::class.java) > 0

    override fun createRelationship(a: RetrievableIdentifier, b: RetrievableIdentifier, relationship: RelationshipData) {
        relate(a, b, relationship, RelateMode.CREATE)
    }

    override fun mergeRelationship(a: RetrievableIdentifier, b: RetrievableIdentifier, relationship: RelationshipData) {
        relate(a, b, relationship, RelateMode.MERGE)
    }

    private fun relate(a: RetrievableIdentifier, b: RetrievableIdentifier, relationship: RelationshipData, mode: RelateMode) {
        val joined = gom.edges.relate(a.asEntity(), b.asEntity(), relationship.name, relationship.properties, mode)
        logger.debug("{} ({} {})-[:{}]->({} {}): joined={}", mode, a.type, a.id, relationship.name, b.type, b.id, joined)
    }

    /** The entity with this id that also carries the identifier's type as a label. */
    private fun RetrievableIdentifier.asEntity() = NodeRef(EntityNode::class.java, id, setOf(type))

    // ----- read -----

    override fun findById(id: String): NamedEntityData? =
        if (contextId == null) gom.load(id, EntityNode::class.java)?.toNamedEntityData()
        else load { predicateOnId(id) }.singleOrNull()

    override fun findByLabel(label: String): List<NamedEntityData> = load { hasAnyLabel(label) }

    override fun find(label: String, filter: PropertyFilter?): List<NamedEntityData> = load {
        hasAnyLabel(label)
        filter?.let { applyFilter(it) }
    }

    override fun find(labels: EntityFilter.HasAnyLabel, filter: PropertyFilter?): List<NamedEntityData> = load {
        applyFilter(labels)
        filter?.let { applyFilter(it) }
    }

    /**
     * The entities joined to [source] by [relationshipName], each once. Not narrowed by a context
     * scope, as the Cypher repository's is not.
     */
    override fun findRelated(
        source: RetrievableIdentifier,
        relationshipName: String,
        direction: RelationshipDirection,
    ): List<NamedEntityData> = gom.edges.loadRelated(
        source.asEntity(),
        relationshipName,
        when (direction) {
            RelationshipDirection.OUTGOING -> Direction.OUTGOING
            RelationshipDirection.INCOMING -> Direction.INCOMING
            RelationshipDirection.BOTH -> Direction.UNDIRECTED
        },
        EntityNode::class.java,
    ).map { it.toNamedEntityData() }

    // ----- search -----

    override fun textSearch(
        request: TextSimilaritySearchRequest,
        metadataFilter: PropertyFilter?,
        entityFilter: EntityFilter?,
    ): List<SimilarityResult<NamedEntityData>> {
        // The index may not exist yet — see EntitySchemaProvisioner. An atomic read once it does.
        entitySchema.ensureOnce()
        // A blank query would reach the full-text parser and throw; empty-in, empty-out.
        if (request.query.isBlank()) return emptyList()
        // An entity named by an identifier is the lookup a vector index cannot serve, so the
        // identifier decides membership rather than the score — as for chunk full-text search.
        return searchPreparedQuery(request.query, properties.queryMode, CompositeRequiredTermExtractor()) { searchText ->
            matching(searchText, request) { applyFilters(metadataFilter, entityFilter) }
        }
    }

    override fun vectorSearch(
        request: TextSimilaritySearchRequest,
        metadataFilter: PropertyFilter?,
        entityFilter: EntityFilter?,
    ): List<SimilarityResult<NamedEntityData>> {
        entitySchema.ensureOnce()
        val vector = embeddingService.embed(request.query).toList()
        return nearest(vector, request) { applyFilters(metadataFilter, entityFilter) }
    }

    // ----- the two shapes a read takes: the entity alone, or the entity within a context -----

    private fun load(predicate: EntityPredicate): List<NamedEntityData> =
        if (contextId == null) {
            gom.loadAll(EntityNode::class.java, EntityNodeQueryDsl.INSTANCE) {
                where { predicate(query) }
            }.map { it.toNamedEntityData() }
        } else {
            gom.loadAll(EntityInContextView::class.java, EntityInContextViewQueryDsl.INSTANCE) {
                where { query.inContext(contextId, predicate) }
            }.map { it.entity.toNamedEntityData() }
        }

    private fun matching(
        searchText: String,
        request: TextSimilaritySearchRequest,
        predicate: EntityPredicate,
    ): List<SimilarityResult<NamedEntityData>> =
        if (contextId == null) {
            gom.loadMatching(
                EntityNode::class.java, EntityNodeQueryDsl.INSTANCE, searchText, request.topK, request.similarityThreshold,
            ) { where { predicate(query) } }.map { it.asResult { node -> node } }
        } else {
            gom.loadMatching(
                EntityInContextView::class.java, EntityInContextViewQueryDsl.INSTANCE,
                searchText, request.topK, request.similarityThreshold,
            ) { where { query.inContext(contextId, predicate) } }.map { it.asResult { view -> view.entity } }
        }

    private fun nearest(
        vector: List<Float>,
        request: TextSimilaritySearchRequest,
        predicate: EntityPredicate,
    ): List<SimilarityResult<NamedEntityData>> =
        if (contextId == null) {
            gom.loadNearest(
                EntityNode::class.java, EntityNodeQueryDsl.INSTANCE, vector, request.topK, request.similarityThreshold,
                searchK = properties.filteredSearchK(request.topK),
            ) { where { predicate(query) } }.map { it.asResult { node -> node } }
        } else {
            gom.loadNearest(
                EntityInContextView::class.java, EntityInContextViewQueryDsl.INSTANCE,
                vector, request.topK, request.similarityThreshold,
                searchK = properties.filteredSearchK(request.topK),
            ) { where { query.inContext(contextId, predicate) } }.map { it.asResult { view -> view.entity } }
        }

    /** [predicate] on the view's entity, and a proposition of [contextId] among those mentioning it. */
    context(_: WhereBuilder<EntityInContextViewQueryDsl>)
    private fun EntityInContextViewQueryDsl.inContext(contextId: String, predicate: EntityPredicate) {
        mentionedBy.any { this.contextId eq contextId }
        predicate(EntityInView)
    }

    private fun <T> Scored<T>.asResult(entity: (T) -> EntityNode): SimilarityResult<NamedEntityData> =
        SimilarityResult.create(entity(value).toNamedEntityData(), score)

    /**
     * The entity as callers know it. Its properties are every property of the node, as the Cypher
     * repository returns them: the open ones and the declared ones beside them.
     */
    private fun EntityNode.toNamedEntityData(): NamedEntityData = SimpleNamedEntityData(
        id = id,
        name = name.orEmpty(),
        description = description.orEmpty(),
        labels = labels,
        properties = buildMap {
            properties.forEach { (key, value) -> value?.let { put(key, it) } }
            put("id", id)
            name?.let { put("name", it) }
            description?.let { put("description", it) }
            lastModifiedDate?.let { put("lastModifiedDate", it) }
        },
    )

    /**
     * The view's entity, as a reference that can resolve a filter key. The generated reference for a
     * fragment inside a view cannot; this one names the same node and resolves as the fragment does.
     */
    private object EntityInView : ResolvableNodeReference {
        override val nodeAlias: String = EntityInContextViewQueryDsl.INSTANCE.entity.nodeAlias
        override val fieldKeyPaths: Map<String, String> = EntityNodeQueryDsl.INSTANCE.fieldKeyPaths
        override val bagPrefixes: List<String> = EntityNodeQueryDsl.INSTANCE.bagPrefixes
    }
}

context(_: WhereBuilder<*>)
private fun ResolvableNodeReference.predicateOnId(id: String) = applyFilter(PropertyFilter.Eq("id", id))
