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
package com.embabel.agent.rag.graph.fulltext

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * These notes reach the LLM verbatim, inside the search tool's description. Getting them wrong is
 * not cosmetic: telling a model it has operators when the store escapes them teaches it to write
 * queries that cannot work, and every one of those failures looks like a retrieval problem.
 *
 * The behavioural proof that the EXPRESSION wording works lives in the assistant's prompt-eval
 * suite (`rag-identifier-required-term`, 3/3 on gpt-4.1-mini against 0/3 for the notes that shipped
 * before). These guard the properties that suite cannot see from another repository.
 */
class FullTextSyntaxNotesTest {

    @Test
    @DisplayName("EXPRESSION teaches the + that makes an identifier lookup precise")
    fun `expression notes ask for required identifiers`() {
        val notes = syntaxNotesFor(FullTextQueryMode.EXPRESSION)
        assertTrue(notes.contains("+"), "must name the operator")
        assertTrue(
            notes.contains("identifier", ignoreCase = true),
            "must say WHAT to require — 'Full support' named a capability and taught nothing",
        )
        assertTrue(
            notes.contains("Example:"),
            "the worked example is what took compliance from 2/3 to 3/3; two rounds of rule-tightening did not",
        )
    }

    @Test
    @DisplayName("EXPRESSION warns against requiring ordinary words")
    fun `expression notes guard the over-requiring failure`() {
        // The more dangerous failure: `+ER20328_23 +payment +service` looks precise and returns
        // nothing, because it discards every document that words the context differently.
        // Whitespace-normalised so re-wrapping the prose cannot fail the test for no reason.
        val notes = syntaxNotesFor(FullTextQueryMode.EXPRESSION).replace(Regex("\\s+"), " ")
        assertTrue(
            notes.contains("Do NOT put + on ordinary words"),
            "must warn against over-requiring, not just ask for the identifier",
        )
    }

    @Test
    @DisplayName("LITERAL offers no operator it cannot honour")
    fun `literal notes promise no syntax`() {
        val notes = syntaxNotesFor(FullTextQueryMode.LITERAL)
        assertFalse(
            notes.contains("Lucene", ignoreCase = true),
            "under LITERAL every character is escaped; naming the syntax invites queries that search for punctuation",
        )
        assertTrue(
            notes.contains("NOT available", ignoreCase = true),
            "must say plainly that operators do not work — a model told nothing will try them",
        )
        assertTrue(
            notes.contains("exactly", ignoreCase = true) && notes.contains("identifier", ignoreCase = true),
            "must tell the model identifiers are handled for it, so it does not try to mark them",
        )
    }

    @Test
    @DisplayName("the two modes never advertise the same surface")
    fun `notes differ by mode`() {
        // The drift this whole indirection exists to prevent.
        assertFalse(
            syntaxNotesFor(FullTextQueryMode.LITERAL) == syntaxNotesFor(FullTextQueryMode.EXPRESSION),
            "notes are derived from the mode precisely so they cannot say the same thing",
        )
    }
}
