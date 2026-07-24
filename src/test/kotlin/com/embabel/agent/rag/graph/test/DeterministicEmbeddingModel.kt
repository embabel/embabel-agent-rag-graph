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
package com.embabel.agent.rag.graph.test

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.util.LinkedList
import kotlin.math.sqrt

/**
 * A deterministic [EmbeddingModel] for characterization tests: identical text always maps to the
 * identical vector, so a vector search for a chunk's own text yields cosine similarity 1.0 for that
 * chunk and it ranks first.
 *
 * This is what makes vector search *assertable*. [FakeEmbeddingModel] emits random vectors, so a
 * vector search against it can only be checked for "it executed and returned candidates" — it cannot
 * prove ranking. With deterministic embeddings we can assert that the semantically-identical chunk is
 * actually retrieved, which is the behaviour the schema migration must preserve.
 *
 * The vector is derived by hashing the text into a per-dimension pseudo-random stream and L2-normalizing,
 * so cosine similarity between two identical texts is exactly 1.0 and unrelated texts are near-orthogonal.
 */
data class DeterministicEmbeddingModel(
    val dimensions: Int = 1536,
) : EmbeddingModel {

    private fun embedText(text: String): FloatArray {
        // A tiny xorshift stream seeded by the text hash — stable across JVMs and runs.
        var state = text.hashCode().toLong() xor 0x9E3779B97F4A7C15uL.toLong()
        if (state == 0L) state = 1L
        val vector = FloatArray(dimensions)
        var sumSquares = 0.0
        for (i in 0 until dimensions) {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            val v = (state and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat() - 0.5f
            vector[i] = v
            sumSquares += (v * v).toDouble()
        }
        val norm = sqrt(sumSquares).toFloat().coerceAtLeast(1e-12f)
        for (i in 0 until dimensions) {
            vector[i] = vector[i] / norm
        }
        return vector
    }

    override fun embed(document: Document): FloatArray = embedText(document.text ?: "")

    override fun embed(texts: List<String>): MutableList<FloatArray> =
        texts.map { embedText(it) }.toMutableList()

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        val output = LinkedList<Embedding>()
        request.instructions.forEachIndexed { i, text ->
            output.add(Embedding(embedText(text), i))
        }
        return EmbeddingResponse(output)
    }
}
