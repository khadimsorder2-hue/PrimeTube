package com.github.libretube.helpers

import android.content.Context
import com.github.libretube.constants.PreferenceKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PrimeTube: AI subtitle translation to Bangla with Google Gemini.
 *
 * The user enters their Gemini API key + model once in the settings and
 * turns the feature on. From then on every video that has a subtitle track
 * is translated on playback ("trigger") - the result is cached per video on
 * disk so it only ever translates once.
 *
 * Pipeline:
 *  1. download the original subtitle track (VTT / SRT / TML text)
 *  2. parse it into timed cues, drop tag noise, merge rolling duplicates
 *  3. translate the cues in small batches through the Gemini API
 *  4. cache the finished Bangla cue list for instant reuse
 */
object GeminiSubtitleHelper {

    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val BATCH_SIZE = 12
    private const val MAX_CUES = 1200
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36 PrimeTube/1.0"

    const val DEFAULT_MODEL = "gemini-2.5-flash"

    /** PrimeTube: one translated subtitle cue (milliseconds). */
    data class AiCue(val startMs: Long, val endMs: Long, val text: String)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun isEnabled(context: Context): Boolean =
        PreferenceHelper.getBoolean(PreferenceKeys.PRIME_AI_SUBTITLES, false) &&
            getApiKey(context).isNotBlank()

    fun getApiKey(context: Context): String =
        PreferenceHelper.getString(PreferenceKeys.PRIME_AI_API_KEY, "").trim()

    fun getModel(context: Context): String =
        PreferenceHelper.getString(PreferenceKeys.PRIME_AI_MODEL, DEFAULT_MODEL)
            .trim()
            .ifBlank { DEFAULT_MODEL }

    /**
     * PrimeTube: full pipeline for one video. Returns an empty list when
     * nothing could be translated (missing key, network error, API error).
     */
    suspend fun translateSubtitles(
        context: Context,
        videoId: String,
        subtitleUrl: String,
        languageCode: String?
    ): List<AiCue> = withContext(Dispatchers.IO) {
        if (!isEnabled(context)) return@withContext emptyList()

        val cache = cacheFile(context, videoId, languageCode)
        runCatching { readCache(cache) }.getOrNull()?.let { return@withContext it }

        val text = runCatching { downloadText(subtitleUrl) }.getOrDefault("")
        if (text.isBlank()) return@withContext emptyList()

        val cues = parseCueFile(text)
        if (cues.isEmpty()) return@withContext emptyList()

        val translated = translateCues(context, cues)
        if (translated.isNotEmpty()) {
            runCatching { writeCache(cache, translated) }
        }
        translated
    }

    // ---------------- translation ----------------

    private fun translateCues(context: Context, cues: List<AiCue>): List<AiCue> {
        val key = getApiKey(context)
        if (key.isBlank()) return emptyList()
        val model = getModel(context)

        val limited = cues.take(MAX_CUES)
        val result = mutableListOf<AiCue>()
        var successBatches = 0

        limited.chunked(BATCH_SIZE).forEach { batch ->
            val translated = runCatching {
                requestTranslation(model, key, batch.map { it.text })
            }.getOrNull()

            if (translated != null && translated.size == batch.size) {
                successBatches++
                batch.forEachIndexed { index, cue ->
                    val newText = translated[index].trim()
                    result.add(cue.copy(text = newText.ifBlank { cue.text }))
                }
            } else {
                // keep the original text for failed batches
                result.addAll(batch)
            }
        }

        // PrimeTube: every batch failed -> the key/model is broken
        return if (successBatches == 0) emptyList() else result
    }

    private fun requestTranslation(model: String, key: String, lines: List<String>): List<String> {
        val numbered = lines.mapIndexed { index, text ->
            "${index + 1}. ${text.replace('\n', ' ')}"
        }
        val prompt = "Translate each numbered subtitle line into natural, conversational " +
            "Bengali (Bangla). Keep proper nouns, brand names and numbers unchanged. " +
            "Reply with ONLY a JSON array of the translated strings in the same order - " +
            "no explanations, no markdown.\n\n" + numbered.joinToString("\n")

        val body = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put("generationConfig", JSONObject().put("temperature", 0.2))
        }.toString()

        val request = Request.Builder()
            .url("$API_BASE$model:generateContent?key=$key")
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gemini HTTP ${response.code}")
            val answer = JSONObject(raw)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
            return parseJsonArrayAnswer(answer)
        }
    }

    private fun parseJsonArrayAnswer(answer: String): List<String> {
        var clean = answer.trim()
        // PrimeTube: the model sometimes wraps the array in markdown fences
        if (clean.startsWith("```")) {
            clean = clean.removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
        }
        val start = clean.indexOf('[')
        val end = clean.lastIndexOf(']')
        if (start >= 0 && end > start) clean = clean.substring(start, end + 1)
        val array = JSONArray(clean)
        return (0 until array.length()).map { array.optString(it) }
    }

    // ---------------- cue parsing (VTT / SRT) ----------------

    private val cueTimingRegex = Regex(
        "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*" +
            "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
    )

    private fun parseCueFile(text: String): List<AiCue> {
        val cues = mutableListOf<AiCue>()
        val lines = text.lines()
        var index = 0
        while (index < lines.size) {
            val match = cueTimingRegex.find(lines[index])
            if (match == null) {
                index++
                continue
            }
            fun g(n: Int) = match.groupValues[n].toLong()
            val startMs = g(1) * 3_600_000 + g(2) * 60_000 + g(3) * 1_000 + g(4)
            val endMs = g(5) * 3_600_000 + g(6) * 60_000 + g(7) * 1_000 + g(8)
            index++

            val builder = StringBuilder()
            while (
                index < lines.size &&
                lines[index].isNotBlank() &&
                !cueTimingRegex.containsMatchIn(lines[index])
            ) {
                builder.append(lines[index]).append('\n')
                index++
            }
            val raw = builder.toString()
                .replace(Regex("<[^>]*>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .trim()
            if (raw.isNotEmpty()) cues.add(AiCue(startMs, endMs, raw))
        }
        return mergeDuplicates(cues)
    }

    /**
     * PrimeTube: YouTube auto-generated captions repeat the same rolling text
     * in consecutive cues - merge them into one stable cue per sentence.
     */
    private fun mergeDuplicates(cues: List<AiCue>): List<AiCue> {
        val merged = mutableListOf<AiCue>()
        for (cue in cues) {
            val last = merged.lastOrNull()
            if (
                last != null &&
                last.text.replace(" ", "") == cue.text.replace(" ", "") &&
                cue.startMs <= last.endMs + 200
            ) {
                merged[merged.size - 1] = last.copy(endMs = maxOf(last.endMs, cue.endMs))
            } else {
                merged.add(cue)
            }
        }
        return merged
    }

    // ---------------- download + cache ----------------

    private fun downloadText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun cacheFile(context: Context, videoId: String, languageCode: String?): File {
        val dir = File(context.cacheDir, "prime_ai_subs").apply { mkdirs() }
        val safe = (videoId + "_" + (languageCode ?: "orig") + "_" + getModel(context))
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "$safe.json")
    }

    private fun readCache(file: File): List<AiCue>? {
        if (!file.isFile) return null
        val array = JSONArray(file.readText())
        if (array.length() == 0) return null
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            AiCue(obj.optLong("s"), obj.optLong("e"), obj.optString("t"))
        }.takeIf { it.isNotEmpty() }
    }

    private fun writeCache(file: File, cues: List<AiCue>) {
        val array = JSONArray()
        cues.forEach { cue ->
            array.put(
                JSONObject()
                    .put("s", cue.startMs)
                    .put("e", cue.endMs)
                    .put("t", cue.text)
            )
        }
        file.writeText(array.toString())
    }
}
