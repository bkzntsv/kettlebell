package com.kettlebell.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.mockk
import com.aallam.openai.client.OpenAI
import com.kettlebell.config.AppConfig
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.api.audio.Transcription

class AIServicePropertyTest : StringSpec({
    
    val openAI = mockk<OpenAI>()
    val config = mockk<AppConfig>()
    val aiService = AIServiceImpl(openAI, config)
    
    "Property 15: AI Retry Logic - should retry up to 3 times on failure" {
        // Mock throwing exception 3 times (all attempts fail)
        var attempts = 0
        coEvery { openAI.transcription(any()) } answers {
            attempts++
            throw RuntimeException("API Error")
        }
        
        shouldThrow<RuntimeException> {
            aiService.transcribeVoice(ByteArray(0))
        }
        
        attempts shouldBe 3
    }
    
    "AI Service Error Handling - should propagate exception after max retries" {
         coEvery { openAI.transcription(any()) } throws RuntimeException("Fatal Error")
         
         val exception = shouldThrow<RuntimeException> {
             aiService.transcribeVoice(ByteArray(0))
         }
         
         exception.message shouldBe "Fatal Error"
    }
})

