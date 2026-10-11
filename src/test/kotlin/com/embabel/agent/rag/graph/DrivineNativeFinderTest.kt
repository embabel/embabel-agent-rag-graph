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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.drivine.annotation.NodeFragment
import org.drivine.manager.StatelessGraphObjectManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@NodeFragment
interface NativeProduct : NamedEntity {
    val price: Double
}

interface NonNativeProduct : NamedEntity {
    val price: Double
}

class DrivineNativeFinderTest {

    private val graphObjectManager: StatelessGraphObjectManager = mockk()
    private val finder = DrivineNativeFinder(graphObjectManager)

    @Nested
    inner class FindByIdTest {

        @Test
        fun `returns null for non-native type`() {
            val result = finder.findById("id-1", NonNativeProduct::class.java)

            assertNull(result)
            verify(exactly = 0) { graphObjectManager.load(any<String>(), any<Class<*>>()) }
        }

        @Test
        fun `delegates to graphObjectManager for NodeFragment type`() {
            val expected: NativeProduct = mockk()
            every { graphObjectManager.load("id-1", NativeProduct::class.java) } returns expected

            val result = finder.findById("id-1", NativeProduct::class.java)

            assertSame(expected, result)
            verify { graphObjectManager.load("id-1", NativeProduct::class.java) }
        }
    }

    @Nested
    inner class FindAllTest {

        @Test
        fun `returns null for non-native type`() {
            val result = finder.findAll(NonNativeProduct::class.java)

            assertNull(result)
            verify(exactly = 0) { graphObjectManager.loadAll(any<Class<*>>()) }
        }

        @Test
        fun `delegates to graphObjectManager for NodeFragment type`() {
            val expected: List<NativeProduct> = listOf(mockk())
            every { graphObjectManager.loadAll(NativeProduct::class.java) } returns expected

            val result = finder.findAll(NativeProduct::class.java)

            assertSame(expected, result)
            verify { graphObjectManager.loadAll(NativeProduct::class.java) }
        }
    }
}
