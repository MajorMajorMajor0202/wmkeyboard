package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.sqrt

/** Bounded phonotactic search: syllable boundaries may restart without a dictionary word. */
object DoublePinyinGlide {
    data class Point(val x: Double, val y: Double, val t: Long = 0L)
    data class Key(val letters: String, val x: Double, val y: Double, val width: Double = letters.length.toDouble().coerceAtLeast(1.0))
    data class Path(val code: String, val cost: Double, val neighbour: Boolean = false)
    data class Result(val code: String, val reading: String, val score: Double, val cost: Double,
        val neighbour: Boolean = false, val deviation: Double = 0.0, val maxDeviation: Double = 0.0, val raw: String? = null)
    data class Deviation(val rms: Double, val maximum: Double)
    private const val SAMPLES = 48
    private const val BEAM = 64
    private const val MAX_KEYS = 24
    private const val POOL = 64
    private const val GAP = 3.0
    private const val SIGMA = 0.42
    private data class State(val code: String, val key: Int, val column: DoubleArray, val bound: Double = 0.0, val mask: Int = 0)

    /** Coordinates are in ordinary letter-key widths; relative pauses mark intentional keys. */
    class Search(
        rawPoints: List<Point>, private val keys: List<Key>, codes: Collection<String>,
        private val pauseWeight: Double = 2.0, private val live: Boolean = false,
    ) {
        private val beam = if (live) 28 else BEAM
        private val pool = if (live) 28 else POOL
        private val compactKeys = keys.any { it.letters.length > 1 }
        private val source = preparedPoints(rawPoints, compactKeys)
        private val loops = loops(source, keys)
        private val intent by lazy { keyIntent(source, keys, loops) }
        private val geometricIntent by lazy {
            if (intent.timed) keyIntent(source, keys, loops, useSpeed = false).keys else intended
        }
        internal val intended get() = intent.keys
        // A local circle is a repeat mark, not travel to neighbouring keys.
        private val points = source.mapIndexedNotNull { i, p ->
            val loop = loops.firstOrNull { i in it.start..it.end }
            when {
                loop == null -> p
                i == loop.start || i == loop.end -> Point(keys[loop.key].x, keys[loop.key].y, p.t)
                else -> null
            }
        }
        private val samples = resample(points, if (live && intended.size <= 6) 24 else SAMPLES).ifEmpty {
            if (loops.isNotEmpty()) listOf(points.first(), points.last()) else emptyList()
        }
        private val arc = DoubleArray(samples.size).also { lengths ->
            for (i in 1 until samples.size) {
                lengths[i] = lengths[i - 1] + hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
            }
        }
        private val pairs = codes.filter { it.length == 2 }.mapNotNull { code ->
            val a = keys.indexOfFirst { code[0] in it.letters }
            val b = keys.indexOfFirst { code[1] in it.letters }
            if (a < 0 || b < 0) null else a to b
        }.toSet()
        private val firstKeys = BooleanArray(keys.size) { k -> pairs.any { it.first == k } }
        private val allowed = Array(keys.size) { a -> BooleanArray(keys.size) { b -> a to b in pairs } }
        private val distances = Array(keys.size) { a -> DoubleArray(keys.size) { b -> hypot(keys[a].x - keys[b].x, keys[a].y - keys[b].y) } }
        private val gapWeight = if (compactKeys && intended.size >= 7) 1.8 else GAP
        // Travel down the intended key-to-key vector is cheap. A detour or a
        // reversal has extra length even when its total matches another key jump.
        private val directions = Array(keys.size) { from ->
            Array(keys.size) { to ->
                val span = distances[from][to]
                val x = if (gapWeight < GAP && span > 0.0) (keys[to].x - keys[from].x) / span else 0.0
                val y = if (gapWeight < GAP && span > 0.0) (keys[to].y - keys[from].y) / span else 0.0
                DoubleArray(samples.size) { j ->
                    if (x == 0.0 && y == 0.0) 0.0 else 1.8 * (arc[j] - samples[j].x * x - samples[j].y * y)
                }
            }
        }
        private val near = keys.indices.filter { k -> samples.any { distance(it, keys[k]) <= 1.2 } }
        private val pointCosts = Array(keys.size) { k -> DoubleArray(samples.size) { j ->
            val d = distance(samples[j], keys[k]); d * d / (2 * SIGMA * SIGMA)
        } }
        private val pivots by lazy { simplify(0.45).drop(1).dropLast(1).filter { p -> keys.any { distance(p,it) <= 0.55 } } }
        private val pauses by lazy {
            val duration = samples.lastOrNull()?.t?.minus(samples.firstOrNull()?.t ?: 0L) ?: 0L
            if (duration <= 0L || samples.size < 2 || pauseWeight <= 0.0) emptyList() else {
                val average = duration.toDouble() / (samples.size - 1)
                val marks = mutableListOf<Pair<Point, Double>>()
                var run = -2
                for (j in 1 until samples.size) {
                    val strength = (((samples[j].t - samples[j-1].t) / average - 1.0) / 1.5).coerceIn(0.0,1.0)
                    if (strength <= 0.0 || keys.none { distance(samples[j],it) <= 0.55 }) continue
                    if (run == j - 1 && marks.isNotEmpty()) {
                        if (strength > marks.last().second) marks[marks.lastIndex] = samples[j] to strength
                    } else marks += samples[j] to strength
                    run = j
                }
                marks
            }
        }
        private val pivotCache = HashMap<Int, Double>()

        fun paths(): List<Path> {
            if (samples.size < 2 || pairs.isEmpty()) return emptyList()
            val remaining = remainingCosts()
            var frontier = near.filter { k -> firstKeys[k] && distance(samples[0], keys[k]) <= 1.2 }
                .map { k -> State(keys[k].letters.take(1), k, DoubleArray(samples.size) { if (it == 0) pointCosts[k][0] else Double.POSITIVE_INFINITY }, mask = 1 shl k) }
            val found = mutableListOf<Path>()
            for (length in 2..MAX_KEYS) {
                val next = mutableListOf<State>()
                for (state in frontier) for (k in near) {
                    if (length % 2 == 0 && !allowed[state.key][k]) continue
                    if (length % 2 != 0 && !firstKeys[k]) continue
                    val col = advance(state.column, state.key, k)
                    if (col.none { it.isFinite() }) continue
                    val code = state.code + keys[k].letters.first()
                    val mask = state.mask or (1 shl k)
                    next += State(code, k, col, col.indices.minOf { j -> col[j] + remaining[j][k] }, mask)
                    if (length % 2 == 0 && distance(samples.last(), keys[k]) <= 1.2 && col.last().isFinite()) {
                        found += Path(code, col.last() + pivotPenalty(mask) + sequencePenalty(code))
                    }
                }
                frontier = next.sortedBy { it.bound }.take(beam)
                if (frontier.isEmpty()) break
            }
            val ordered = found.sortedBy { it.cost }.distinctBy { it.code }
            // Preserve long syllable paths before cheap shortcuts fill the global pool.
            val lengths = ordered.groupBy { it.code.length }.values.flatMap { it.take(2) }
            val anchored = anchorPaths().ifEmpty { listOfNotNull(cornerCode()?.let { Path(it, cost(it)) }) }
            return (anchored + lengths + ordered).distinctBy { it.code }.take(pool).sortedBy { it.cost }
        }

        /** Keep full ordered visits in the pool even when the shape beam prunes their prefixes. */
        internal fun anchorPaths(): List<Path> {
            fun spelling(ids: List<Int>): String? {
                if (ids.size !in 2..MAX_KEYS || ids.size % 2 != 0 ||
                    ids.chunked(2).any { !allowed[it[0]][it[1]] }) return null
                return ids.joinToString("") { keys[it].letters.take(1) }
            }
            fun paths(routes: Sequence<List<Int>>) = routes.mapNotNull(::spelling).distinct()
                .map { Path(it, cost(it)) }.filter { it.cost.isFinite() }.sortedBy { it.cost }.take(8).toList()
            val direct = paths(sequenceOf(intended, geometricIntent))
            if (!compactKeys || intended.size < 7) return direct
            val odd = sequenceOf(intended, geometricIntent).distinct().filter { it.size >= 7 && it.size % 2 != 0 }
            // A key on the straight part of a swipe can carry one half of a syllable.
            // Only insert keys whose centres lie along that particular leg.
            val inserted = odd.flatMap { route -> route.zipWithNext().asSequence().flatMapIndexed { index, (from, to) ->
                val a = keys[from]
                val b = keys[to]
                near.asSequence().filter { mid ->
                    mid != from && mid != to &&
                        distances[from][mid] + distances[mid][to] - distances[from][to] < 0.32 &&
                        abs(a.y - keys[mid].y) + abs(b.y - keys[mid].y) <= abs(a.y - b.y) + 0.35
                }.map { mid -> route.take(index + 1) + mid + route.drop(index + 1) }
            } }
            val removed = odd.flatMap { route -> (1 until route.lastIndex).asSequence()
                .map { index -> route.take(index) + route.drop(index + 1) } }
            return (direct + paths(inserted).take(4) + paths(removed).take(3)).distinctBy { it.code }
        }

        /** Keep deliberately drawn pivots even if the statistical beam filled with another prefix. */
        private fun cornerCode(): String? {
            val ids = simplify(0.12).map { p -> keys.indices.minByOrNull { distance(p,keys[it]) } ?: return null }
                .fold(mutableListOf<Int>()) { acc,k -> acc.apply { if (lastOrNull()!=k) add(k) } }
            if (ids.size !in 2..MAX_KEYS || ids.size % 2 != 0 || ids.chunked(2).any { !allowed[it[0]][it[1]] }) return null
            return ids.joinToString("") { keys[it].letters.take(1) }
        }

        /** A deliberate turn near a key must be explained, even if a shortcut has similar arc length. */
        private fun pivotPenalty(mask: Int): Double = pivotCache.getOrPut(mask) {
            pivots.sumOf { p ->
                val nearest = keys.indices.filter { mask and (1 shl it) != 0 }.minOfOrNull { distance(p,keys[it]) } ?: 0.0
                val missed = (nearest - 0.35).coerceAtLeast(0.0)
                missed * missed * 0.8
            } + pauses.sumOf { (p, strength) ->
                if (keys.indices.any { mask and (1 shl it) != 0 && distance(p,keys[it]) <= 0.55 }) 0.0 else strength * pauseWeight
            }
        }

        private val orderedMarks by lazy {
            (pivots + pauses.map { it.first }).sortedBy { it.t }.fold(mutableListOf<Point>()) { marks,p ->
                marks.apply { if(lastOrNull()?.let { hypot(it.x-p.x,it.y-p.y)<0.35 } != true) add(p) }
            }.take(24)
        }
        private val visitPenalty by lazy {
            // Rounded turns and a lift near a boundary shorten the measured arc.
            // Give the reconstructed route extra weight if it spells complete syllables.
            if (intent.inferred && intended.size in 4..6 && intended.size % 2 == 0 &&
                intended.chunked(2).all { allowed[it[0]][it[1]] }) 6.5 else 3.0
        }
        private val loopCounts = loops.groupingBy { it.key }.eachCount()
        private val sequenceCache=HashMap<String,Double>()
        /** A later return to the same key needs its own occurrence; a set cannot represent it. */
        private fun sequencePenalty(code: String): Double = sequenceCache.getOrPut(code) {
            val ids=code.map { c -> keys.indexOfFirst { c in it.letters } }
            var row=DoubleArray(ids.size+1)
            for(mark in orderedMarks) {
                val next=DoubleArray(ids.size+1);next[0]=row[0]+1.2
                for(j in ids.indices) {
                    val error=(distance(mark,keys[ids[j]])-0.4).coerceAtLeast(0.0)
                    next[j+1]=minOf(next[j],row[j+1]+1.2,row[j]+error*error*0.8)
                }
                row=next
            }
            // A return stroke can have almost the same length after dropping one of its turns.
            // Match deliberate visits in order, so a nearby key cannot stand in for the turn.
            fun missedVisits(visits: List<Int>): Int {
                // Count the longest ordered match, including paths with a corrected start or end.
                val matches = IntArray(visits.size + 1)
                for (id in ids) {
                    var diagonal = 0
                    for (i in visits.indices) {
                        val previous = matches[i + 1]
                        matches[i + 1] = if (id == visits[i]) diagonal + 1 else maxOf(matches[i], previous)
                        diagonal = previous
                    }
                }
                return visits.size - matches.last()
            }
            val missed = if (compactKeys && intended.size >= 4) missedVisits(geometricIntent) else 0
            val timingPenalty = if (intent.timed) (missedVisits(intended) - missed).coerceAtLeast(0) * 1.3 else 0.0
            val repeats = IntArray(keys.size)
            for (i in 1 until ids.size) if (ids[i] == ids[i - 1]) repeats[ids[i]]++
            val repeatPenalty = repeats.indices.sumOf { key ->
                val difference = repeats[key] - (loopCounts[key] ?: 0)
                when {
                    difference < 0 -> -difference * 4.0
                    compactKeys -> difference * 1.8
                    else -> 0.0
                }
            }
            row.last() + missed * visitPenalty + timingPenalty + repeatPenalty
        }

        private fun simplify(tolerance: Double): List<Point> {
            val valid = points.filter { it.x.isFinite() && it.y.isFinite() }
            if (valid.size < 2) return valid
            val selected = sortedSetOf(0, valid.lastIndex)
            val pending = java.util.ArrayDeque<Pair<Int, Int>>()
            pending.add(0 to valid.lastIndex)
            while (pending.isNotEmpty()) {
                val (a,b) = pending.removeLast()
                val dx = valid[b].x - valid[a].x; val dy = valid[b].y - valid[a].y
                val squared = dx * dx + dy * dy
                var farthest = -1; var distance = tolerance
                for (i in a + 1 until b) {
                    val t = if (squared == 0.0) 0.0 else (((valid[i].x-valid[a].x)*dx+(valid[i].y-valid[a].y)*dy)/squared).coerceIn(0.0,1.0)
                    val d = hypot(valid[i].x-valid[a].x-t*dx, valid[i].y-valid[a].y-t*dy)
                    if (d > distance) { distance = d; farthest = i }
                }
                if (farthest >= 0) {
                    selected.add(farthest)
                    pending.add(a to farthest); pending.add(farthest to b)
                }
            }
            return selected.map { valid[it] }
        }

        /** Relax the syllable grammar to estimate the unfinished stroke, avoiding a beam full of stalled prefixes. */
        private fun remainingCosts(): Array<DoubleArray> {
            val remaining = Array(samples.size) { DoubleArray(keys.size) { Double.POSITIVE_INFINITY } }
            for (k in near) if (distance(samples.last(), keys[k]) <= 1.2) remaining[samples.lastIndex][k] = 0.0
            for (i in samples.lastIndex - 1 downTo 0) for (from in near) {
                var best = Double.POSITIVE_INFINITY
                for (j in i + 1 until samples.size) for (to in near) {
                    val span = distances[from][to]
                    val direction = directions[from][to]
                    val cost = gapWeight * abs(arc[j] - arc[i] - span) + direction[j] - direction[i] +
                        pointCosts[to][j] + 0.35 + remaining[j][to]
                    if (cost < best) best = cost
                }
                remaining[i][from] = best
            }
            return remaining
        }

        /** Same metric for grammar paths and whole-word paths from the existing native decoder. */
        fun cost(code: String, endpointReach: Double = 1.2): Double {
            if (samples.size < 2 || code.isEmpty()) return Double.POSITIVE_INFINITY
            val ids = code.map { c -> keys.indexOfFirst { c in it.letters } }
            if (ids.any { it < 0 } || distance(samples.first(), keys[ids.first()]) > endpointReach ||
                distance(samples.last(), keys[ids.last()]) > endpointReach
            ) return Double.POSITIVE_INFINITY
            var col = DoubleArray(samples.size) { if (it == 0) pointCosts[ids[0]][0] else Double.POSITIVE_INFINITY }
            for (i in 1 until ids.size) col = advance(col, ids[i - 1], ids[i])
            return col.last() + pivotPenalty(ids.fold(0) { mask,k -> mask or (1 shl k) }) + sequencePenalty(code)
        }

        /** One adjacent physical-key repair only, with a separate bounded correction budget. */
        fun neighbours(paths: List<Path>, reach: Double): List<Path> {
            val repairs = mutableMapOf<String, Path>()
            for (path in paths.take(8)) for (index in path.code.indices) {
                val from = keys.indexOfFirst { path.code[index] in it.letters }
                if (from < 0) continue
                val nearby = keys.indices.filter { it != from && distances[from][it] <= reach }.sortedBy { distances[from][it] }.take(8)
                for (key in nearby) {
                    val code = path.code.replaceRange(index,index+1,keys[key].letters.take(1))
                    val ids = code.map { c -> keys.indexOfFirst { c in it.letters } }
                    if (ids.chunked(2).any { it.size != 2 || !allowed[it[0]][it[1]] }) continue
                    val cost = cost(code, endpointReach = reach)
                    if (cost.isFinite()) repairs[code] = Path(code,cost,true)
                }
            }
            val originals = paths.mapTo(HashSet()) { it.code }
            return repairs.values.filter { it.code !in originals }.sortedBy { it.cost }.take(pool)
        }

        /** Ordered alignment retained for measurement and per-syllable undo checkpoints. */
        private fun alignment(code: String): Pair<List<Int>,List<Int>>? {
            if(samples.isEmpty() || code.isEmpty()) return null
            val ids=code.map { c -> keys.indexOfFirst { c in it.letters } }
            if(ids.any { it<0 }) return null
            val previous=Array(ids.size) { IntArray(samples.size) }
            var column=DoubleArray(samples.size) { if(it==0) pointCosts[ids[0]][0] else Double.POSITIVE_INFINITY }
            for(letter in 1 until ids.size) {
                val span=distances[ids[letter-1]][ids[letter]]
                val direction = directions[ids[letter - 1]][ids[letter]]
                column=DoubleArray(samples.size) { j ->
                    var best=Double.POSITIVE_INFINITY
                    for(i in 0..j) {
                        val cost=column[i]+gapWeight*abs(arc[j]-arc[i]-span)+direction[j]-direction[i]+pointCosts[ids[letter]][j]+0.35
                        if(cost<best) { best=cost;previous[letter][j]=i }
                    }
                    best
                }
            }
            var at=samples.lastIndex
            val aligned=IntArray(ids.size)
            for(letter in ids.indices.reversed()) { aligned[letter]=at;if(letter>0) at=previous[letter][at] }
            return ids to aligned.toList()
        }

        /** Positional error relative to each physical key's visible width. */
        fun deviation(code: String): Deviation {
            val (ids,aligned)=alignment(code) ?: return Deviation(0.0,0.0)
            val errors=ids.indices.map { i -> distance(samples[aligned[i]],keys[ids[i]])/keys[ids[i]].width.coerceAtLeast(0.1) }
            return Deviation(sqrt(errors.sumOf { it*it }/ids.size),errors.maxOrNull() ?: 0.0)
        }

        fun syllableChunks(code: String): List<List<Point>> {
            val (_,aligned)=alignment(code) ?: return emptyList()
            return aligned.chunked(2).mapNotNull { indices ->
                if(indices.size!=2) null else samples.subList(indices.first(),indices.last()+1).toList()
            }
        }

        private fun advance(previous: DoubleArray, from: Int, to: Int): DoubleArray {
            val span = distances[from][to]
            val direction = directions[from][to]
            fun rightBound(index: Int) = previous[index] - direction[index] + gapWeight * arc[index]
            val out = DoubleArray(samples.size)
            val queue = IntArray(samples.size)
            var head = 0
            var tail = 0
            var boundary = 0
            var left = Double.POSITIVE_INFINITY
            for (j in samples.indices) {
                val rightValue = rightBound(j)
                while (tail > head && rightBound(queue[tail - 1]) >= rightValue) tail--
                queue[tail++] = j
                val target = arc[j] - span
                while (boundary <= j && arc[boundary] <= target) {
                    left = minOf(left, previous[boundary] - direction[boundary] - gapWeight * arc[boundary])
                    boundary++
                }
                while (head < tail && queue[head] < boundary) head++
                val right = if (head < tail) rightBound(queue[head]) - gapWeight * target else Double.POSITIVE_INFINITY
                // Absolute travel mismatch has a sliding range minimum: O(samples), not O(samples squared).
                out[j] = minOf(left + gapWeight * target, right) + direction[j] + pointCosts[to][j] + 0.35
            }
            return out
        }
    }

    data class Loop(val start: Int, val end: Int, val key: Int)

    private fun boundedPoints(points: List<Point>): List<Point> {
        val valid = points.filter { it.x.isFinite() && it.y.isFinite() }
        return if (valid.size <= 512) valid else List(512) { valid[it * (valid.size - 1) / 511] }
    }

    private fun preparedPoints(points: List<Point>, compact: Boolean): List<Point> {
        val path = boundedPoints(points)
        if (!compact) return path
        return path.mapIndexed { index, point ->
            if (index !in 1 until path.lastIndex) return@mapIndexed point
            val before = path[index - 1]
            val after = path[index + 1]
            if (hypot(before.x - point.x, before.y - point.y) > 0.28 ||
                hypot(after.x - point.x, after.y - point.y) > 0.28) return@mapIndexed point
            point.copy(x = (before.x + 2 * point.x + after.x) / 4, y = (before.y + 2 * point.y + after.y) / 4)
        }
    }

    fun hasLoop(points: List<Point>, keys: List<Key>): Boolean =
        loops(preparedPoints(points, keys.any { it.letters.length > 1 }), keys).isNotEmpty()

    /** Closed local area, in either direction. Jitter and out-and-back strokes have no area. */
    fun loops(points: List<Point>, keys: List<Key>): List<Loop> {
        if (keys.isEmpty()) return emptyList()
        val nearest = points.map { p -> keys.indices.minBy { distance(p, keys[it]) } }
        val found = mutableListOf<Loop>()
        var start = 0
        while (start < points.size - 4) {
            val anchor = points[start]
            val key = nearest[start]
            val scale = keys[key].width.coerceIn(1.0, 1.4)
            if (distance(anchor, keys[key]) > 0.65 * scale) { start++; continue }
            var minX = anchor.x; var maxX = anchor.x
            var minY = anchor.y; var maxY = anchor.y
            var area = 0.0; var travel = 0.0
            var end = -1
            for (j in start + 1 until points.size) {
                val p = points[j]; val prev = points[j - 1]
                // A loop can skim a boundary, but a stroke into a neighbour is travel.
                if (nearest[j] != key && distance(p, keys[nearest[j]]) + 0.12 < distance(p, keys[key])) break
                minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
                minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
                val w = maxX - minX; val h = maxY - minY
                if (maxOf(w, h) > 1.15 * scale) break
                area += (prev.x - anchor.x) * (p.y - anchor.y) - (p.x - anchor.x) * (prev.y - anchor.y)
                travel += hypot(p.x - prev.x, p.y - prev.y)
                if (j - start < 4 || minOf(w, h) < maxOf(0.16, maxOf(w, h) * 0.35) || maxOf(w, h) < 0.28) continue
                val closure = hypot(p.x - anchor.x, p.y - anchor.y)
                if (closure > maxOf(0.08 * scale, maxOf(w, h) * 0.22)) continue
                val perimeter = travel + closure
                if (2 * PI * abs(area) < 0.65 * perimeter * perimeter || travel < maxOf(w, h) * 2.1) continue
                val tangentReach = maxOf(w, h) * 0.30
                val outgoing = (start + 1 until j).firstOrNull {
                    hypot(points[it].x - anchor.x, points[it].y - anchor.y) >= tangentReach
                }?.let(points::get) ?: continue
                val incoming = (j - 1 downTo start + 1).firstOrNull {
                    hypot(points[it].x - p.x, points[it].y - p.y) >= tangentReach
                }?.let(points::get) ?: continue
                if ((outgoing.x - anchor.x) * (p.x - incoming.x) + (outgoing.y - anchor.y) * (p.y - incoming.y) <= 0) continue
                val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
                if (hypot(cx - keys[key].x, cy - keys[key].y) > 0.65 * scale) continue
                end = j
                break
            }
            if (end >= 0) { found += Loop(start, end, key); start = end } else start++
        }
        return found
    }

    /** A straight transit over a key is not another keystroke. Keep turns, pauses and endpoints. */
    fun intentKeys(points: List<Point>, keys: List<Key>): List<Int> {
        val path = preparedPoints(points, keys.any { it.letters.length > 1 })
        return keyIntent(path, keys, loops(path, keys)).keys
    }

    private data class KeyIntent(val keys: List<Int>, val inferred: Boolean = false, val timed: Boolean = false)

    private fun keyIntent(path: List<Point>, keys: List<Key>, loops: List<Loop>, useSpeed: Boolean = true): KeyIntent {
        if (path.isEmpty() || keys.isEmpty()) return KeyIntent(emptyList())
        val chosen=sortedSetOf(0,path.lastIndex)
        val pending=java.util.ArrayDeque<Pair<Int,Int>>()
        pending.add(0 to path.lastIndex)
        while(pending.isNotEmpty()) {
            val (a,b)=pending.removeLast()
            val dx=path[b].x-path[a].x;val dy=path[b].y-path[a].y;val length=dx*dx+dy*dy
            var far=-1;var error=0.28
            for(i in a+1 until b) {
                val t=if(length==0.0) 0.0 else (((path[i].x-path[a].x)*dx+(path[i].y-path[a].y)*dy)/length).coerceIn(0.0,1.0)
                val d=hypot(path[i].x-path[a].x-t*dx,path[i].y-path[a].y-t*dy)
                if(d>error) { error=d;far=i }
            }
            if(far>=0) { chosen.add(far);pending.add(a to far);pending.add(far to b) }
        }
        val stops = (1 until path.size).mapNotNullTo(mutableSetOf()) { i ->
            (i - 1).takeIf { path[i].t - path[i - 1].t >= 100L && hypot(path[i].x - path[i - 1].x, path[i].y - path[i - 1].y) < 0.4 }
        }
        val timed = if (useSpeed && keys.any { it.letters.length > 1 } && chosen.size >= 6) {
            val turns = chosen.toList()
            stops.addAll(slowVisits(path, keys).filter { index ->
                val before = turns.lastOrNull { it < index } ?: return@filter false
                val after = turns.firstOrNull { it > index } ?: return@filter false
                val a = keys.minBy { distance(path[before], it) }
                val b = keys.minBy { distance(path[after], it) }
                val key = keys.minBy { distance(path[index], it) }
                abs(a.y - b.y) < 0.35 && abs(key.y - a.y) < 0.35 &&
                    key.x > minOf(a.x, b.x) && key.x < maxOf(a.x, b.x) &&
                    abs(path[before].t - path[index].t) >= 72L && abs(path[after].t - path[index].t) >= 72L
            })
        } else false
        chosen.addAll(stops)
        for(loop in loops) {
            chosen.removeAll { it in loop.start..loop.end }
            chosen.add(loop.start);chosen.add(loop.end)
        }
        val result=mutableListOf<Int>()
        var previous=-1;var lastIndex=0
        val compact = keys.any { it.letters.length > 1 }
        val anchors = if (compact) compactAnchors(path, chosen.toList(), keys, stops, loops)
            else chosen.map { it to path[it] }
        val nearest = anchors.map { (_, point) -> keys.indices.minBy { distance(point, keys[it]) } }
        for ((order, anchor) in anchors.withIndex()) {
            val (index, p) = anchor
            val loop=loops.firstOrNull { it.start==index || it.end==index }
            if(loop!=null) {
                if(index==loop.start) {
                    if(result.lastOrNull()!=loop.key) result+=loop.key
                } else result+=loop.key
                previous=loop.key;lastIndex=index
                continue
            }
            val k = nearest[order]
            val key = keys[k]
            val offset = if (index == 0 || index == path.lastIndex) distance(p, key)
                else hypot((p.x - key.x) / key.width.coerceAtLeast(1.0), p.y - key.y)
            if (offset > (if (index == 0 || index == path.lastIndex) 1.2 else 0.7)) continue
            if (compact && order in 1 until anchors.lastIndex && index !in stops) {
                val before = keys[nearest[order - 1]]
                val after = keys[nearest[order + 1]]
                val between = key.x > minOf(before.x, after.x) && key.x < maxOf(before.x, after.x)
                // A shallow arc across the row should not insert the key between two reversals.
                if (between && abs(key.y - before.y) < 0.35 && abs(key.y - after.y) < 0.35) continue
            }
            val repeated = !compact && k == previous && path.subList(lastIndex, index + 1).any { distance(it, key) > 0.85 }
            if(k!=previous || repeated) { result+=k;previous=k;lastIndex=index }
        }
        return KeyIntent(result.take(MAX_KEYS), anchors.any { (index, point) -> point != path[index] }, timed)
    }

    /** A speed dip at a key also marks a visit on a straight run through the row. */
    private fun slowVisits(path: List<Point>, keys: List<Key>): List<Int> {
        fun speed(from: Int, to: Int): Double {
            val a = path[from]
            val b = path[to]
            val dt = b.t - a.t
            return if (dt > 0L) hypot(b.x - a.x, b.y - a.y) / dt else Double.NaN
        }
        // Compare a short window with the runs on both sides to avoid treating
        // every key on a uniformly slow passage as an intentional stop.
        val slow = mutableListOf<Pair<Int, Double>>()
        for (i in 10 until path.size - 10) {
            if (keys.none { distance(path[i], it) <= 0.52 }) continue
            val middle = speed(i - 3, i + 3)
            val outside = minOf(speed(i - 10, i - 4), speed(i + 4, i + 10))
            if (!(outside > 0.0 && middle <= outside * 0.68)) continue
            val ratio = middle / outside
            if (slow.lastOrNull()?.let { path[i].t - path[it.first].t < 80L } == true) {
                if (ratio < slow.last().second) slow[slow.lastIndex] = i to ratio
            } else slow += i to ratio
        }
        return slow.map { it.first }
    }

    /** Infer the corner cut off by a rounded turn, or the key approached at lift. */
    private fun compactAnchors(
        path: List<Point>, indices: List<Int>, keys: List<Key>, stops: Set<Int>, loops: List<Loop>,
    ): List<Pair<Int, Point>> {
        fun nearest(point: Point) = keys.minBy { distance(point, it) }
        fun fixed(index: Int) = index in stops || loops.any { index in it.start..it.end }
        val anchors = indices.map { it to path[it] }.toMutableList()
        var i = 1
        while (i < anchors.lastIndex - 1) {
            val (firstIndex, first) = anchors[i]
            val (secondIndex, second) = anchors[i + 1]
            if (!fixed(firstIndex) && !fixed(secondIndex)) {
                val corner = roundedCorner(anchors[i - 1].second, first, second, anchors[i + 2].second)
                if (corner != null) {
                    val key = nearest(corner)
                    val firstKey = nearest(first)
                    val secondKey = nearest(second)
                    // Keep distinct stops close to their own centers.
                    val distinctStops = firstKey != secondKey && distance(first, firstKey) < 0.32 && distance(second, secondKey) < 0.32
                    if (!distinctStops && distance(corner, key) <= 0.42) {
                        anchors[i] = firstIndex to corner.copy(t = first.t)
                        anchors.removeAt(i + 1)
                    }
                }
            }
            i++
        }
        if (anchors.size < 3 || fixed(path.lastIndex)) return anchors
        val end = path.last()
        val previous = anchors[anchors.lastIndex - 1].second
        val dx = end.x - previous.x
        val dy = end.y - previous.y
        val length = hypot(dx, dy)
        if (length < 0.18) return anchors
        val extension = minOf(0.26 / length, 0.75)
        val ahead = end.copy(x = end.x + dx * extension, y = end.y + dy * extension)
        val currentKey = nearest(end)
        val nextKey = nearest(ahead)
        if (currentKey != nextKey && distance(end, nextKey) - distance(end, currentKey) <= 0.20 &&
            distance(ahead, nextKey) < distance(end, nextKey)) {
            anchors[anchors.lastIndex] = path.lastIndex to ahead
        }
        return anchors
    }

    private fun roundedCorner(before: Point, first: Point, second: Point, after: Point): Point? {
        val inX = first.x - before.x
        val inY = first.y - before.y
        val backX = second.x - after.x
        val backY = second.y - after.y
        val inLength = hypot(inX, inY)
        val backLength = hypot(backX, backY)
        val dx = second.x - first.x
        val dy = second.y - first.y
        if (inLength < 0.25 || backLength < 0.25 || hypot(dx, dy) > 1.65) return null
        val cross = inX * backY - inY * backX
        if (abs(cross) < inLength * backLength * 0.38) return null
        val along = (dx * backY - dy * backX) / cross
        val back = (dx * inY - dy * inX) / cross
        if (along * inLength !in 0.08..1.25 || back * backLength !in 0.08..1.25) return null
        return Point(first.x + inX * along, first.y + inY * along)
    }

    @Suppress("LongParameterList")
    fun decode(
        points: List<Point>, keys: List<Key>, table: DoublePinyin.Table, inventory: Set<String>,
        compact: Boolean, dict: ConversionDictionary, ngrams: CjkNgrams,
        user: Lattice.UserData? = null, context: String? = null, additionalCodes: List<String> = emptyList(), pauseWeight: Double = 2.0, referenceKeys: List<Key>? = null,
        live: Boolean = false,
        correction: PinyinCorrection.Tuning? = PinyinCorrection.Tuning(),
    ): List<Result> {
        if (dict.isEmpty || inventory.isEmpty()) return emptyList()
        val codes = DoublePinyin.codesOf(table, inventory)
        val search = Search(points, keys, codes, pauseWeight, live)
        val intended = search.intended
        val shapeWeight = if (compact && intended.size >= 7) 1.35 else 2.0
        if (compact && intended.size < 2) return emptyList()
        val repeats = intended.zipWithNext().filter { it.first == it.second }.map { keys[it.first].letters }
        val pairCode=if(intended.size==2) intended.joinToString("") { keys[it].letters.take(1) } else null
        val endpointPath=pairCode?.let { Path(it,search.cost(it)) }?.takeIf { it.cost.isFinite() }
        val paths = (listOfNotNull(endpointPath) + search.paths() + additionalCodes.filter { it.length in 2..MAX_KEYS && it.length % 2 == 0 }
            .map { Path(it, search.cost(it)) }).filter { it.cost.isFinite() }
            .sortedBy { it.cost }.distinctBy { path -> path.code.map { c -> keys.indexOfFirst { c in it.letters } } }.take(POOL)
        val graphs=HashMap<String,Lattice.Graph>()
        fun graphFor(path: Path): Lattice.Graph? {
            graphs[path.code]?.let { return it }
            val graph=if(compact) {
                if (path.code.length == 2) {
                    val group = keys.first { path.code[0] in it.letters }.letters
                    if (path.code[1] in group && group !in repeats) return null
                }
                compactGraph(CompactPinyin.encode(path.code, keys.map { it.letters }), table, inventory)
            } else {
                val segments=DoublePinyin.segments(path.code,table,inventory)
                if(segments.any { it.syllable !in inventory }) return null
                DoublePinyin.fuzzyGraph(path.code,table,inventory)
            }
            graphs[path.code]=graph
            return graph
        }
        fun rankWords(path: Path,graph: Lattice.Graph): List<Result> {
            val units=path.code.length/2
            return rankedReadings(graph, path.code.length, dict, ngrams, user, context).map { word ->
                val languageScore=(word.score+ln(dict.totalFreq.coerceAtLeast(1).toDouble()))/units
                val penalty = if (path.neighbour) correction?.glidePenalty ?: 0.0 else 0.0
                Result(path.code,word.reading,languageScore-path.cost*shapeWeight-units*0.5-penalty,path.cost,path.neighbour,raw=if(compact) CompactPinyin.encode(path.code,keys.map { it.letters }) else path.code)
            }
        }
        val endpointReadings=if(endpointPath!=null) syllables(keys[intended[0]].letters,keys[intended[1]].letters,table,inventory,compact,dict,ngrams,user).map {
            it.copy(code=endpointPath.code,cost=endpointPath.cost,
                score=it.score+ln(dict.totalFreq.coerceAtLeast(1).toDouble())-endpointPath.cost*2.0-0.5)
        } else emptyList()
        fun followsPair(result: Result)=endpointPath!=null && result.code==endpointPath.code && endpointReadings.any { it.reading==result.reading }
        val direct=(endpointReadings+paths.flatMap { path -> graphFor(path)?.let { rankWords(path,it) }.orEmpty() })
            .sortedWith(compareByDescending<Result> { followsPair(it) }.thenByDescending { it.score })
        val repaired = if (correction == null) emptyList() else {
            val seeds = direct.distinctBy { it.code }.take(3).mapNotNull { result -> paths.find { it.code == result.code } }
            search.neighbours(seeds, correction.glideReach).flatMap { path ->
                graphFor(path)?.let { rankWords(path, it) }.orEmpty()
            }
        }
        val ranked=(direct+repaired).sortedWith(compareByDescending<Result> { followsPair(it) }.thenByDescending { it.score })
        val variants=mutableListOf<Result>()
        if(compact && !live) {
            // Diversity is bounded to the leading physical path, two initial syllables and a beam of four.
            // A dictionary whole word must not suppress a composable reading such as ni-xi-huan.
            data class Prefix(val graph: Lattice.Graph,val node: Int,val score: Double)
            for(best in ranked.filterNot { it.neighbour }.distinctBy { it.code }.take(1)) {
                val path=paths.find { it.code==best.code } ?: continue
                var frontier=listOf(Prefix(graphFor(path) ?: continue,0,best.score))
                repeat(minOf(12,best.code.length/2)) {
                    val next=mutableListOf<Prefix>()
                    for(prefix in frontier) {
                        val edges=prefix.graph.arcs[prefix.node]
                        for(edge in edges.take(4)) {
                            if(edges.size==1) { next+=Prefix(prefix.graph,edge.to,prefix.score);continue }
                            val arcs=prefix.graph.arcs.copyOf();arcs[prefix.node]=listOf(edge)
                            val graph=Lattice.Graph(prefix.graph.consumed,arcs)
                            val choices=rankWords(path,graph)
                            variants+=choices
                            choices.firstOrNull()?.let { next+=Prefix(graph,edge.to,it.score) }
                        }
                    }
                    frontier=next.sortedByDescending { it.score }.take(1)
                }
            }
        }
        val primary=ranked.distinctBy { it.reading }.take(8)
        val seen=primary.mapTo(HashSet()) { it.reading }
        val diverse=(variants+ranked.filterNot { it.neighbour }).sortedByDescending { it.score }
            .distinctBy { it.reading }.filter { it.reading !in seen }.take(8)
        seen.addAll(diverse.map { it.reading })
        val corrected=ranked.filter { it.neighbour && it.reading !in seen }.distinctBy { it.code }.distinctBy { it.reading }.take(64)
        val kept=primary+diverse+corrected
        val measurement = if(referenceKeys==null) search else Search(points,referenceKeys,codes,0.0)
        val errors=HashMap<String,Deviation>()
        return kept.map { result ->
            val error=errors.getOrPut(result.code) { measurement.deviation(result.code) }
            result.copy(deviation=error.rms,maxDeviation=error.maximum)
        }
    }

    private fun compactGraph(raw: String, table: DoublePinyin.Table, inventory: Set<String>): Lattice.Graph {
        val edges = CompactPinyin.doubleGraph(raw, table, inventory)
        return Lattice.Graph(IntArray(edges.size) { it }, Array(edges.size) { i ->
            edges[i].map { Lattice.Arc(it.to, it.reading, it.cost, guess = it.cost < 0.0) }
        })
    }

    private fun rankedReadings(
        graph: Lattice.Graph, length: Int, dict: ConversionDictionary, ngrams: CjkNgrams,
        user: Lattice.UserData?, context: String?,
    ) = Lattice.decode(graph, dict, ngrams,
        Lattice.Opts(
            limit = 16, maxAmbiguousSpan = Int.MAX_VALUE, coverageOrder = true, hanLevels = true,
            beam = if (length >= 10) 16 else 8, pathsPerStretch = if (length >= 10) 12 else 4,
        ),
        user, Lattice.UserScope.ALL, context,
    ).filter { it.consumed == length }.distinctBy { it.reading }.take(4)

    /** Decode the deliberate visits on a short merged-key stroke in one dictionary pass. */
    fun compactPathReadings(
        groups: List<String>, table: DoublePinyin.Table, inventory: Set<String>,
        dict: ConversionDictionary, ngrams: CjkNgrams, user: Lattice.UserData?, context: String?,
    ): List<Result> {
        if (groups.size !in 4..6 || groups.size % 2 != 0 || dict.isEmpty) return emptyList()
        val code = groups.joinToString("") { it.take(1) }
        val raw = CompactPinyin.encode(code, groups)
        val graph = compactGraph(raw, table, inventory)
        if (raw.indices.step(2).any { graph.arcs[it].isEmpty() }) return emptyList()
        return rankedReadings(graph, raw.length, dict, ngrams, user, context).map {
            Result(code, it.reading, it.score, 0.0, raw = raw)
        }
    }

    /** Finish the final turns when the background preview still describes a shorter stroke. */
    fun compactLiftReadings(
        points: List<Point>, keys: List<Key>, table: DoublePinyin.Table, inventory: Set<String>,
        dict: ConversionDictionary, ngrams: CjkNgrams, user: Lattice.UserData?, context: String?,
    ): List<Result> {
        if (dict.isEmpty) return emptyList()
        val search = Search(points, keys, DoublePinyin.codesOf(table, inventory))
        val groups = keys.map { it.letters }
        return search.anchorPaths().sortedBy { it.cost }.take(5).flatMap { path ->
            val raw = CompactPinyin.encode(path.code, groups)
            rankedReadings(compactGraph(raw, table, inventory), raw.length, dict, ngrams, user, context).map { candidate ->
                val units = raw.length / 2
                val score = (candidate.score + ln(dict.totalFreq.coerceAtLeast(1).toDouble())) / units -
                    path.cost * 1.35 - units * 0.5
                Result(path.code, candidate.reading, score, path.cost, raw = raw)
            }
        }.sortedByDescending { it.score }.distinctBy { it.reading }.take(8)
    }

    /** Only the finite letters of two physical keys, never whole-stroke combinations. */
    fun syllables(a: String,b: String,table: DoublePinyin.Table,inventory: Set<String>,compact: Boolean,
        dict: ConversionDictionary,ngrams: CjkNgrams,user: Lattice.UserData? = null): List<Result> {
        return a.flatMap { first -> b.map { second -> "$first$second" } }.flatMap { code ->
            val typed=DoublePinyin.translate(code,table,inventory)
            if(typed !in inventory) emptyList() else PinyinFuzzy.costed(typed,inventory).map { (reading,penalty) ->
                val graph=Lattice.graph(Lattice.input(listOf(reading),listOf(2)))
                val score=Lattice.decode(graph,dict,ngrams,Lattice.Opts(limit=1),user,Lattice.UserScope.ALL)
                    .firstOrNull()?.score ?: -100.0
                val raw=if(compact) CompactPinyin.key(code.take(1),a)+CompactPinyin.key(code.takeLast(1),b) else code
                Result(code,reading,score+penalty,0.0,raw=raw)
            }
        }.sortedByDescending { it.score }.distinctBy { it.reading }
    }

    /** Exact physical-key pairs only: at most four direct lookups, without language scoring. */
    fun exactSyllables(a: String,b: String,table: DoublePinyin.Table,inventory: Set<String>,compact: Boolean): List<Result> =
        a.flatMap { first -> b.mapNotNull { second ->
            val code="$first$second"
            val reading=DoublePinyin.translate(code,table,inventory)
            if(reading !in inventory) null else Result(code,reading,0.0,0.0,
                raw=if(compact) CompactPinyin.key(first.toString(),a)+CompactPinyin.key(second.toString(),b) else code)
        } }.distinctBy { it.reading }

    fun exactBufferFor(result: Result,compact: Boolean): String =
        if(compact) CompactReadingBuffer.selected(result.raw ?: result.code,result.reading) else result.code

    fun syllableBuffers(buffer: String,table: DoublePinyin.Table,inventory: Set<String>,compact: Boolean): List<Pair<String,String>> {
        if(compact) return CompactReadingBuffer.parts(buffer).mapNotNull { part ->
            part.reading?.let { it to buffer.substring(part.start,part.end) }
        }
        var offset=0
        return DoublePinyin.segments(buffer,table,inventory).map { part ->
            val raw=buffer.substring(offset,offset+part.inputLen);offset+=part.inputLen
            part.syllable to raw
        }
    }

    /** An explicit full-reading pick locks every syllable while keeping the original two-key spans. */
    fun bufferFor(result: Result, table: DoublePinyin.Table, inventory: Set<String>, compact: Boolean): String? {
        if(!compact) {
            if(DoublePinyin.translate(result.code,table,inventory)==result.reading) return result.code
            data class Prefix(val offset: Int,val code: String)
            var frontier=listOf(Prefix(0,""))
            val spelling=DoublePinyin.keysOf(table,inventory)
            for(unit in DoublePinyin.segments(result.code,table,inventory)) {
                frontier=frontier.flatMap { prefix -> PinyinFuzzy.costed(unit.syllable,inventory).mapNotNull { (reading,_) ->
                    if(!result.reading.startsWith(reading,prefix.offset)) null else spelling[reading]?.let {
                        Prefix(prefix.offset+reading.length,prefix.code+it)
                    }
                } }.distinctBy { it.offset }
            }
            return frontier.firstOrNull { it.offset==result.reading.length }?.code
        }
        val raw=result.raw ?: CompactPinyin.encode(result.code)
        val graph=CompactPinyin.doubleGraph(raw,table,inventory)
        data class Step(val node: Int,val offset: Int,val readings: List<String>)
        var frontier=listOf(Step(0,0,emptyList()))
        while(frontier.isNotEmpty()) {
            val next=mutableListOf<Step>()
            for(step in frontier) {
                if(step.node==raw.length && step.offset==result.reading.length) {
                    return step.readings.mapIndexed { i,reading -> CompactReadingBuffer.selected(raw.substring(i*2,i*2+2),reading) }.joinToString("")
                }
                for(edge in graph[step.node]) if(result.reading.startsWith(edge.reading,step.offset))
                    next+=Step(edge.to,step.offset+edge.reading.length,step.readings+edge.reading)
            }
            frontier=next.distinctBy { it.node to it.offset }
        }
        return null
    }

    private fun distance(p: Point, k: Key) = hypot(p.x - k.x, p.y - k.y)
    private fun resample(points: List<Point>, sampleCount: Int): List<Point> {
        val clean = points.filter { it.x.isFinite() && it.y.isFinite() }.fold(mutableListOf<Point>()) { acc, p -> acc.apply {
            if (lastOrNull() != p) add(p)
        } }
        if (clean.size < 2) return emptyList()
        val arc = DoubleArray(clean.size)
        for (i in 1 until clean.size) arc[i] = arc[i - 1] + hypot(clean[i].x - clean[i - 1].x, clean[i].y - clean[i - 1].y)
        if (arc.last() < 0.05) return emptyList()
        var index = 1
        return List(sampleCount) { j ->
            val at = arc.last() * j / (sampleCount - 1)
            while (index < clean.lastIndex && (arc[index] < at || arc[index] == arc[index - 1])) index++
            val span = arc[index] - arc[index - 1]
            val f = if (span > 0.0) (at - arc[index - 1]) / span else 1.0
            Point(clean[index - 1].x + (clean[index].x - clean[index - 1].x) * f,
                clean[index - 1].y + (clean[index].y - clean[index - 1].y) * f,
                (clean[index - 1].t + (clean[index].t - clean[index - 1].t) * f).toLong())
        }
    }
}
