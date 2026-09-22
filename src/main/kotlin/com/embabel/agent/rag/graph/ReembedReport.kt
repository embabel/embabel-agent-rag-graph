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

/**
 * What a [GraphRagStore.reembedAll] did.
 *
 * In its own file because it outlived the class it was declared beside: it lived at the foot of
 * `DrivineStore.kt`, so removing that store took the return type of the interface with it (#31).
 * A type named in an interface belongs where the interface can see it, not inside one
 * implementation's file.
 *
 * @param chunks how many chunks were re-embedded
 * @param entities how many entities were re-embedded — zero for a store that does not walk them
 */
data class ReembedReport(
    val chunks: Int,
    val entities: Int,
)
