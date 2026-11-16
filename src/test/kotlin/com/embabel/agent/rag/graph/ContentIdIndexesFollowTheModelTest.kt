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

import com.embabel.agent.rag.graph.model.ContentElementFragment
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.model.FragmentModel
import org.drivine.schema.RangeIndexSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter

/**
 * The content id indexes are declared on the model, as `@NodeId @RangeIndex`, and the store provisions what
 * the model declares. These check that every content fragment's saves are served by one of them, so a new
 * fragment, or a lost annotation, cannot leave the saves it MERGEs by id scanning their label.
 */
class ContentIdIndexesFollowTheModelTest {

    /** The labels whose nodes the store writes and finds by id. */
    private val contentLabels = setOf("ContentElement", GraphRagServiceProperties().chunkNodeName)

    /** Every `@NodeFragment` in the model package, interfaces included. */
    private fun modelFragments(): List<Class<*>> {
        val scanner = object : ClassPathScanningCandidateComponentProvider(false) {
            override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition) = true
        }
        scanner.addIncludeFilter(AnnotationTypeFilter(NodeFragment::class.java))
        return scanner.findCandidateComponents(ContentElementFragment::class.java.packageName)
            .map { Class.forName(it.beanClassName) }
    }

    /**
     * The content fragments in [fragments] whose `@NodeId` no index in [indexes] serves: a save of one is a
     * MERGE by its id under all its labels, so an index on its id under any one of them is enough.
     */
    private fun unindexed(fragments: List<Class<*>>, indexes: List<RangeIndexSpec>): List<String> =
        fragments.mapNotNull { fragment ->
            val labels = FragmentModel.labelsFor(fragment)
            if (labels.none { it in contentLabels }) return@mapNotNull null
            val nodeId = FragmentModel.from(fragment).nodeIdProperty ?: return@mapNotNull null
            val served = indexes.any { it.label in labels && it.properties == listOf(nodeId) }
            if (served) null else "${fragment.simpleName}($labels).$nodeId"
        }

    @Test
    @DisplayName("the indexes read off the model are the ones the store used to list by hand")
    fun `derived indexes equal the old hand list`() {
        assertEquals(
            setOf(
                RangeIndexSpec("ContentElement", listOf("id")),
                RangeIndexSpec(GraphRagServiceProperties().chunkNodeName, listOf("id")),
            ),
            contentIdIndexes().toSet(),
        )
    }

    @Test
    @DisplayName("every content fragment's @NodeId is served by an index the model declares")
    fun `every content fragment is indexed by its id`() {
        val fragments = modelFragments()
        assertTrue(fragments.size > CONTENT_ID_FRAGMENTS.size, "the scan should find the whole model, found $fragments")
        assertEquals(emptyList<String>(), unindexed(fragments, contentIdIndexes()))
    }

    @NodeFragment(labels = ["Chunk"])
    data class ChunkByKey(@NodeId val key: String)

    @Test
    @DisplayName("a content fragment whose @NodeId carries no @RangeIndex is caught")
    fun `an unindexed node id is caught`() {
        // Without the annotations, the two fragments the indexes are declared on are unserved.
        assertEquals(
            setOf("ContentElementFragment", "ChunkPlaceFragment"),
            unindexed(CONTENT_ID_FRAGMENTS.map { it.java }, emptyList()).map { it.substringBefore('(') }.toSet(),
        )
        // A fragment found by another property than the indexed `id` is caught too.
        assertEquals(
            listOf("ChunkByKey([Chunk]).key"),
            unindexed(listOf(ChunkByKey::class.java), contentIdIndexes()),
        )
    }
}
