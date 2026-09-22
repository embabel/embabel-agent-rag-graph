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

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.EmbabelComponent
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.rag.graph.DrivineStore
import com.embabel.agent.rag.tools.ToolishRag
import com.embabel.agent.rag.tools.TryHyDE
import com.embabel.chat.Conversation
import com.embabel.chat.UserMessage
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean

/**
 * Simplified ChatActions for testing RAG functionality.
 *
 * This is a cut-down version of the Guide's ChatActions that:
 * - Responds to user messages with RAG-powered context
 * - Uses the DrivineStore for vector/fulltext search
 * - Uses a configurable LLM for response generation
 */
@EmbabelComponent
@ConditionalOnBean(DrivineStore::class)
class RagChatActions(
    private val drivineStore: DrivineStore,
    private val testProperties: RagTestProperties,
) {

    private val logger = LoggerFactory.getLogger(RagChatActions::class.java)

    companion object {
        private val SYSTEM_PROMPT = """
            You are a helpful assistant for testing RAG (Retrieval Augmented Generation) functionality.

            You have access to a knowledge base that you can search using the tools available to you.
            When answering questions:
            1. Use the RAG search tools to find relevant information
            2. Ground your responses in the retrieved context
            3. If you cannot find relevant information, say so honestly
            4. Be concise and helpful

            This is a test environment for debugging and validating RAG search functionality.
        """.trimIndent()
    }

    @Action(canRerun = true, trigger = UserMessage::class)
    fun respond(
        conversation: Conversation,
        context: ActionContext
    ) {
        logger.info("Incoming chat request from user {}", context.user())

        val assistantMessage = context
            .ai()
            .withLlm(testProperties.chatLlm)
            .withId("rag_chat_response")
            .withReference(
                ToolishRag(
                    "docs",
                    "RAG documentation search",
                    drivineStore
                ).withHint(TryHyDE.usingConversationContext())
            )
            .withSystemPrompt(SYSTEM_PROMPT)
            .respond(conversation.messages)

        conversation.addMessage(assistantMessage)
        context.sendMessage(assistantMessage)
    }
}
