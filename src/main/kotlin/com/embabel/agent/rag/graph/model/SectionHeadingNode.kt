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

import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

/**
 * A section as a table of contents needs it: where it is and what it is called, without the text
 * a [LeafSectionNode] carries. Loaded through this base, each section arrives as the heading of
 * its own kind, so leaf or container is the type and not a property.
 */
@NodeFragment(labels = ["ContentElement"])
sealed interface SectionHeadingNode {
    @get:NodeId val id: String
    val title: String
    val parentId: String?
    @GraphProperty("root_document_id") val rootDocumentId: String?
    val ordinal: Long?
    val depth: Long?
}

@NodeFragment(labels = ["LeafSection"])
data class LeafSectionHeading(
    @NodeId override val id: String,
    override val title: String,
    override val parentId: String? = null,
    @GraphProperty("root_document_id") override val rootDocumentId: String? = null,
    override val ordinal: Long? = null,
    override val depth: Long? = null,
) : SectionHeadingNode

@NodeFragment(labels = ["ContainerSection"])
data class ContainerSectionHeading(
    @NodeId override val id: String,
    override val title: String,
    override val parentId: String? = null,
    @GraphProperty("root_document_id") override val rootDocumentId: String? = null,
    override val ordinal: Long? = null,
    override val depth: Long? = null,
) : SectionHeadingNode
