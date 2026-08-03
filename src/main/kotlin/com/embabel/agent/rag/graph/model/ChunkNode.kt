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
import com.embabel.agent.rag.model.ChunkStructure
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
 * The chunker's **structural** fields (`containerSectionId`, `sequenceNumber`, …) map to and from the
 * core [ChunkStructure], kept flat on disk under their original snake_case names via [GraphProperty] —
 * so the traversal Cypher that reads them (`expand_by_sequence.cypher`) is unchanged and no data
 * migration is needed. Genuinely free-form metadata goes to a [PropertyBag] under the `metadata.`
 * prefix.
 *
 * Both directions go through [Chunk.structure], **not** [Chunk.metadata]. The string-key view still
 * exposes the structural fields, so mapping through it happens to work — but only until that
 * deprecation shim is removed, at which point a metadata-based mapper would start reading nulls
 * silently: chunks would persist without `sequence_number` / `root_document_id`, `expand_by_sequence`
 * would stop matching, and `ChunkMergingEnhancer` (typed-only as of embabel/embabel-agent#1867) would
 * quietly stop merging. Going through the typed accessor makes that a compile error instead.
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
    @GraphProperty("container_section_url") val containerSectionUrl: String? = null,
    @GraphProperty("leaf_section_id") val leafSectionId: String? = null,
    @GraphProperty("leaf_section_title") val leafSectionTitle: String? = null,
    @GraphProperty("leaf_section_url") val leafSectionUrl: String? = null,
    @GraphProperty("sequence_number") val sequenceNumber: Long? = null,
    @GraphProperty("chunk_index") val chunkIndex: Long? = null,
    @GraphProperty("total_chunks") val totalChunks: Long? = null,
    @VectorIndex(similarity = SimilarityFunction.COSINE) val embedding: List<Float>? = null,
    @PropertyBag(prefix = "metadata") val freeFormMetadata: Map<String, Any?> = emptyMap(),
) : ContentElementNode {

    /**
     * Reconstruct the core [Chunk], restoring the promoted fields as its typed [ChunkStructure].
     * They remain readable as `chunk.metadata["sequence_number"]` for as long as core's compat view
     * lasts, but that is core's business now, not this mapper's.
     */
    override fun toCoreType(): Chunk = Chunk.create(
        text = text,
        parentId = parentId,
        metadata = freeFormMetadata,
        id = id,
        urtext = urtext,
        structure = ChunkStructure(
            rootDocumentId = rootDocumentId,
            containerSectionId = containerSectionId,
            containerSectionTitle = containerSectionTitle,
            // Pre-promotion rows bagged this one under `metadata.container_section_url`.
            containerSectionUrl = containerSectionUrl
                ?: freeFormMetadata[ChunkStructure.CONTAINER_SECTION_URL] as? String,
            leafSectionId = leafSectionId,
            leafSectionTitle = leafSectionTitle,
            leafSectionUrl = leafSectionUrl,
            chunkIndex = chunkIndex?.toInt(),
            totalChunks = totalChunks?.toInt(),
            sequenceNumber = sequenceNumber?.toInt(),
        ),
    )

    companion object {

        fun from(chunk: Chunk, embedding: List<Float>? = null): ChunkNode {
            val structure = chunk.structure
            return ChunkNode(
                id = chunk.id,
                text = chunk.text,
                urtext = chunk.urtext,
                parentId = chunk.parentId,
                uri = chunk.uri,
                rootDocumentId = structure.rootDocumentId,
                containerSectionId = structure.containerSectionId,
                containerSectionTitle = structure.containerSectionTitle,
                containerSectionUrl = structure.containerSectionUrl,
                leafSectionId = structure.leafSectionId,
                leafSectionTitle = structure.leafSectionTitle,
                leafSectionUrl = structure.leafSectionUrl,
                sequenceNumber = structure.sequenceNumber?.toLong(),
                chunkIndex = structure.chunkIndex?.toLong(),
                totalChunks = structure.totalChunks?.toLong(),
                embedding = embedding,
                // Belt and braces: core's factories already lift structural keys out of metadata, so
                // this is a no-op for any chunk built through them — but a hand-rolled Chunk impl
                // could still carry them, and they must not be duplicated into the bag.
                freeFormMetadata = ChunkStructure.withoutStructuralKeys(chunk.metadata),
            )
        }
    }
}
