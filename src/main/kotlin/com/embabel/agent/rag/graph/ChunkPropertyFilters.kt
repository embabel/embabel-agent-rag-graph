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

import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.graph.model.ChunkNodeQueryDsl
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.WhereBuilder
import org.drivine.query.dsl.anyOf
import org.drivine.query.dsl.hasAnyLabel
import org.drivine.query.dsl.not
import org.drivine.query.dsl.predicateOn

/** Apply the optional metadata + entity filters into the enclosing `where { }` (both `AND`-ed; null = no predicate). */
context(_: WhereBuilder<*>)
fun ChunkNodeQueryDsl.applyFilters(metadataFilter: PropertyFilter?, entityFilter: EntityFilter?) {
    metadataFilter?.let { applyFilter(it) }
    entityFilter?.let { applyFilter(it) }
}

/**
 * Translate an embabel [PropertyFilter] (an [EntityFilter] is one — `EntityFilter : ObjectFilter :
 * PropertyFilter`) into Drivine's typed `where { }` DSL, so metadata-filtered search runs through the
 * object manager instead of hand-written filter Cypher. Values bind as `$param_*` (no injection).
 *
 * `predicateOn(key, …)` is Drivine's model-aware resolver (0.0.70): it maps a filter key to its stored
 * path from `ChunkNode`'s own `@GraphProperty` / `@PropertyBag` annotations — a promoted structural key
 * to its flat on-disk name, everything else to the `metadata.` bag — so this translator no longer needs
 * to know the model's structure. `And` is the implicit conjunction of a `where` block; `Or` / `Not` map
 * to Drivine's [anyOf] / [not].
 */
context(_: WhereBuilder<*>)
fun ChunkNodeQueryDsl.applyFilter(filter: PropertyFilter) {
    when (filter) {
        is PropertyFilter.Eq -> predicateOn(filter.key, ComparisonOperator.EQUALS, filter.value)
        is PropertyFilter.Ne -> predicateOn(filter.key, ComparisonOperator.NOT_EQUALS, filter.value)
        is PropertyFilter.Gt -> predicateOn(filter.key, ComparisonOperator.GREATER_THAN, filter.value)
        is PropertyFilter.Gte -> predicateOn(filter.key, ComparisonOperator.GREATER_THAN_OR_EQUAL, filter.value)
        is PropertyFilter.Lt -> predicateOn(filter.key, ComparisonOperator.LESS_THAN, filter.value)
        is PropertyFilter.Lte -> predicateOn(filter.key, ComparisonOperator.LESS_THAN_OR_EQUAL, filter.value)
        is PropertyFilter.In -> predicateOn(filter.key, ComparisonOperator.IN, filter.values)
        is PropertyFilter.Nin -> predicateOn(filter.key, ComparisonOperator.NOT_IN, filter.values)
        is PropertyFilter.Contains -> predicateOn(filter.key, ComparisonOperator.CONTAINS, filter.value)
        is PropertyFilter.StartsWith -> predicateOn(filter.key, ComparisonOperator.STARTS_WITH, filter.value)
        is PropertyFilter.EndsWith -> predicateOn(filter.key, ComparisonOperator.ENDS_WITH, filter.value)
        is PropertyFilter.Like -> predicateOn(filter.key, ComparisonOperator.MATCHES, filter.pattern)
        is PropertyFilter.ContainsIgnoreCase -> predicateOn(filter.key, ComparisonOperator.CONTAINS_IGNORE_CASE, filter.value)
        is PropertyFilter.EqIgnoreCase -> predicateOn(filter.key, ComparisonOperator.EQUALS_IGNORE_CASE, filter.value)
        is PropertyFilter.And -> filter.filters.forEach { applyFilter(it) }
        is PropertyFilter.Or -> anyOf { filter.filters.forEach { applyFilter(it) } }
        is PropertyFilter.Not -> not { applyFilter(filter.filter) }
        is EntityFilter.HasAnyLabel -> hasAnyLabel(*filter.labels.toTypedArray())
        // List-membership (`value IN n.key`, e.g. filter chunks by a `tags` value). Drivine has the typed
        // `hasItem` infix, but the *dynamic-key* path (predicateOn) has no list-membership operator yet —
        // so this is the one PropertyFilter we can't translate. TODO: predicateOn(key, HAS_ELEMENT, value)
        // once Drivine adds a dynamic list-membership operator.
        is PropertyFilter.HasElement -> throw UnsupportedOperationException(
            "PropertyFilter.HasElement (list-membership) is not yet translatable — pending a dynamic " +
                "list-membership operator in Drivine's predicateOn",
        )
        else -> throw UnsupportedOperationException(
            "GraphObjectManagerStore cannot translate ${filter::class.simpleName} to a graph query filter",
        )
    }
}
