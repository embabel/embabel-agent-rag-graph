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

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root

/**
 * A content element and the element it sits inside: saving one writes the `HAS_PARENT` edge
 * between two elements that are already stored. Both ends carry an id and nothing else, so a
 * merge-patch save touches neither's properties.
 */
@GraphView
data class ParentLinkView(
    @Root val element: ContentElementFragment,
    @GraphRelationship(type = "HAS_PARENT", direction = Direction.OUTGOING)
    val parent: ContentElementFragment,
)
