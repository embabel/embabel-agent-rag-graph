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
 * An entity together with the propositions that mention it. Loaded with a predicate on
 * [mentionedBy], it is how an entity is found within one context: an entity belongs to a context
 * when a proposition of that context mentions it.
 */
@GraphView
data class EntityInContextView(
    @Root val entity: EntityNode,
    @GraphRelationship(type = "MENTIONS", direction = Direction.INCOMING)
    val mentionedBy: List<MentioningProposition> = emptyList(),
)
