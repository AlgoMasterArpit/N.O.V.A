package com.arpit.nova

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.WebSearchTool20260209
import java.time.Duration

/** Answers questions out loud. One instance = one conversation, so follow-up questions have context. */
class Claude(apiKey: String) {
    companion object {
        const val MODEL = "claude-opus-5-5"
        private val SYSTEM = """
            You are N.O.V.A, Arpit's personal voice assistant, in the spirit of JARVIS: calm, sharp, a little witty. Call him "boss".
            Your reply is read aloud by text-to-speech: answer in 1 to 4 short spoken sentences, with no markdown, lists, emojis, links or symbols.
            Reply in Hinglish (Hindi in Roman script) when he speaks Hinglish, otherwise English.
            He lives in India (IST). Use web search for anything current: news, weather, scores, prices.
            Never say your own name.
        """.trimIndent()
    }

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(60))
        .build()
    private val turns = ArrayList<MessageParam>()

    /** Blocking: call off the main thread. Always returns something speakable. */
    fun ask(question: String): String = synchronized(turns) {
        turns += MessageParam.builder().role(MessageParam.Role.USER).content(question).build()
        try {
            val said = StringBuilder()
            repeat(3) { // a long web search can pause the turn; continue it
                val r = client.messages().create(params())
                turns += r.toParam()
                r.content().forEach { b -> b.text().ifPresent { said.append(it.text()).append(' ') } }
                when (r.stopReason().orElse(null)) {
                    StopReason.PAUSE_TURN -> {}
                    StopReason.REFUSAL -> return "Boss, isme main help nahi kar sakta."
                    else -> return said.toString().trim().ifEmpty { "Boss, iska jawab nahi mila." }
                }
            }
            said.toString().trim()
        } catch (e: UnauthorizedException) {
            turns.clear(); "Boss, Claude API key galat hai. NOVA app mein check karo."
        } catch (e: RateLimitException) {
            turns.clear(); "Boss, Claude ki limit khatam ho gayi. Thodi der baad try karo."
        } catch (e: AnthropicServiceException) {
            turns.clear(); "Boss, Claude abhi busy hai. Ek baar phir pucho."
        } catch (e: AnthropicIoException) {
            turns.clear(); "Boss, net slow hai, Claude tak baat nahi pahunchi."
        }
    }

    private fun params() = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(16000L)
        .system(SYSTEM)
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build()) // fast, chatty answers
        .addTool(WebSearchTool20260209.builder().maxUses(3L).build())
        .messages(turns.toList())
        // If a safety check declines, the API retries on a fallback model instead of failing.
        .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        .build()
}
