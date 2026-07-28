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
package com.embabel.agent.rag.graph.model

import com.embabel.agent.rag.model.Chunk
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction

/**
 * Drivine `@NodeFragment` model for a persisted [Chunk], mapped through Drivine's
 * [org.drivine.manager.GraphObjectManager] instead of hand-rolled Cypher.
 *
 * The chunker's **structural** keys (`container_section_id`, `sequence_number`, …) are promoted from
 * the open metadata map to real typed fields, kept flat on disk under their original snake_case names
 * via [GraphProperty] — so the traversal Cypher that reads them (`expand_by_sequence.cypher`) is
 * unchanged and no data migration is needed. Genuinely free-form metadata goes to a [PropertyBag]
 * under the `metadata.` prefix. [toCoreType] reassembles both back into [Chunk.metadata], preserving
 * the core contract (callers still read `chunk.metadata["sequence_number"]`).
 *
 * The `embedding` is a [VectorIndex] property, so a single [org.drivine.manager.GraphObjectManager.save]
 * persists structure and embedding together — and on FalkorDB Drivine writes it as `vecf32(...)` so the
 * vector index picks it up (no hand-rolled `vecf32` wrapping).
 */
@NodeFragment(labels = ["Chunk"])
data class ChunkNode(
    @NodeId override val id: String,
    @FullTextIndex val text: String,
    val urtext: String,
    val parentId: String,
    override val uri: String? = null,
    @GraphProperty("root_document_id") val rootDocumentId: String? = null,
    @GraphProperty("container_section_id") val containerSectionId: String? = null,
    @GraphProperty("container_section_title") val containerSectionTitle: String? = null,
    @GraphProperty("leaf_section_id") val leafSectionId: String? = null,
    @GraphProperty("leaf_section_title") val leafSectionTitle: String? = null,
    @GraphProperty("leaf_section_url") val leafSectionUrl: String? = null,
    @GraphProperty("sequence_number") val sequenceNumber: Long? = null,
    @GraphProperty("chunk_index") val chunkIndex: Long? = null,
    @GraphProperty("total_chunks") val totalChunks: Long? = null,
    @VectorIndex(similarity = SimilarityFunction.COSINE) val embedding: List<Float>? = null,
    @PropertyBag(prefix = "metadata") val freeFormMetadata: Map<String, Any?> = emptyMap(),
) : ContentElementNode {

    /** Reconstruct the core [Chunk], merging the promoted structural fields back into its metadata. */
    override fun toCoreType(): Chunk = Chunk.create(
        text = text,
        parentId = parentId,
        metadata = buildMap {
            putAll(freeFormMetadata)
            rootDocumentId?.let { put(ROOT_DOCUMENT_ID, it) }
            containerSectionId?.let { put(CONTAINER_SECTION_ID, it) }
            containerSectionTitle?.let { put(CONTAINER_SECTION_TITLE, it) }
            leafSectionId?.let { put(LEAF_SECTION_ID, it) }
            leafSectionTitle?.let { put(LEAF_SECTION_TITLE, it) }
            leafSectionUrl?.let { put(LEAF_SECTION_URL, it) }
            sequenceNumber?.let { put(SEQUENCE_NUMBER, it) }
            chunkIndex?.let { put(CHUNK_INDEX, it) }
            totalChunks?.let { put(TOTAL_CHUNKS, it) }
        },
        id = id,
        urtext = urtext,
    )

    companion object {
        const val ROOT_DOCUMENT_ID = "root_document_id"
        const val CONTAINER_SECTION_ID = "container_section_id"
        const val CONTAINER_SECTION_TITLE = "container_section_title"
        const val LEAF_SECTION_ID = "leaf_section_id"
        const val LEAF_SECTION_TITLE = "leaf_section_title"
        const val LEAF_SECTION_URL = "leaf_section_url"
        const val SEQUENCE_NUMBER = "sequence_number"
        const val CHUNK_INDEX = "chunk_index"
        const val TOTAL_CHUNKS = "total_chunks"

        /** The metadata keys promoted to typed fields — everything else stays in the property bag. */
        private val STRUCTURAL_KEYS = setOf(
            ROOT_DOCUMENT_ID, CONTAINER_SECTION_ID, CONTAINER_SECTION_TITLE,
            LEAF_SECTION_ID, LEAF_SECTION_TITLE, LEAF_SECTION_URL,
            SEQUENCE_NUMBER, CHUNK_INDEX, TOTAL_CHUNKS,
        )

        /**
         * Every property this fragment persists as a **flat** (non-`metadata.`-bagged) node property: the
         * base fields plus the promoted [STRUCTURAL_KEYS]. Used to detect legacy free-form metadata that an
         * older store wrote flat instead of into the bag (see [LegacyChunkMetadataCheck]) — a flat key
         * outside this set is not one of ours.
         */
        val KNOWN_FLAT_PROPERTIES: Set<String> =
            STRUCTURAL_KEYS + setOf("id", "text", "urtext", "parentId", "uri", "embedding")

        private fun Map<String, Any?>.longOrNull(key: String): Long? =
            when (val v = this[key]) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull()
                else -> null
            }

        fun from(chunk: Chunk, embedding: List<Float>? = null): ChunkNode {
            val md = chunk.metadata
            return ChunkNode(
                id = chunk.id,
                text = chunk.text,
                urtext = chunk.urtext,
                parentId = chunk.parentId,
                uri = chunk.uri,
                rootDocumentId = md[ROOT_DOCUMENT_ID] as? String,
                containerSectionId = md[CONTAINER_SECTION_ID] as? String,
                containerSectionTitle = md[CONTAINER_SECTION_TITLE] as? String,
                leafSectionId = md[LEAF_SECTION_ID] as? String,
                leafSectionTitle = md[LEAF_SECTION_TITLE] as? String,
                leafSectionUrl = md[LEAF_SECTION_URL] as? String,
                sequenceNumber = md.longOrNull(SEQUENCE_NUMBER),
                chunkIndex = md.longOrNull(CHUNK_INDEX),
                totalChunks = md.longOrNull(TOTAL_CHUNKS),
                embedding = embedding,
                freeFormMetadata = md.filterKeys { it !in STRUCTURAL_KEYS },
            )
        }
    }
}
