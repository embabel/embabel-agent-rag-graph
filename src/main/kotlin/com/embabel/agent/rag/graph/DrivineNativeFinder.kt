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

import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.service.NativeFinder
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.manager.StatelessGraphObjectManager

/**
 * [NativeFinder] that uses the Drivine [GraphObjectManager] to load entities
 * annotated with [@NodeFragment][NodeFragment] or [@GraphView][GraphView].
 *
 * Returns null for types without these annotations, allowing fallback to generic lookup.
 *
 * @param graphObjectManager the Drivine graph object manager for native loading
 */
class DrivineNativeFinder(
    private val graphObjectManager: StatelessGraphObjectManager,
) : NativeFinder {

    override fun <T : NamedEntity> findById(id: String, type: Class<T>): T? {
        if (!isNativeType(type)) return null
        return graphObjectManager.load(id, type)
    }

    override fun <T : NamedEntity> findAll(type: Class<T>): List<T>? {
        if (!isNativeType(type)) return null
        return graphObjectManager.loadAll(type)
    }

    private fun isNativeType(type: Class<*>): Boolean =
        type.isAnnotationPresent(NodeFragment::class.java) ||
                type.isAnnotationPresent(GraphView::class.java)
}
