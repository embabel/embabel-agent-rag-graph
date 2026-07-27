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
 * Recursive `@GraphView` over the `HAS_PARENT` hierarchy: loading the view rooted at a document pulls
 * its whole subtree (sections, then chunks) in one typed query via nested pattern comprehensions,
 * replacing hand-rolled tree assembly. `children` follows `HAS_PARENT` **incoming** (a child points at
 * its parent), and is self-referential so it recurses to `maxDepth`.
 */
@GraphView
data class ContentTreeView(
    @Root val element: ContentElementFragment,
    @GraphRelationship(type = "HAS_PARENT", direction = Direction.INCOMING, maxDepth = 10)
    val children: List<ContentTreeView>,
)
