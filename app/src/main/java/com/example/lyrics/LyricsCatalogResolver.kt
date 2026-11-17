package com.example.lyrics

import com.example.network.BridgeHttpClient
import com.example.network.HostCooldownGuard
import com.example.network.NetworkCategory
import com.example.network.NetworkLogStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

data class LyricWord(
    val startMs: Long,
    val text: String
)

data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

data class LyricsInterlude(
    val startMs: Long,
    val endMs: Long
)

enum class LyricsSyncMode {
    WORD_SYNCED,
    LINE_SYNCED,
    UNSYNCED
}

data class LyricsHypothesis(
    val artist: String,
    val title: String,
    val isSplitHypothesis: Boolean,
    val label: String
)

data class LyricsQueryPlan(
    val rawArtist: String,
    val rawTitle: String,
    val cleanArtist: String,
    val cleanTitle: String,
    val featArtists: List<String>,
    val originalModifiers: Set<String>,
    val hypotheses: List<LyricsHypothesis>
)

data class LyricsCandidate(
    val id: String,
    val source: String,
    val artist: String,
    val title: String,
    val durationSec: Int,
    val synced: Boolean,
    val syncedLyrics: String,
    val plainLyrics: String,
    val lines: List<LyricsLine>,
    val score: Float,
    val titleSimilarity: Float,
    val artistSimilarity: Float
) {
    val syncMode: LyricsSyncMode
        get() = when {
            synced && lines.any { it.words.isNotEmpty() } -> LyricsSyncMode.WORD_SYNCED
            synced && lines.isNotEmpty() -> LyricsSyncMode.LINE_SYNCED
            else -> LyricsSyncMode.UNSYNCED
        }

    fun toLyricsResult(
        candidates: List<LyricsCandidate>,
        providerStatuses: Map<String, String>
    ): LyricsResult {
        return LyricsResult(
            found = true,
            source = source,
            artist = artist,
            title = title,
            synced = synced && lines.isNotEmpty(),
            syncedLyrics = syncedLyrics,
            plainLyrics = plainLyrics,
            lines = lines,
            candidates = candidates,
            selectedCandidateId = id,
            providerStatuses = providerStatuses
        )
    }
}

data class LyricsResult(
    val found: Boolean,
    val source: String,
    val artist: String,
    val title: String,
    val synced: Boolean,
    val syncedLyrics: String,
    val plainLyrics: String,
    val lines: List<LyricsLine>,
    val candidates: List<LyricsCandidate> = emptyList(),
    val selectedCandidateId: String = "",
    val providerStatuses: Map<String, String> = emptyMap()
) {
    val syncMode: LyricsSyncMode
        get() = when {
            lines.any { it.words.isNotEmpty() } -> LyricsSyncMode.WORD_SYNCED
            lines.isNotEmpty() -> LyricsSyncMode.LINE_SYNCED
            else -> LyricsSyncMode.UNSYNCED
        }

    fun toJsonString(): String {
        val linesJson = lines.joinToString(separator = ",", prefix = "[", postfix = "]") { line ->
            """{"time_ms":${line.timeMs},"text":"${escapeJson(line.text)}"}"""
        }
        return """{"found":$found,"source":"${escapeJson(source)}","artist":"${escapeJson(artist)}","title":"${escapeJson(title)}","synced":$synced,"synced_lyrics":"${escapeJson(syncedLyrics)}","plain_lyrics":"${escapeJson(plainLyrics)}","lines":$linesJson}"""
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/**
 * LyricsSearchEngine v2:
 * - Multi-hypothesis SoundCloud/VK/YouTube metadata parser (junk strip, feat extraction, L-R & R-L splits)
 * - Synced-First parallel search: LRCLIB (User-Agent + /get -> /search) + NetEase (top-8 prefilter + lrc/yrc) + KuGou (top-8 + base64 LRC)
 * - Fallback Hypothesis 3: iTunes / Deezer canonicalization (±15s) if local hypotheses yielded no synced LRC
 * - Plain fallback (LRCLIB/NetEase/KuGou plain -> Genius -> Lyrics.ovh) only when no valid synced LRC exists
 * - Strict anti-false-positive scoring: ±15s hard duration cutoff, symmetric Dice + Levenshtein, junk modifier penalty, >= 3 timed lines
 * - Top-5 candidates retained for instant manual switching synced between Player UI & HTTP /lyrics
 */
object LyricsCatalogResolver {

    private const val LRCLIB_USER_AGENT = "ClawdBridge/3.2 (https://github.com/tsobbb/Clawd-Bridge)"
    private const val MAX_CACHE_ENTRIES = 48
    private const val MAX_TOP_CANDIDATES = 5
    private const val MIN_SYNCED_LINES = 3

    private val VERSION_MODIFIERS = listOf(
        "remix", "slowed", "sped up", "speed up", "nightcore",
        "instrumental", "acoustic", "live", "8d", "bass boosted", "reverb"
    )

    private val PAREN_JUNK_REGEX = Regex(
        """[\(\[]\s*(?:prod\.?|produced\s+by|w/|with|feat\.?|ft\.?|featuring|official(?:\s+(?:audio|video|music\s+video|visualizer|lyric\s+video))?|audio|video|visualizer|lyrics?|slowed(?:\s*\+?\s*reverb)?|reverb|sped\s*up|speed\s*up|nightcore|explicit|clean|hd|hq|4k)[^)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_FEAT_PROD_REGEX = Regex(
        """\b(?:prod\.?\s*(?:by)?|feat\.?|ft\.?|featuring)\s+[^-–—|(]+""",
        RegexOption.IGNORE_CASE
    )
    private val EMOJI_AND_SYMBOL_REGEX = Regex("""[\p{So}\p{Cn}\uFE0F\u200D]""")
    private val SEPARATORS = listOf(" — ", " – ", " - ", " | ")
    private val META_CREDIT_LINE_REGEX = Regex(
        """^(?:作词|作曲|编曲|制作人?|混音|母带|录音|和声|吉他|贝斯|鼓|弦乐|监制|出品|词|曲|Arranger|Producer|Lyricist|Composer)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )

    private data class CachedLyricsEntry(
        val trackDurationSec: Int,
        val result: LyricsResult
    )

    private val cacheLock = Any()
    private val lyricsCache = object : LinkedHashMap<String, CachedLyricsEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyricsEntry>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    // Track-level manual overrides so Player UI & HTTP /lyrics stay in sync until track changes
    private val manualOverrides = ConcurrentHashMap<String, LyricsCandidate>()

    private fun buildTrackIdentityKey(rawArtist: String, rawTitle: String): String {
        return "${normalizeForMatch(rawArtist)}|${normalizeForMatch(rawTitle)}"
    }

    fun setManualOverride(rawArtist: String, rawTitle: String, candidate: LyricsCandidate) {
        val key = buildTrackIdentityKey(rawArtist, rawTitle)
        if (key != "|") {
            manualOverrides[key] = candidate
        }
    }

    fun clearManualOverride(rawArtist: String, rawTitle: String) {
        val key = buildTrackIdentityKey(rawArtist, rawTitle)
        manualOverrides.remove(key)
    }

    fun clearAllManualOverrides() {
        manualOverrides.clear()
    }

    // ---- 1. Smart Metadata Parser & Hypothesis Generator ----

    internal fun buildQueryPlan(rawArtist: String, rawTitle: String): LyricsQueryPlan {
        val combinedOrig = "$rawArtist $rawTitle".lowercase(Locale.US)
        val originalModifiers = VERSION_MODIFIERS.filter { mod ->
            Regex("""\b${Regex.escape(mod)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(combinedOrig)
        }.toSet()

        val featArtists = linkedSetOf<String>()
        extractFeatArtists(rawArtist, featArtists)
        extractFeatArtists(rawTitle, featArtists)

        val cleanArtist = stripMetadataJunk(rawArtist)
        val rawCleanTitle = stripMetadataJunk(rawTitle)
        val (coreTitle, titlePlusCollabs) = splitTitleAndInlineCollaborators(rawCleanTitle)
        titlePlusCollabs.forEach { featArtists.add(it) }

        val effectiveCleanTitle = coreTitle.ifBlank { rawCleanTitle }

        // Extract individual artists from multi-artist strings (e.g. "lfy,дмитрий уткин")
        val artistParts = splitArtistParts(cleanArtist)
        artistParts.drop(1).forEach { featArtists.add(it) }

        val hypotheses = mutableListOf<LyricsHypothesis>()
        val seenKeys = hashSetOf<String>()

        fun addHypothesis(artist: String, title: String, isSplit: Boolean, label: String) {
            val a = artist.trim()
            val t = title.trim()
            if (t.isBlank() && a.isBlank()) return
            val key = "${normalizeForMatch(a)}|${normalizeForMatch(t)}|$isSplit"
            if (seenKeys.add(key)) {
                hypotheses.add(LyricsHypothesis(artist = a, title = t, isSplitHypothesis = isSplit, label = label))
            }
        }

        // Hypothesis 2 (SoundCloud/YouTube split "Left - Right") checked first alongside H1
        val titleForSplit = stripJunkPreserveSeparator(rawTitle)
        val splitPair = splitTitleArtistPair(titleForSplit)
        if (splitPair != null) {
            val (left, right) = splitPair
            val cleanLeftRaw = stripMetadataJunk(left)
            val cleanRightRaw = stripMetadataJunk(right)
            val (coreLeft, leftCollabs) = splitTitleAndInlineCollaborators(cleanLeftRaw)
            val (coreRight, rightCollabs) = splitTitleAndInlineCollaborators(cleanRightRaw)
            leftCollabs.forEach { featArtists.add(it) }
            rightCollabs.forEach { featArtists.add(it) }

            val effLeft = coreLeft.ifBlank { cleanLeftRaw }
            val effRight = coreRight.ifBlank { cleanRightRaw }
            if (effLeft.isNotBlank() && effRight.isNotBlank()) {
                // H2a: (Left = Artist, Right = Title)
                addHypothesis(cleanLeftRaw, effRight, isSplit = true, label = "H2(L-R)")
                // H2b: (Right = Artist, Left = Title)
                addHypothesis(cleanRightRaw, effLeft, isSplit = true, label = "H2(R-L)")
            }
        }

        // Hypothesis 1: Prioritize clean (primaryArtist, coreTitle) and secondary co-artist hypotheses for multi-artist / + tracks
        if (artistParts.size > 1 && effectiveCleanTitle.isNotBlank()) {
            addHypothesis(artistParts.first(), effectiveCleanTitle, isSplit = false, label = "H1(Primary)")
            addHypothesis(artistParts[1], effectiveCleanTitle, isSplit = false, label = "H1(Secondary)")
            val extraCollab = featArtists.firstOrNull { feat ->
                artistParts.take(2).none { normalizeForMatch(it) == normalizeForMatch(feat) }
            }
            if (!extraCollab.isNullOrBlank()) {
                addHypothesis(extraCollab, effectiveCleanTitle, isSplit = false, label = "H1(Feat)")
            }
            addHypothesis(artistParts.joinToString(" "), effectiveCleanTitle, isSplit = false, label = "H1")
        } else {
            addHypothesis(cleanArtist, effectiveCleanTitle, isSplit = false, label = "H1")
            if (titlePlusCollabs.isNotEmpty() && effectiveCleanTitle.isNotBlank()) {
                addHypothesis(titlePlusCollabs.first(), effectiveCleanTitle, isSplit = false, label = "H1(Feat)")
            }
        }

        // Preserve unsplit rawCleanTitle as lower-priority fallback in case '+' is part of the real song name
        if (coreTitle.isNotBlank() && normalizeForMatch(coreTitle) != normalizeForMatch(rawCleanTitle)) {
            addHypothesis(cleanArtist, rawCleanTitle, isSplit = false, label = "H1(RawTitle)")
        }

        return LyricsQueryPlan(
            rawArtist = rawArtist,
            rawTitle = rawTitle,
            cleanArtist = cleanArtist,
            cleanTitle = effectiveCleanTitle,
            featArtists = featArtists.toList(),
            originalModifiers = originalModifiers,
            hypotheses = hypotheses
        )
    }

    private fun extractFeatArtists(input: String, out: MutableSet<String>) {
        val parenFeat = Regex("""[\(\[]\s*(?:feat\.?|ft\.?|featuring|w/|with)\s+([^)\]]+)[\)\]]""", RegexOption.IGNORE_CASE)
        parenFeat.findAll(input).forEach { m ->
            splitArtistParts(stripJunkPreserveSeparator(m.groupValues[1])).forEach { part ->
                if (part.isNotBlank()) out.add(part)
            }
        }
        val inlineFeat = Regex("""\b(?:feat\.?|ft\.?|featuring)\s+([^-–—|(\[]+)""", RegexOption.IGNORE_CASE)
        inlineFeat.findAll(input).forEach { m ->
            splitArtistParts(stripJunkPreserveSeparator(m.groupValues[1])).forEach { part ->
                if (part.isNotBlank()) out.add(part)
            }
        }
    }

    internal fun splitTitleAndInlineCollaborators(titleSegment: String): Pair<String, List<String>> {
        if (titleSegment.isBlank()) return "" to emptyList()
        val match = Regex(
            """^(.+?)\s+(?:\+\s*([^+].+)|(?:w/|with)\s+(.+))$""",
            RegexOption.IGNORE_CASE
        ).find(titleSegment.trim()) ?: return titleSegment.trim() to emptyList()

        val core = match.groupValues[1].trim()
        val rawCollab = (match.groupValues[2].ifBlank { match.groupValues[3] }).trim()
        if (core.isBlank() || rawCollab.isBlank()) return titleSegment.trim() to emptyList()

        val parts = splitArtistParts(rawCollab).filter { part ->
            val low = part.lowercase(Locale.US)
            low !in VERSION_MODIFIERS && low.any { it.isLetter() }
        }
        if (parts.isEmpty()) return titleSegment.trim() to emptyList()
        return core to parts
    }

    internal fun stripMetadataJunk(raw: String): String {
        return raw
            .replace(EMOJI_AND_SYMBOL_REGEX, " ")
            .replace(PAREN_JUNK_REGEX, " ")
            .replace(TRAILING_FEAT_PROD_REGEX, " ")
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""[«»"“”„‟'`]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '—', '–', '|', ',')
            .trim()
    }

    private fun stripJunkPreserveSeparator(raw: String): String {
        return raw
            .replace(EMOJI_AND_SYMBOL_REGEX, " ")
            .replace(PAREN_JUNK_REGEX, " ")
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""[«»"“”„‟'`]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    internal fun splitTitleArtistPair(title: String): Pair<String, String>? {
        for (sep in SEPARATORS) {
            val idx = title.indexOf(sep)
            if (idx in 2 until (title.length - sep.length - 1)) {
                val left = title.substring(0, idx).trim()
                val right = title.substring(idx + sep.length).trim()
                if (left.isNotBlank() && right.isNotBlank()) {
                    return left to right
                }
            }
        }
        return null
    }

    internal fun splitArtistParts(rawArtist: String): List<String> {
        if (rawArtist.isBlank()) return emptyList()
        // Strip parenthetical transliterations/notes before splitting (e.g. "дмитрий уткин (dmitry utkin), semmmyq")
        val withoutParens = rawArtist
            .replace(Regex("""[\(\[][^)\]]*[\)\]]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { rawArtist }
        return withoutParens
            .split(Regex("""\s*(?:,|&|\+|×|/|\bx\b|\bfeat\.?\b|\bft\.?\b|\bw/\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.length >= 2 || it.any { ch -> ch.isLetterOrDigit() } }
            .distinct()
    }

    // ---- 2. String Normalization, Symmetric Dice & Levenshtein Similarity ----

    internal fun normalizeForMatch(s: String): String {
        if (s.isBlank()) return ""
        return s.lowercase(Locale.US)
            .replace('ё', 'е')
            .replace(Regex("""[\(\[][^)\]]*[\)\]]"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    internal fun computeLevenshteinSimilarity(aRaw: String, bRaw: String): Float {
        val a = normalizeForMatch(aRaw)
        val b = normalizeForMatch(bRaw)
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f

        val n = a.length
        val m = b.length
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        val dist = prev[m]
        return (1f - dist.toFloat() / max(n, m).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Symmetric Dice / F1 token similarity:
     * 2 * |A ∩ B| / (|A| + |B|). Prevents single-word hits ("EDM") from scoring 1.0 on long titles.
     */
    internal fun computeSymmetricTokenSimilarity(aRaw: String, bRaw: String): Float {
        val aTokens = normalizeForMatch(aRaw).split(" ").filter { it.isNotBlank() }.toSet()
        val bTokens = normalizeForMatch(bRaw).split(" ").filter { it.isNotBlank() }.toSet()
        if (aTokens.isEmpty() && bTokens.isEmpty()) return 1f
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0f
        val intersection = aTokens.intersect(bTokens).size
        if (intersection == 0) return 0f
        return (2f * intersection.toFloat()) / (aTokens.size + bTokens.size).toFloat()
    }

    internal fun computeFieldSimilarity(expected: String, candidate: String): Float {
        val normExp = normalizeForMatch(expected)
        val normCand = normalizeForMatch(candidate)
        if (normExp.isBlank() || normCand.isBlank()) return 0f
        if (normExp == normCand) return 1f
        return maxOf(
            computeLevenshteinSimilarity(normExp, normCand),
            computeSymmetricTokenSimilarity(normExp, normCand)
        )
    }

    internal fun computeArtistSimilarityAgainstHypothesis(
        hypothesis: LyricsHypothesis,
        featArtists: List<String>,
        candidateArtist: String
    ): Float {
        val candParts = splitArtistParts(candidateArtist).ifEmpty { listOf(candidateArtist) }
        val hypParts = splitArtistParts(hypothesis.artist).ifEmpty {
            if (hypothesis.artist.isNotBlank()) listOf(hypothesis.artist) else emptyList()
        }
        val expectedParts = if (hypothesis.isSplitHypothesis) {
            // Nuance 1: strictly compare against this split hypothesis's artist parts only (ignore noisy rawArtist)
            hypParts
        } else {
            (hypParts + featArtists).distinct()
        }

        if (expectedParts.isEmpty()) return 0f

        var best = computeFieldSimilarity(hypothesis.artist, candidateArtist)
        for (expPart in expectedParts) {
            for (candPart in candParts) {
                val sim = computeFieldSimilarity(expPart, candPart)
                // Do NOT penalize when at least one key artist strongly matches (>= 0.85f) despite extra feats/collabs
                val weighted = if (sim < 0.85f && (expectedParts.size > 1 || candParts.size > 1)) {
                    sim * 0.96f
                } else {
                    sim
                }
                if (weighted > best) best = weighted
            }
        }
        return best.coerceIn(0f, 1f)
    }

    // ---- 3. Scoring & Anti-False-Positive Guard ----

    internal data class MatchEvaluation(
        val accepted: Boolean,
        val score: Float,
        val titleSim: Float,
        val artistSim: Float,
        val bestHypothesis: LyricsHypothesis
    )

    internal fun evaluateCandidateMetadata(
        plan: LyricsQueryPlan,
        trackDurationSec: Int,
        candidateArtist: String,
        candidateTitle: String,
        candidateDurationSec: Int,
        requireStrictNoDurationThresholds: Boolean
    ): MatchEvaluation? {
        if (candidateTitle.isBlank()) return null

        // Hard duration filter: ±15 seconds across ALL sources when both durations are known
        var durationBonus = 0f
        if (trackDurationSec > 0 && candidateDurationSec > 0) {
            val diff = abs(candidateDurationSec - trackDurationSec)
            if (diff > 15) return null
            durationBonus = when {
                diff <= 2 -> 40f
                diff <= 7 -> 25f
                else -> 10f
            }
        }

        val hasEffectiveDuration = (trackDurationSec > 0 && candidateDurationSec > 0)
        val minTitle = if (requireStrictNoDurationThresholds || !hasEffectiveDuration) 0.82f else 0.72f
        val minArtist = if (requireStrictNoDurationThresholds || !hasEffectiveDuration) 0.72f else 0.55f

        var bestEval: MatchEvaluation? = null
        for (hyp in plan.hypotheses) {
            if (hyp.title.isBlank()) continue
            val tSim = computeFieldSimilarity(hyp.title, candidateTitle)
            if (tSim < minTitle) continue

            val aSim = if (hyp.artist.isBlank()) {
                // Only allowed without artist when duration confirms the exact track on a duration-backed source
                if (!requireStrictNoDurationThresholds && hasEffectiveDuration && tSim >= 0.90f) 0.60f else 0f
            } else {
                computeArtistSimilarityAgainstHypothesis(hyp, plan.featArtists, candidateArtist)
            }
            if (aSim < minArtist) continue

            // Anti-junk modifier check (remix, slowed, sped up, nightcore, live, acoustic, instrumental)
            val candCombined = "$candidateArtist $candidateTitle".lowercase(Locale.US)
            var modifierPenalty = 0f
            for (mod in VERSION_MODIFIERS) {
                val candHas = Regex("""\b${Regex.escape(mod)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(candCombined)
                if (candHas && mod !in plan.originalModifiers) {
                    modifierPenalty -= 40f
                    break
                }
            }

            val totalScore = (tSim * 100f) + (aSim * 75f) + durationBonus + modifierPenalty
            val candidateEval = MatchEvaluation(
                accepted = true,
                score = totalScore,
                titleSim = tSim,
                artistSim = aSim,
                bestHypothesis = hyp
            )
            if (bestEval == null || candidateEval.score > bestEval.score) {
                bestEval = candidateEval
            }
        }
        return bestEval
    }

    // ---- 4. Public Entry Point & Orchestration ----

    fun resolveLyrics(
        rawArtist: String,
        rawTitle: String,
        trackDurationMs: Long = 0L,
        forceRefresh: Boolean = false,
        preferredProvider: String? = null
    ): LyricsResult {
        val trackDurationSec = if (trackDurationMs > 0L) (trackDurationMs / 1000L).toInt() else 0
        val plan = buildQueryPlan(rawArtist, rawTitle)
        if (plan.cleanTitle.isBlank() && plan.cleanArtist.isBlank() && plan.hypotheses.isEmpty()) {
            return emptyNotFound(rawArtist, rawTitle)
        }

        val providerKey = preferredProvider?.lowercase(Locale.US)?.takeIf { it.isNotBlank() && it != "auto" }
        val trackIdentity = buildTrackIdentityKey(rawArtist, rawTitle)
        val durBucket = if (trackDurationSec > 0) (trackDurationSec / 5).toString() else "nodur"
        val cacheKey = "$trackIdentity|$durBucket|${providerKey ?: "auto"}"

        // Nuance 4: manual provider selection or forceRefresh clears previous manual override
        if (providerKey != null || forceRefresh) {
            clearManualOverride(rawArtist, rawTitle)
        }

        // Check active manual override (applies in Auto mode for both Player UI & HTTP /lyrics)
        if (providerKey == null && !forceRefresh) {
            manualOverrides[trackIdentity]?.let { overridden ->
                val cachedEntry = synchronized(cacheLock) { lyricsCache[cacheKey]?.result }
                val siblingCandidates = cachedEntry?.candidates.orEmpty().ifEmpty { listOf(overridden) }
                val statuses = cachedEntry?.providerStatuses.orEmpty()
                return overridden.toLyricsResult(siblingCandidates, statuses)
            }
        }

        if (!forceRefresh) {
            synchronized(cacheLock) {
                lyricsCache[cacheKey]?.let { entry ->
                    // Nuance 3: never return cached negative result if duration is now known and cached entry lacked duration
                    if (entry.result.found || trackDurationSec <= 0 || entry.trackDurationSec > 0) {
                        return entry.result
                    }
                }
            }
        }

        val statuses = LinkedHashMap<String, String>()
        val allCandidates = mutableListOf<LyricsCandidate>()

        if (providerKey != null) {
            val singlePool = runSingleProvider(providerKey, plan, trackDurationSec, statuses)
            allCandidates.addAll(singlePool)
            return finalizeResult(
                cacheKey = cacheKey,
                trackIdentity = trackIdentity,
                trackDurationSec = trackDurationSec,
                rawArtist = plan.cleanArtist.ifBlank { rawArtist },
                rawTitle = plan.cleanTitle.ifBlank { rawTitle },
                candidates = allCandidates,
                statuses = statuses,
                allowNegativeCache = trackDurationSec > 0
            )
        }

        // Phase 1: Parallel Synced Providers (LRCLIB + NetEase + KuGou) on local hypotheses
        runPhase1SyncedParallel(
            plan = plan,
            trackDurationSec = trackDurationSec,
            statuses = statuses,
            outCandidates = allCandidates
        )

        val hasValidSynced = allCandidates.any { it.synced && it.lines.size >= MIN_SYNCED_LINES }

        // Hypothesis 3: Canonicalization via iTunes / Deezer ONLY if Phase 1 found NO synced LRC
        if (!hasValidSynced) {
            val canonicalHyp = resolveCanonicalMetadataHypothesis(plan, trackDurationSec, statuses)
            if (canonicalHyp != null) {
                val canonicalPlan = plan.copy(hypotheses = listOf(canonicalHyp))
                runPhase1SyncedParallel(
                    plan = canonicalPlan,
                    trackDurationSec = trackDurationSec,
                    statuses = statuses,
                    outCandidates = allCandidates
                )
            }
        }

        val hasSyncedAfterCanonical = allCandidates.any { it.synced && it.lines.size >= MIN_SYNCED_LINES }

        // Phase 2: Plain Fallback — Genius first, Lyrics.ovh strictly as last resort when candidates.isEmpty()
        if (!hasSyncedAfterCanonical) {
            val geniusCandidates = runCatching {
                fetchGeniusCandidates(plan, trackDurationSec, statuses)
            }.getOrDefault(emptyList())
            allCandidates.addAll(geniusCandidates)

            if (allCandidates.isEmpty()) {
                val ovhCandidates = runCatching {
                    fetchLyricsOvhCandidates(plan, trackDurationSec, statuses)
                }.getOrDefault(emptyList())
                allCandidates.addAll(ovhCandidates)
            }
        }

        return finalizeResult(
            cacheKey = cacheKey,
            trackIdentity = trackIdentity,
            trackDurationSec = trackDurationSec,
            rawArtist = plan.cleanArtist.ifBlank { rawArtist },
            rawTitle = plan.cleanTitle.ifBlank { rawTitle },
            candidates = allCandidates,
            statuses = statuses,
            allowNegativeCache = trackDurationSec > 0
        )
    }

    private fun runSingleProvider(
        providerKey: String,
        plan: LyricsQueryPlan,
        trackDurationSec: Int,
        statuses: MutableMap<String, String>
    ): List<LyricsCandidate> {
        return when (providerKey) {
            "lrclib" -> fetchLrcLibCandidates(plan, trackDurationSec, statuses)
            "netease" -> fetchNetEaseCandidates(plan, trackDurationSec, statuses)
            "kugou" -> fetchKuGouCandidates(plan, trackDurationSec, statuses)
            "genius" -> fetchGeniusCandidates(plan, trackDurationSec, statuses)
            "lyrics_ovh" -> fetchLyricsOvhCandidates(plan, trackDurationSec, statuses)
            else -> emptyList()
        }
    }

    private fun runPhase1SyncedParallel(
        plan: LyricsQueryPlan,
        trackDurationSec: Int,
        statuses: MutableMap<String, String>,
        outCandidates: MutableList<LyricsCandidate>
    ) {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val fLrc = pool.submit(Callable { fetchLrcLibCandidates(plan, trackDurationSec, statuses) })
            val fNet = pool.submit(Callable { fetchNetEaseCandidates(plan, trackDurationSec, statuses) })
            val fKugou = pool.submit(Callable { fetchKuGouCandidates(plan, trackDurationSec, statuses) })

            runCatching { fLrc.get(8, TimeUnit.SECONDS) }.getOrNull()?.let { outCandidates.addAll(it) }
            runCatching { fNet.get(7, TimeUnit.SECONDS) }.getOrNull()?.let { outCandidates.addAll(it) }
            runCatching { fKugou.get(6, TimeUnit.SECONDS) }.getOrNull()?.let { outCandidates.addAll(it) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun finalizeResult(
        cacheKey: String,
        trackIdentity: String,
        trackDurationSec: Int,
        rawArtist: String,
        rawTitle: String,
        candidates: List<LyricsCandidate>,
        statuses: Map<String, String>,
        allowNegativeCache: Boolean
    ): LyricsResult {
        val ranked = rankAndDeduplicateCandidates(candidates).take(MAX_TOP_CANDIDATES)
        val statusSnapshot = LinkedHashMap(statuses)
        if (ranked.isEmpty()) {
            val emptyRes = emptyNotFound(rawArtist, rawTitle, statusSnapshot)
            if (allowNegativeCache) {
                cacheResult(cacheKey, CachedLyricsEntry(trackDurationSec, emptyRes))
            }
            return emptyRes
        }

        val overrideCand = manualOverrides[trackIdentity]
        val chosen = overrideCand ?: ranked.first()
        val finalRes = chosen.toLyricsResult(ranked, statusSnapshot)
        cacheResult(cacheKey, CachedLyricsEntry(trackDurationSec, finalRes))
        return finalRes
    }

    internal fun rankAndDeduplicateCandidates(candidates: List<LyricsCandidate>): List<LyricsCandidate> {
        val sorted = candidates.sortedWith(
            compareByDescending<LyricsCandidate> { candidate ->
                when (candidate.syncMode) {
                    LyricsSyncMode.WORD_SYNCED -> 2
                    LyricsSyncMode.LINE_SYNCED -> 1
                    LyricsSyncMode.UNSYNCED -> 0
                }
            }.thenByDescending { it.score }
        )
        val seen = hashSetOf<String>()
        val result = mutableListOf<LyricsCandidate>()
        for (c in sorted) {
            val dedupKey = "${c.source}|${normalizeForMatch(c.artist)}|${normalizeForMatch(c.title)}|${c.syncMode}|${c.durationSec}"
            if (seen.add(dedupKey)) {
                result.add(c)
            }
        }
        return result
    }

    private fun emptyNotFound(
        artist: String,
        title: String,
        statuses: Map<String, String> = emptyMap()
    ) = LyricsResult(
        found = false,
        source = "none",
        artist = artist,
        title = title,
        synced = false,
        syncedLyrics = "",
        plainLyrics = "",
        lines = emptyList(),
        candidates = emptyList(),
        selectedCandidateId = "",
        providerStatuses = statuses
    )

    private fun cacheResult(key: String, entry: CachedLyricsEntry) {
        synchronized(cacheLock) {
            lyricsCache[key] = entry
        }
    }

    // ---- 5. Provider Implementations ----

    private fun checkProviderCooldownStatus(category: NetworkCategory): String? {
        val info = HostCooldownGuard.checkCooldown(category.cooldownBucket) ?: return null
        return "HTTP ${info.reasonCode} (пауза до ${NetworkLogStore.formatTimestampHHmm(info.untilMs)})"
    }

    private fun fetchLrcLibCandidates(
        plan: LyricsQueryPlan,
        trackDurationSec: Int,
        statuses: MutableMap<String, String>
    ): List<LyricsCandidate> {
        checkProviderCooldownStatus(NetworkCategory.LRCLIB)?.let {
            statuses["LRCLIB"] = it
            return emptyList()
        }
        val headers = mapOf("User-Agent" to LRCLIB_USER_AGENT)
        val collected = mutableListOf<LyricsCandidate>()
        var lastNote = "200 (0 совп.)"
        var hasFoundSynced = false

        try {
            // Step A: Try /api/get first on top hypotheses (fast exact hit)
            for (hyp in plan.hypotheses.take(4)) {
                if (hyp.artist.isBlank() || hyp.title.isBlank()) continue
                val durParam = if (trackDurationSec > 0) "&duration=$trackDurationSec" else ""
                val getUrl = "https://lrclib.net/api/get?artist_name=${urlEncode(hyp.artist)}&track_name=${urlEncode(hyp.title)}$durParam"
                val res = BridgeHttpClient.getTextDetailed(getUrl, useCatalogClient = true, headers = headers, maxRetries = 0)
                if (res.isCooldown || res.httpCode == 403 || res.httpCode == 429) {
                    statuses["LRCLIB"] = res.errorLabel ?: "HTTP ${res.httpCode}"
                    return collected
                }
                if (res.errorLabel != null) {
                    lastNote = res.errorLabel
                    continue
                }
                val body = res.body ?: continue
                runCatching { JSONObject(body) }.getOrNull()?.let { obj ->
                    parseLrcLibCandidate(obj, plan, trackDurationSec, idx = 0)?.let { cand ->
                        collected.add(cand)
                        if (cand.synced) hasFoundSynced = true
                    }
                }
                if (hasFoundSynced) break
            }

            // Step B: ALWAYS run /api/search if /api/get yielded no synced LRC (or 404)
            if (!hasFoundSynced) {
                for (hyp in plan.hypotheses.take(4)) {
                    if (hyp.title.isBlank()) continue
                    val searchUrls = buildList {
                        if (hyp.artist.isNotBlank()) {
                            add("https://lrclib.net/api/search?track_name=${urlEncode(hyp.title)}&artist_name=${urlEncode(hyp.artist)}")
                            add("https://lrclib.net/api/search?q=${urlEncode("${hyp.artist} ${hyp.title}")}")
                        } else {
                            add("https://lrclib.net/api/search?q=${urlEncode(hyp.title)}")
                        }
                    }
                    for (url in searchUrls) {
                        val res = BridgeHttpClient.getTextDetailed(url, useCatalogClient = true, headers = headers, maxRetries = 0)
                        if (res.isCooldown || res.httpCode == 403 || res.httpCode == 429) {
                            statuses["LRCLIB"] = res.errorLabel ?: "HTTP ${res.httpCode}"
                            return collected
                        }
                        if (res.errorLabel != null) {
                            lastNote = res.errorLabel
                            continue
                        }
                        val body = res.body ?: continue
                        val arr = runCatching { JSONArray(body) }.getOrNull() ?: continue
                        for (i in 0 until arr.length().coerceAtMost(20)) {
                            val item = arr.optJSONObject(i) ?: continue
                            parseLrcLibCandidate(item, plan, trackDurationSec, idx = i)?.let { cand ->
                                collected.add(cand)
                                if (cand.synced) hasFoundSynced = true
                            }
                        }
                        if (hasFoundSynced) break
                    }
                    if (hasFoundSynced) break
                }
            }
        } catch (t: Throwable) {
            lastNote = t.javaClass.simpleName
        }

        val syncedCount = collected.count { it.synced }
        statuses["LRCLIB"] = when {
            syncedCount > 0 -> "200 ($syncedCount sync)"
            collected.isNotEmpty() -> "200 (${collected.size} plain)"
            else -> lastNote
        }
        return collected
    }

    private fun parseLrcLibCandidate(
        obj: JSONObject,
        plan: LyricsQueryPlan,
        trackDurationSec: Int,
        idx: Int
    ): LyricsCandidate? {
        if (obj.optBoolean("instrumental", false)) return null
        val itemArtist = obj.optString("artistName").trim()
        val itemTitle = obj.optString("trackName").trim()
        val itemDurationSec = obj.optDouble("duration", 0.0).toInt().coerceAtLeast(0)

        val eval = evaluateCandidateMetadata(
            plan = plan,
            trackDurationSec = trackDurationSec,
            candidateArtist = itemArtist,
            candidateTitle = itemTitle,
            candidateDurationSec = itemDurationSec,
            requireStrictNoDurationThresholds = false
        ) ?: return null

        val syncedRaw = obj.optString("syncedLyrics").takeIf { it != "null" && it.isNotBlank() } ?: ""
        val plainRaw = obj.optString("plainLyrics").takeIf { it != "null" && it.isNotBlank() } ?: ""
        if (syncedRaw.isBlank() && plainRaw.isBlank()) return null

        val lines = if (syncedRaw.isNotBlank()) parseLrcLines(syncedRaw) else emptyList()
        val validSynced = lines.size >= MIN_SYNCED_LINES
        val effectivePlain = plainRaw.ifBlank { lines.joinToString("\n") { it.text } }
        if (!validSynced && effectivePlain.isBlank()) return null

        val id = "lrclib_${obj.optLong("id", idx.toLong())}_${if (validSynced) "s" else "p"}"
        return LyricsCandidate(
            id = id,
            source = "lrclib",
            artist = itemArtist.ifBlank { eval.bestHypothesis.artist },
            title = itemTitle.ifBlank { eval.bestHypothesis.title },
            durationSec = itemDurationSec,
            synced = validSynced,
            syncedLyrics = if (validSynced) syncedRaw else "",
            plainLyrics = effectivePlain,
            lines = if (validSynced) lines else emptyList(),
            score = eval.score + (if (validSynced) 12f else 0f),
            titleSimilarity = eval.titleSim,
            artistSimilarity = eval.artistSim
        )
    }

    private fun fetchNetEaseCandidates(
        plan: LyricsQueryPlan,
        trackDurationSec: Int,
        statuses: MutableMap<String, String>
    ): List<LyricsCandidate> {
        checkProviderCooldownStatus(NetworkCategory.NETEASE)?.let {
            statuses["NetEase"] = it
            return emptyList()
        }
        val headers = mapOf(
            "User-Agent" to BridgeHttpClient.BROWSER_USER_AGENT,
            "Referer" to "https://music.163.com/"
        )
        val collected = mutableListOf<LyricsCandidate>()
        var lastNote = "200 (0 совп.)"

        class PreScoredSong(
            val songId: Long,
            val title: String,
            val artist: String,
            val durationSec: Int,
            val eval: MatchEvaluation
        )

        val scoredSongs = mutableListOf<PreScoredSong>()
        val seenIds = hashSetOf<Long>()

        try {
            for (hyp in plan.hypotheses.take(3)) {
                val query = "${hyp.artist} ${hyp.title}".trim()
                if (query.isBlank()) continue
                val res = BridgeHttpClient.getTextDetailed(
                    url = "https://music.163.com/api/search/get/web?s=${urlEncode(query)}&type=1&offset=0&total=true&limit=8",
                    useCatalogClient = true,
                    headers = headers,
                    maxRetries = 0,
                    customCallTimeoutMs = 5500L
                )
                if (res.isCooldown || res.httpCode == 403 || res.httpCode == 429) {
                    statuses["NetEase"] = res.errorLabel ?: "HTTP ${res.httpCode}"
                    return emptyList()
                }
                if (res.errorLabel != null) {
                    lastNote = res.errorLabel
                    continue
                }
                val body = res.body ?: continue
                val songs = runCatching { JSONObject(body).optJSONObject("result")?.optJSONArray("songs") }.getOrNull() ?: continue
                for (i in 0 until songs.length().coerceAtMost(8)) {
                    val song = songs.optJSONObject(i) ?: continue
                    val songId = song.optLong("id", -1L)
                    if (songId <= 0L || !seenIds.add(songId)) continue
                    val songTitle = song.optString("name").trim()
                    val artistsArr = song.optJSONArray("artists")
                    val songArtist = buildList {
                        if (artistsArr != null) {
                            for (aIdx in 0 until artistsArr.length()) {
                                artistsArr.optJSONObject(aIdx)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
                            }
                        }
                    }.joinToString(", ")
                    val songDurationSec = (song.optLong("duration", 0L) / 1000L).toInt().coerceAtLeast(0)
                    val eval = evaluateCandidateMetadata(
                        plan = plan,
                        trackDurationSec = trackDurationSec,
                        candidateArtist = songArtist,
                        candidateTitle = songTitle,
                        candidateDurationSec = songDurationSec,
                        requireStrictNoDurationThresholds = false
                    ) ?: continue
                    scoredSongs.add(PreScoredSong(songId, songTitle, songArtist, songDurationSec, eval))
                }
            }

            // Fetch lyrics ONLY for top 2 scored songs
            val topSongs = scoredSongs.sortedByDescending { it.eval.score }.take(2)
            for (song in topSongs) {
                val lyricUrl = "https://music.163.com/api/song/lyric?id=${song.songId}&lv=1&kv=1&tv=-1&yv=1"
                val lrcRes = BridgeHttpClient.getTextDetailed(
                    url = lyricUrl,
                    useCatalogClient = true,
                    headers = headers,
                    maxRetries = 0,
                    customCallTimeoutMs = 5000L
                )
                if (lrcRes.isCooldown || lrcRes.httpCode == 403 || lrcRes.httpCode == 429) {
                    statuses["NetEase"] = lrcRes.errorLabel ?: "HTTP ${lrcRes.httpCode}"
                    break
                }
                val lyricBody = lrcRes.body ?: continue
                val lyricJson = runCatching { JSONObject(lyricBody) }.getOrNull() ?: continue
                if (lyricJson.optBoolean("nolyric", false) || lyricJson.optBoolean("uncollected", false)) continue

                val yrcRaw = lyricJson.optJSONObject("yrc")?.optString("lyric").orEmpty()
                val lrcRaw = lyricJson.optJSONObject("lrc")?.optString("lyric").orEmpty()

                val yrcLines = if (yrcRaw.isNotBlank()) stripCreditMetaLinesIfBodyExists(parseNetEaseYrc(yrcRaw)) else emptyList()
                val lrcLines = if (lrcRaw.isNotBlank()) stripCreditMetaLinesIfBodyExists(parseLrcLines(lrcRaw)) else emptyList()

                val chosenLines = when {
                    yrcLines.size >= MIN_SYNCED_LINES && yrcLines.any { it.words.isNotEmpty() } -> yrcLines
                    lrcLines.size >= MIN_SYNCED_LINES -> lrcLines
                    else -> emptyList()
                }

                if (chosenLines.isNotEmpty()) {
                    val plain = chosenLines.joinToString("\n") { it.text }
                    collected.add(
                        LyricsCandidate(
                            id = "netease_${song.songId}_s",
                            source = "netease",
                            artist = song.artist.ifBlank { song.eval.bestHypothesis.artist },
                            title = song.title.ifBlank { song.eval.bestHypothesis.title },
                            durationSec = song.durationSec,
                            synced = true,
                            syncedLyrics = if (chosenLines === yrcLines) formatLinesAsLrc(yrcLines) else lrcRaw,
                            plainLyrics = plain,
                            lines = chosenLines,
                            score = song.eval.score + (if (chosenLines === yrcLines) 15f else 8f),
                            titleSimilarity = song.eval.titleSim,
                            artistSimilarity = song.eval.artistSim
                        )
                    )
                } else if (lrcRaw.isNotBlank()) {
                    val cleanedPlain = lrcRaw
                        .replace(Regex("""\[[a-zA-Z]+:[^\]]*]"""), "")
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !META_CREDIT_LINE_REGEX.containsMatchIn(it) }
                        .joinToString("\n")
                    if (cleanedPlain.lines().size >= MIN_SYNCED_LINES) {
                        collected.add(
                            LyricsCandidate(
                                id = "netease_${song.songId}_p",
                                source = "netease",
                                artist = song.artist.ifBlank { song.eval.bestHypothesis.artist },
                                title = song.title.ifBlank { song.eval.bestHypothesis.title },
                                durationSec = song.durationSec,
                                synced = false,
                                syncedLyrics = "",
                                plainLyrics = cleanedPlain,
                                lines = emptyList(),
                                score = song.eval.score,
                                titleSimilarity = song.eval.titleSim,
                                artistSimilarity = song.eval.artistSim
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            lastNote = t.javaClass.simpleName
        }

        val syncedCount = collected.count { it.synced }
        statuses["NetEase"] = when {
            syncedCount > 0 -> "200 ($syncedCount sync)"
            collected.isNotEmpty() -> "200 (${collected.size} plain)"
            else -> lastNote
        }
        return collected
    }

    private fun fetchKuGouCandidates(
        plan: LyricsQueryPlan,
        trackDurationSec: Int,
        statuses: MutableMap<String, String>
    ): List<LyricsCandidate> {
        checkProviderCooldownStatus(NetworkCategory.KUGOU)?.let {
            statuses["KuGou"] = it
            return emptyList()
        }
        val headers = mapOf("User-Agent" to BridgeHttpClient.BROWSER_USER_AGENT)
        val collected = mutableListOf<LyricsCandidate>()
        var lastNote = "200 (0 совп.)"

        class KuGouPreMatch(
            val hash: String,
            val title: String,
            val artist: String,
            val durationSec: Int,
            val eval: MatchEvaluation
        )

        val preMatches = mutableListOf<KuGouPreMatch>()
        val seenHashes = hashSetOf<String>()

        try {
            for (hyp in plan.hypotheses.take(3)) {
                val query = "${hyp.artist} ${hyp.title}".trim()
                if (query.isBlank()) continue
                val searchUrl = "https://mobileservice.kugou.com/api/v3/search/song?format=json&keyword=${urlEncode(query)}&page=1&pagesize=8&showtype=1"
                val res = BridgeHttpClient.getTextDetailed(
                    url = searchUrl,
                    useCatalogClient = true,
                    headers = headers,
                    maxRetries = 0,
                    customCallTimeoutMs = 4500L
                )
                if (res.isCooldown || res.httpCode == 403 || res.httpCode == 429) {
                    statuses["KuGou"] = res.errorLabel ?: "HTTP ${res.httpCode}"
                    return emptyList()
                }
                if (res.errorLabel != null) {
                    lastNote = res.errorLabel
                    continue
                }
                val body = res.body ?: continue
                val infoArr = runCatching { JSONObject(body).optJSONObject("data")?.optJSONArray("info") }.getOrNull() ?: continue
                for (i in 0 until infoArr.length().coerceAtMost(8)) {
                    val item = infoArr.optJSONObject(i) ?: continue
                    val hash = item.optString("hash").trim()
                    if (hash.isBlank() || !seenHashes.add(hash)) continue
                    val candTitle = item.optString("songname").trim()
                    val candArtist = item.optString("singername").trim()
                    val candDurSec = item.optInt("duration", 0).coerceAtLeast(0)
                    val eval = evaluateCandidateMetadata(
                        plan = plan,
                        trackDurationSec = trackDurationSec,
                        candidateArtist = candArtist,
                        candidateTitle = candTitle,
                        candidateDurationSec = candDurSec,
                        requireStrictNoDurationThresholds = false
                    ) ?: continue
                    preMatches.add(KuGouPreMatch(hash, candTitle, candArtist, candDurSec, eval))
                }
            }

            // Only query KuGou lyrics endpoint for top 2 filtered candidates
            for (match in preMatches.sortedByDescending { it.eval.score }.take(2)) {
                val keyword = "${match.artist} - ${match.title}".trim(' ', '-')
                val durMs = (match.durationSec.takeIf { it > 0 } ?: trackDurationSec) * 1000L
                val lrcSearchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${urlEncode(keyword)}&duration=$durMs&hash=${urlEncode(match.hash)}"
                val lrcSearchRes = BridgeHttpClient.getTextDetailed(
                    url = lrcSearchUrl,
                    useCatalogClient = true,
                    headers = headers,
                    maxRetries = 0,
                    customCallTimeoutMs = 4000L
                )
                if (lrcSearchRes.isCooldown) {
                    statuses["KuGou"] = lrcSearchRes.errorLabel ?: "Cooldown"
                    break
                }
                val searchBody = lrcSearchRes.body ?: continue
                val candsArr = runCatching { JSONObject(searchBody).optJSONArray("candidates") }.getOrNull() ?: continue
                val firstLrc = candsArr.optJSONObject(0) ?: continue
                val lrcId = firstLrc.optString("id").trim()
                val accesskey = firstLrc.optString("accesskey").trim()
                if (lrcId.isBlank() || accesskey.isBlank()) continue

                val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${urlEncode(lrcId)}&accesskey=${urlEncode(accesskey)}&fmt=lrc&charset=utf8"
                val dlRes = BridgeHttpClient.getTextDetailed(
                    url = dlUrl,
                    useCatalogClient = true,
                    headers = headers,
                    maxRetries = 0,
                    customCallTimeoutMs = 4000L
                )
                val dlBody = dlRes.body ?: continue
                val base64Content = runCatching { JSONObject(dlBody).optString("content") }.getOrNull().orEmpty()
                val decodedLrc = decodeKuGouBase64Lrc(base64Content) ?: continue
                val parsedLines = stripCreditMetaLinesIfBodyExists(parseLrcLines(decodedLrc))
                if (parsedLines.size >= MIN_SYNCED_LINES) {
                    collected.add(
                        LyricsCandidate(
                            id = "kugou_${match.hash}_s",
                            source = "kugou",
                            artist = match.artist.ifBlank { match.eval.bestHypothesis.artist },
                            title = match.title.ifBlank { match.eval.bestHypothesis.title },
                            durationSec = match.durationSec,
                            synced = true,
                            syncedLyrics = decodedLrc,
                            plainLyrics = parsedLines.joinToString("\n") { it.text },
                            lines = parsedLines,
                            score = match.eval.score + 6f,
                            titleSimilarity = match.eval.titleSim,
                            artistSimilarity = match.eval.artistSim
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            lastNote = t.javaClass.simpleName
        }

        val syncedCount = collected.count { it.synced }
        statuses["KuGou"] = when {
            syncedCount > 0 -> "200 ($syncedCount sync)"
            collected.isNotEmpty() -> "200 (${collected.size} plain)"
            else -> lastNote
        }
        return collected
    }

    /**
     * Hypothesis 3: Canonicalize (artist, title) via iTunes / Deezer with strict ±15s duration verification
     * ONLY when local hypotheses failed to produce any valid synced LRC.
     */
    private fun resolveCanonicalMetadataHypothesis(
        plan: LyricsQueryPlan,
        trackDurationSec: Int,
        statuses: MutableMap<String, String>
    ): LyricsHypothesis? {
        val seedHyp = plan.hypotheses.firstOrNull() ?: return null
        val query = "${seedHyp.artist} ${seedHyp.title}".trim().ifBlank { plan.cleanTitle }
        if (query.isBlank()) return null

        // Try Deezer first (clean artist + title + durationSec)
        if (HostCooldownGuard.checkCooldown(NetworkCategory.DEEZER.cooldownBucket) == null) {
            val dzRes = BridgeHttpClient.getTextDetailed(
                url = "https://api.deezer.com/search?q=${urlEncode(query)}&limit=5",
                useCatalogClient = true,
                maxRetries = 0,
                customCallTimeoutMs = 4500L
            )
            val dzData = dzRes.body?.let { runCatching { JSONObject(it).optJSONArray("data") }.getOrNull() }
            if (dzData != null) {
                for (i in 0 until dzData.length()) {
                    val item = dzData.optJSONObject(i) ?: continue
                    val title = stripMetadataJunk(item.optString("title"))
                    val artist = stripMetadataJunk(item.optJSONObject("artist")?.optString("name").orEmpty())
                    val dur = item.optInt("duration", 0)
                    val eval = evaluateCandidateMetadata(
                        plan = plan,
                        trackDurationSec = trackDurationSec,
                        candidateArtist = artist,
                        candidateTitle = title,
                        candidateDurationSec = dur,
                        requireStrictNoDurationThresholds = false
                    )
                    if (eval != null) {
                        val isNew = plan.hypotheses.none {
                            normalizeForMatch(it.artist) == normalizeForMatch(artist) &&
                                normalizeForMatch(it.title) == normalizeForMatch(title)
                        }
                        if (isNew) {
                            statuses["Канон"] = "Deezer ($artist — $title)"
                            return LyricsHypothesis(artist, title, isSplitHypothesis = false, label = "H3(Deezer)")
                        }
                    }
                }
            }
        }

        // Try iTunes Search fallback
        if (HostCooldownGuard.checkCooldown(NetworkCategory.ITUNES.cooldownBucket) == null) {
            val itRes = BridgeHttpClient.getTextDetailed(
                url = "https://itunes.apple.com/search?term=${urlEncode(query)}&media=music&entity=song&limit=5",
                useCatalogClient = true,
                maxRetries = 0,
                customCallTimeoutMs = 4500L
            )
            val itArr = itRes.body?.let { runCatching { JSONObject(it).optJSONArray("results") }.getOrNull() }
            if (itArr != null) {
                for (i in 0 until itArr.length()) {
                    val item = itArr.optJSONObject(i) ?: continue
                    val title = stripMetadataJunk(item.optString("trackName"))
                    val artist = stripMetadataJunk(item.optString("artistName"))
                    val dur = (item.optLong("trackTimeMillis", 0L) / 1000L).toInt()
                    val eval = evaluateCandidateMetadata(
                        plan = plan,
                        trackDurationSec = trackDurationSec,
                        candidateArtist = artist,
                        candidateTitle = title,
                        candidateDurationSec = dur,
                        requireStrictNoDurationThresholds = false
                    )
                    if (eval != null) {
                        val isNew = plan.hypotheses.none {
                            normalizeForMatch(it.artist) == normalizeForMatch(artist) &&
                                normalizeForMatch(it.title) == normalizeForMatch(title)
                        }
                        if (isNew) {
                            statuses["Канон"] = "iTunes ($artist — $title)"
                            return LyricsHypothesis(artist, title, isSplitHypothesis = false, label = "H3(iTunes)")
                        }
                    }
                }
            }
        }
        return null
    }

    private fun fetchGeniusCandidates(
        plan: LyricsQueryPlan,
        trackDurationSec: Int,
        statuses: MutableMap<String, String>
    ): List<LyricsCandidate> {
        checkProviderCooldownStatus(NetworkCategory.GENIUS)?.let {
            statuses["Genius"] = it
            return emptyList()
        }
        val headers = mapOf("User-Agent" to BridgeHttpClient.BROWSER_USER_AGENT)
        val collected = mutableListOf<LyricsCandidate>()
        var lastNote = "200 (0 совп.)"

        try {
            for (hyp in plan.hypotheses.take(4)) {
                val query = "${hyp.artist} ${hyp.title}".trim()
                if (query.isBlank()) continue
                val res = BridgeHttpClient.getTextDetailed(
                    url = "https://genius.com/api/search/multi?q=${urlEncode(query)}",
                    useCatalogClient = true,
                    headers = headers,
                    maxRetries = 0,
                    customCallTimeoutMs = 5500L
                )
                if (res.isCooldown || res.httpCode == 403 || res.httpCode == 429) {
                    statuses["Genius"] = res.errorLabel ?: "HTTP ${res.httpCode}"
                    return collected
                }
                if (res.errorLabel != null) {
                    lastNote = res.errorLabel
                    continue
                }
                val body = res.body ?: continue
                val sections = runCatching { JSONObject(body).optJSONObject("response")?.optJSONArray("sections") }.getOrNull() ?: continue
                for (s in 0 until sections.length()) {
                    val sec = sections.optJSONObject(s) ?: continue
                    val type = sec.optString("type")
                    if (type != "song" && type != "top_hit") continue
                    val hits = sec.optJSONArray("hits") ?: continue
                    for (h in 0 until hits.length().coerceAtMost(5)) {
                        val hitResult = hits.optJSONObject(h)?.optJSONObject("result") ?: continue
                        val hitTitle = hitResult.optString("title").trim()
                        val hitArtist = hitResult.optString("artist_names").trim()
                            .ifBlank { hitResult.optJSONObject("primary_artist")?.optString("name").orEmpty().trim() }
                        val eval = evaluateCandidateMetadata(
                            plan = plan,
                            trackDurationSec = trackDurationSec,
                            candidateArtist = hitArtist,
                            candidateTitle = hitTitle,
                            candidateDurationSec = 0,
                            requireStrictNoDurationThresholds = true // No duration: strict 0.82 / 0.72!
                        ) ?: continue

                        val pageUrl = hitResult.optString("url").takeIf { it.startsWith("http") }
                            ?: hitResult.optString("path").takeIf { it.startsWith("/") }?.let { "https://genius.com$it" }
                            ?: continue

                        val pageRes = BridgeHttpClient.getTextDetailed(
                            url = pageUrl,
                            useCatalogClient = true,
                            headers = headers,
                            maxRetries = 0,
                            customCallTimeoutMs = 5500L
                        )
                        if (pageRes.isCooldown || pageRes.httpCode == 403 || pageRes.httpCode == 429) {
                            statuses["Genius"] = pageRes.errorLabel ?: "HTTP ${pageRes.httpCode}"
                            return collected
                        }
                        val html = pageRes.body ?: continue
                        val plain = extractGeniusHtmlLyrics(
                            html = html,
                            expectedTitle = hitTitle.ifBlank { eval.bestHypothesis.title }
                        )
                        if (plain.isNotBlank()) {
                            collected.add(
                                LyricsCandidate(
                                    id = "genius_${hitResult.optLong("id", h.toLong())}_p",
                                    source = "genius",
                                    artist = hitArtist.ifBlank { eval.bestHypothesis.artist },
                                    title = hitTitle.ifBlank { eval.bestHypothesis.title },
                                    durationSec = 0,
                                    synced = false,
                                    syncedLyrics = "",
                                    plainLyrics = plain,
                                    lines = emptyList(),
                                    score = eval.score,
                                    titleSimilarity = eval.titleSim,
                                    artistSimilarity = eval.artistSim
                                )
                            )
                            statuses["Genius"] = "200 (1 plain)"
                            return collected
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            lastNote = t.javaClass.simpleName
        }
        statuses["Genius"] = if (collected.isNotEmpty()) "200 (${collected.size} plain)" else lastNote
        return collected
    }

    private fun fetchLyricsOvhCandidates(
        plan: LyricsQueryPlan,
        trackDurationSec: Int,
        statuses: MutableMap<String, String>
    ): List<LyricsCandidate> {
        checkProviderCooldownStatus(NetworkCategory.LYRICS_OVH)?.let {
            statuses["Lyrics.ovh"] = it
            return emptyList()
        }
        var lastNote = "200 (0 совп.)"
        try {
            for (hyp in plan.hypotheses.take(2)) {
                if (hyp.artist.isBlank() || hyp.title.isBlank()) continue
                // Require strict no-duration thresholds before hitting or accepting
                val eval = evaluateCandidateMetadata(
                    plan = plan,
                    trackDurationSec = trackDurationSec,
                    candidateArtist = hyp.artist,
                    candidateTitle = hyp.title,
                    candidateDurationSec = 0,
                    requireStrictNoDurationThresholds = true
                ) ?: continue
                val a = urlEncode(hyp.artist).replace("+", "%20")
                val t = urlEncode(hyp.title).replace("+", "%20")
                val res = BridgeHttpClient.getTextDetailed(
                    url = "https://api.lyrics.ovh/v1/$a/$t",
                    useCatalogClient = true,
                    maxRetries = 0,
                    customCallTimeoutMs = 3000L
                )
                if (res.isCooldown || res.httpCode == 403 || res.httpCode == 429) {
                    statuses["Lyrics.ovh"] = res.errorLabel ?: "HTTP ${res.httpCode}"
                    return emptyList()
                }
                if (res.errorLabel != null) {
                    lastNote = res.errorLabel
                    continue
                }
                val body = res.body ?: continue
                val lyrics = runCatching { JSONObject(body).optString("lyrics").trim() }.getOrNull().orEmpty()
                if (lyrics.length >= 24) {
                    statuses["Lyrics.ovh"] = "200 (1 plain)"
                    return listOf(
                        LyricsCandidate(
                            id = "ovh_${hyp.label}_p",
                            source = "lyrics_ovh",
                            artist = hyp.artist,
                            title = hyp.title,
                            durationSec = 0,
                            synced = false,
                            syncedLyrics = "",
                            plainLyrics = lyrics,
                            lines = emptyList(),
                            score = eval.score - 5f,
                            titleSimilarity = eval.titleSim,
                            artistSimilarity = eval.artistSim
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            lastNote = t.javaClass.simpleName
        }
        statuses["Lyrics.ovh"] = lastNote
        return emptyList()
    }

    // ---- 6. LRC / YRC / KuGou Base64 / Genius HTML Parsers ----

    internal fun decodeKuGouBase64Lrc(base64Content: String): String? {
        if (base64Content.isBlank()) return null
        return runCatching {
            val cleaned = base64Content.replace(Regex("""\s+"""), "")
            val bytes = Base64.getDecoder().decode(cleaned)
            String(bytes, Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Parses NetEase YRC verbatim format:
     * `[lineStartMs,lineDurMs](wordStartMs,wordDurMs,0)Word `
     */
    internal fun parseNetEaseYrc(yrcText: String): List<LyricsLine> {
        if (yrcText.isBlank()) return emptyList()
        val lineHeaderRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
        val wordChunkRegex = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")
        val out = mutableListOf<LyricsLine>()

        yrcText.removePrefix("\uFEFF").replace("\r\n", "\n").lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (!trimmed.startsWith("[")) return@forEach
            val headerMatch = lineHeaderRegex.matchEntire(trimmed) ?: return@forEach
            val lineStartMs = headerMatch.groupValues[1].toLongOrNull() ?: return@forEach
            val body = headerMatch.groupValues[3]
            val wordMatches = wordChunkRegex.findAll(body).toList()
            if (wordMatches.isEmpty()) {
                val plainBody = body.trim()
                if (plainBody.isNotEmpty()) {
                    out.add(LyricsLine(timeMs = lineStartMs, text = plainBody))
                }
                return@forEach
            }

            val words = mutableListOf<LyricWord>()
            val textBuilder = StringBuilder()
            for (wm in wordMatches) {
                val wStart = wm.groupValues[1].toLongOrNull() ?: lineStartMs
                val wText = wm.groupValues[3]
                if (wText.isNotEmpty()) {
                    words.add(LyricWord(startMs = wStart, text = wText))
                    textBuilder.append(wText)
                }
            }
            val fullText = textBuilder.toString().trim()
            if (fullText.isNotEmpty()) {
                out.add(LyricsLine(timeMs = lineStartMs, text = fullText, words = words))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    internal fun stripCreditMetaLinesIfBodyExists(lines: List<LyricsLine>): List<LyricsLine> {
        if (lines.isEmpty()) return emptyList()
        val nonMeta = lines.filterNot { META_CREDIT_LINE_REGEX.containsMatchIn(it.text.trim()) }
        return if (nonMeta.size >= MIN_SYNCED_LINES) nonMeta else lines
    }

    private fun formatLinesAsLrc(lines: List<LyricsLine>): String {
        return lines.joinToString("\n") { line ->
            val mins = (line.timeMs / 60_000L)
            val secs = (line.timeMs % 60_000L) / 1000L
            val hundredths = (line.timeMs % 1000L) / 10L
            String.format(Locale.US, "[%02d:%02d.%02d]%s", mins, secs, hundredths, line.text)
        }
    }

    internal fun extractGeniusHtmlLyrics(html: String, expectedTitle: String = ""): String {
        val markerRegex = Regex("""<div\b[^>]*data-lyrics-container\s*=\s*["']true["'][^>]*>""", RegexOption.IGNORE_CASE)
        val openDivRegex = Regex("""<div\b[^>]*>""", RegexOption.IGNORE_CASE)
        val closeDivRegex = Regex("""</div\s*>""", RegexOption.IGNORE_CASE)

        val blocks = mutableListOf<String>()
        var searchFrom = 0
        while (searchFrom < html.length) {
            val startMatch = markerRegex.find(html, searchFrom) ?: break
            val contentStart = startMatch.range.last + 1
            var cursor = contentStart
            var depth = 1
            var contentEnd = html.length

            while (cursor < html.length && depth > 0) {
                val nextOpen = openDivRegex.find(html, cursor)
                val nextClose = closeDivRegex.find(html, cursor)
                if (nextClose == null) break
                if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                    depth++
                    cursor = nextOpen.range.last + 1
                } else {
                    depth--
                    if (depth == 0) {
                        contentEnd = nextClose.range.first
                    }
                    cursor = nextClose.range.last + 1
                }
            }

            if (contentEnd > contentStart) {
                blocks.add(html.substring(contentStart, contentEnd))
            }
            searchFrom = maxOf(cursor, startMatch.range.last + 1)
        }

        if (blocks.isEmpty()) return ""
        val combined = blocks.joinToString("\n")
            .replace(Regex("""<div\b[^>]*data-exclude-from-selection\s*=\s*["']true["'][^>]*>.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<(script|style|svg|button)\b[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</(?:p|div|li|h\d)>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")

        val decoded = decodeHtmlEntities(combined)
        return cleanGeniusLyricsText(decoded, expectedTitle)
    }

    internal fun cleanGeniusLyricsText(rawText: String, expectedTitle: String = ""): String {
        val contributorsRegex = Regex("""^\s*\d+\s+Contributors?.*$""", RegexOption.IGNORE_CASE)
        val geniusHeaderNoiseRegex = Regex("""^\s*(?:Translations|Read More|About|Genius Lyrics)\s*$""", RegexOption.IGNORE_CASE)
        val titleLyricsHeaderRegex = Regex("""^.{1,90}\s+Lyrics\s*$""")
        val youMightAlsoLikeRegex = Regex("""(?i)\s*You might also like\s*$""")
        val embedTailRegex = Regex("""\s*\d*Embed\s*$""", RegexOption.IGNORE_CASE)

        val cleanedLines = rawText
            .replace("\r\n", "\n")
            .lines()
            .map { it.trimEnd() }
            .toMutableList()

        // Strip leading header artifacts ("<Title> Lyrics", "12 Contributors", "Translations", "Read More")
        val normalizedExpected = normalizeForMatch(stripMetadataJunk(expectedTitle))
        var headerProbe = 0
        while (cleanedLines.isNotEmpty() && headerProbe < 5) {
            val first = cleanedLines.first().trim()
            if (first.isEmpty()) {
                cleanedLines.removeAt(0)
                continue
            }
            val normFirst = normalizeForMatch(first)
            val matchesExpectedHeader = normalizedExpected.isNotBlank() &&
                (normFirst == "$normalizedExpected lyrics" || (normFirst.endsWith(" lyrics") && normFirst.contains(normalizedExpected)))
            val isGeniusBanner = matchesExpectedHeader ||
                contributorsRegex.matches(first) ||
                geniusHeaderNoiseRegex.matches(first) ||
                (headerProbe == 0 && titleLyricsHeaderRegex.matches(first))
            if (isGeniusBanner) {
                cleanedLines.removeAt(0)
                headerProbe++
            } else {
                break
            }
        }

        // Remove inline/trailing "You might also like" markers
        val filtered = cleanedLines.mapNotNull { line ->
            val stripped = line.replace(youMightAlsoLikeRegex, "").trimEnd()
            if (stripped.equals("You might also like", ignoreCase = true)) null else stripped
        }.toMutableList()

        // Trim trailing blank lines and Genius "...Embed" tail
        while (filtered.isNotEmpty()) {
            val last = filtered.last().trim()
            if (last.isEmpty()) {
                filtered.removeAt(filtered.lastIndex)
                continue
            }
            val withoutEmbed = last.replace(embedTailRegex, "").replace(youMightAlsoLikeRegex, "").trimEnd()
            if (withoutEmbed != last) {
                if (withoutEmbed.isEmpty()) {
                    filtered.removeAt(filtered.lastIndex)
                } else {
                    filtered[filtered.lastIndex] = withoutEmbed
                }
            } else {
                break
            }
        }

        return filtered
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun decodeHtmlEntities(input: String): String {
        var text = input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&rsquo;", "’")
            .replace("&lsquo;", "‘")
            .replace("&rdquo;", "”")
            .replace("&ldquo;", "“")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        text = Regex("""&#(\d+);""").replace(text) { m ->
            m.groupValues[1].toIntOrNull()?.let { code ->
                if (code in 32..0x10FFFF) String(Character.toChars(code)) else m.value
            } ?: m.value
        }
        text = Regex("""&#x([0-9a-fA-F]+);""").replace(text) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { code ->
                if (code in 32..0x10FFFF) String(Character.toChars(code)) else m.value
            } ?: m.value
        }
        return text
    }

    internal fun parseLrcLines(lrcText: String): List<LyricsLine> {
        val lineTagRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val wordTagRegex = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
        val result = mutableListOf<LyricsLine>()
        val sanitizedText = lrcText.removePrefix("\uFEFF").replace("\r\n", "\n")

        sanitizedText.lineSequence().forEach { rawLineRaw ->
            val rawLine = rawLineRaw.removePrefix("\uFEFF")
            val tags = lineTagRegex.findAll(rawLine).toList()
            if (tags.isEmpty()) return@forEach
            val bodyWithWordTags = lineTagRegex.replace(rawLine, "").trim()
            if (bodyWithWordTags.isEmpty()) return@forEach

            val cleanText = wordTagRegex.replace(bodyWithWordTags, "").trim()
            if (cleanText.isEmpty()) return@forEach

            val parsedBaseTimeMs = parseTimestampMs(tags.first().groupValues[1], tags.first().groupValues[2], tags.first().groupValues[3]) ?: 0L
            val parsedWordsBase = parseEnhancedWordTags(bodyWithWordTags, parsedBaseTimeMs, wordTagRegex)

            for (match in tags) {
                val totalMs = parseTimestampMs(match.groupValues[1], match.groupValues[2], match.groupValues[3]) ?: continue
                val delta = totalMs - parsedBaseTimeMs
                val shiftedWords = if (parsedWordsBase.isEmpty()) {
                    emptyList()
                } else if (delta == 0L) {
                    parsedWordsBase
                } else {
                    parsedWordsBase.map { w -> w.copy(startMs = (w.startMs + delta).coerceAtLeast(0L)) }
                }
                result.add(LyricsLine(timeMs = totalMs, text = cleanText, words = shiftedWords))
            }
        }

        return result.sortedBy { it.timeMs }
    }

    private fun parseEnhancedWordTags(
        body: String,
        lineStartMs: Long,
        wordTagRegex: Regex
    ): List<LyricWord> {
        val matches = wordTagRegex.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()

        val words = mutableListOf<LyricWord>()
        val firstStart = matches.first().range.first
        if (firstStart > 0) {
            val prefix = body.substring(0, firstStart)
            if (prefix.isNotBlank()) {
                words.add(LyricWord(startMs = lineStartMs, text = prefix))
            }
        }
        for (i in matches.indices) {
            val m = matches[i]
            val segStart = m.range.last + 1
            val segEnd = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
            val chunk = if (segEnd > segStart) body.substring(segStart, segEnd) else ""
            val wordMs = parseTimestampMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: lineStartMs
            if (chunk.isNotEmpty()) {
                words.add(LyricWord(startMs = wordMs, text = chunk))
            }
        }
        return words
    }

    private fun parseTimestampMs(minStr: String, secStr: String, fracRaw: String): Long? {
        val mins = minStr.toLongOrNull() ?: return null
        val secs = secStr.toLongOrNull() ?: return null
        val millis = when (fracRaw.length) {
            0 -> 0L
            1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
            2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
            else -> fracRaw.take(3).toLongOrNull() ?: 0L
        }
        return mins * 60_000L + secs * 1000L + millis
    }

    internal fun computeInterludeBefore(
        lines: List<LyricsLine>,
        index: Int,
        minIntroMs: Long = 4_000L,
        minInterLineGapMs: Long = 5_000L
    ): LyricsInterlude? {
        if (index < 0 || index >= lines.size) return null
        val targetStart = lines[index].timeMs
        return if (index == 0) {
            if (targetStart >= minIntroMs) LyricsInterlude(startMs = 0L, endMs = targetStart) else null
        } else {
            val prevStart = lines[index - 1].timeMs
            val estimatedPrevEnd = prevStart + 2_200L
            if ((targetStart - prevStart) >= minInterLineGapMs && targetStart > estimatedPrevEnd) {
                LyricsInterlude(startMs = estimatedPrevEnd, endMs = targetStart)
            } else {
                null
            }
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
