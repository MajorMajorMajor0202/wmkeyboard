package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
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
    class Search(rawPoints: List<Point>, private val keys: List<Key>, codes: Collection<String>, private val pauseWeight: Double = 2.0) {
        private val source = boundedPoints(rawPoints)
        private val loops = loops(source, keys)
        // A local circle is a repeat mark, not travel to neighbouring keys.
        private val points = source.mapIndexedNotNull { i, p ->
            val loop = loops.firstOrNull { i in it.start..it.end }
            when {
                loop == null -> p
                i == loop.start || i == loop.end -> Point(keys[loop.key].x, keys[loop.key].y, p.t)
                else -> null
            }
        }
        private val samples = resample(points).ifEmpty {
            if (loops.isNotEmpty()) listOf(points.first(), points.last()) else emptyList()
        }
        private val arc = DoubleArray(samples.size)
        private val pairs = codes.filter { it.length == 2 }.mapNotNull { code ->
            val a = keys.indexOfFirst { code[0] in it.letters }
            val b = keys.indexOfFirst { code[1] in it.letters }
            if (a < 0 || b < 0) null else a to b
        }.toSet()
        private val firstKeys = BooleanArray(keys.size) { k -> pairs.any { it.first == k } }
        private val allowed = Array(keys.size) { a -> BooleanArray(keys.size) { b -> a to b in pairs } }
        private val distances = Array(keys.size) { a -> DoubleArray(keys.size) { b -> hypot(keys[a].x - keys[b].x, keys[a].y - keys[b].y) } }
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
        init { for (i in 1 until samples.size) arc[i] = arc[i - 1] + hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y) }

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
                frontier = next.sortedBy { it.bound }.take(BEAM)
                if (frontier.isEmpty()) break
            }
            val intended = intentKeys(source, keys)
            val explicit = intended.joinToString("") { keys[it].letters.take(1) }.takeIf { code ->
                code.length in 2..MAX_KEYS && code.length % 2 == 0 &&
                    intended.chunked(2).all { allowed[it[0]][it[1]] }
            }
            val corners = explicit ?: cornerCode()
            val ordered = found.sortedBy { it.cost }.distinctBy { it.code }
            // Preserve long syllable paths before cheap shortcuts fill the global pool.
            val lengths = ordered.groupBy { it.code.length }.values.flatMap { it.take(2) }
            val kept = (lengths + ordered).distinctBy { it.code }.take(POOL - 1)
            return (kept + listOfNotNull(corners?.let { Path(it, cost(it)) })).distinctBy { it.code }.sortedBy { it.cost }
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
            row.last() + loops.groupingBy { it.key }.eachCount().entries.sumOf { (key, count) ->
                val doubled = ids.zipWithNext().count { it.first == key && it.second == key }
                (count - doubled).coerceAtLeast(0) * 4.0
            }
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
                    val cost = GAP * abs(arc[j] - arc[i] - span) + pointCosts[to][j] + 0.35 + remaining[j][to]
                    if (cost < best) best = cost
                }
                remaining[i][from] = best
            }
            return remaining
        }

        /** Same metric for grammar paths and whole-word paths from the existing native decoder. */
        fun cost(code: String, neighbour: Boolean = false): Double {
            if (samples.size < 2 || code.isEmpty()) return Double.POSITIVE_INFINITY
            val ids = code.map { c -> keys.indexOfFirst { c in it.letters } }
            if (ids.any { it < 0 } || distance(samples.first(), keys[ids.first()]) > (if (neighbour) 2.2 else 1.2) || distance(samples.last(), keys[ids.last()]) > (if (neighbour) 2.2 else 1.2)) return Double.POSITIVE_INFINITY
            var col = DoubleArray(samples.size) { if (it == 0) pointCosts[ids[0]][0] else Double.POSITIVE_INFINITY }
            for (i in 1 until ids.size) col = advance(col, ids[i - 1], ids[i])
            return col.last() + pivotPenalty(ids.fold(0) { mask,k -> mask or (1 shl k) }) + sequencePenalty(code)
        }

        /** One adjacent physical-key repair only, with a separate bounded correction budget. */
        fun neighbours(paths: List<Path>): List<Path> {
            val repairs = mutableMapOf<String, Path>()
            for (path in paths.take(8)) for (index in path.code.indices) {
                val from = keys.indexOfFirst { path.code[index] in it.letters }
                if (from < 0) continue
                val nearby = keys.indices.filter { it != from && distances[from][it] <= 2.2 }.sortedBy { distances[from][it] }.take(8)
                for (key in nearby) {
                    val code = path.code.replaceRange(index,index+1,keys[key].letters.take(1))
                    val ids = code.map { c -> keys.indexOfFirst { c in it.letters } }
                    if (ids.chunked(2).any { it.size != 2 || !allowed[it[0]][it[1]] }) continue
                    val cost = cost(code, neighbour = true)
                    if (cost.isFinite()) repairs[code] = Path(code,cost,true)
                }
            }
            val originals = paths.mapTo(HashSet()) { it.code }
            return repairs.values.filter { it.code !in originals }.sortedBy { it.cost }.take(POOL)
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
                column=DoubleArray(samples.size) { j ->
                    var best=Double.POSITIVE_INFINITY
                    for(i in 0..j) {
                        val cost=column[i]+GAP*abs(arc[j]-arc[i]-span)+pointCosts[ids[letter]][j]+0.35
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
            val out = DoubleArray(samples.size)
            val queue = IntArray(samples.size)
            var head = 0
            var tail = 0
            var boundary = 0
            var left = Double.POSITIVE_INFINITY
            for (j in samples.indices) {
                val rightValue = previous[j] + GAP * arc[j]
                while (tail > head && previous[queue[tail - 1]] + GAP * arc[queue[tail - 1]] >= rightValue) tail--
                queue[tail++] = j
                val target = arc[j] - span
                while (boundary <= j && arc[boundary] <= target) {
                    left = minOf(left, previous[boundary] - GAP * arc[boundary])
                    boundary++
                }
                while (head < tail && queue[head] < boundary) head++
                val right = if (head < tail) previous[queue[head]] + GAP * arc[queue[head]] - GAP * target else Double.POSITIVE_INFINITY
                // Absolute travel mismatch has a sliding range minimum: O(samples), not O(samples squared).
                out[j] = minOf(left + GAP * target, right) + pointCosts[to][j] + 0.35
            }
            return out
        }
    }

    data class Loop(val start: Int, val end: Int, val key: Int)

    private fun boundedPoints(points: List<Point>): List<Point> {
        val valid = points.filter { it.x.isFinite() && it.y.isFinite() }
        return if (valid.size <= 256) valid else List(256) { valid[it * (valid.size - 1) / 255] }
    }

    fun hasLoop(points: List<Point>, keys: List<Key>): Boolean = loops(boundedPoints(points), keys).isNotEmpty()

    /** Closed local area, in either direction. Jitter and out-and-back strokes have no area. */
    fun loops(points: List<Point>, keys: List<Key>): List<Loop> {
        if (keys.isEmpty()) return emptyList()
        val found = mutableListOf<Loop>()
        var start = 0
        while (start < points.size - 4) {
            val anchor = points[start]
            val key = keys.indices.minBy { distance(anchor, keys[it]) }
            val scale = keys[key].width.coerceIn(1.0, 1.4)
            if (distance(anchor, keys[key]) > 0.65 * scale) { start++; continue }
            var minX = anchor.x; var maxX = anchor.x
            var minY = anchor.y; var maxY = anchor.y
            var area = 0.0; var travel = 0.0
            var end = -1
            for (j in start + 1 until points.size) {
                val p = points[j]; val prev = points[j - 1]
                minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
                minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
                val w = maxX - minX; val h = maxY - minY
                if (maxOf(w, h) > 1.15 * scale) break
                area += (prev.x - anchor.x) * (p.y - anchor.y) - (p.x - anchor.x) * (prev.y - anchor.y)
                travel += hypot(p.x - prev.x, p.y - prev.y)
                if (j - start < 4 || minOf(w, h) < 0.16 || maxOf(w, h) < 0.28) continue
                if (hypot(p.x - anchor.x, p.y - anchor.y) > maxOf(0.10 * scale, maxOf(w, h) * 0.28)) continue
                if (abs(area) < w * h * 0.50 || travel < maxOf(w, h) * 2.1) continue
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
    fun intentKeys(points: List<Point>,keys: List<Key>): List<Int> {
        val path=boundedPoints(points)
        if(path.isEmpty() || keys.isEmpty()) return emptyList()
        val loops=loops(path,keys)
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
        for(i in 1 until path.size) if(path[i].t-path[i-1].t>=100L && hypot(path[i].x-path[i-1].x,path[i].y-path[i-1].y)<0.4) chosen.add(i-1)
        for(loop in loops) {
            chosen.removeAll { it in loop.start..loop.end }
            chosen.add(loop.start);chosen.add(loop.end)
        }
        val result=mutableListOf<Int>()
        var previous=-1;var lastIndex=0
        for(index in chosen) {
            val loop=loops.firstOrNull { it.start==index || it.end==index }
            if(loop!=null) {
                if(index==loop.start) {
                    if(result.lastOrNull()!=loop.key) result+=loop.key
                } else result+=loop.key
                previous=loop.key;lastIndex=index
                continue
            }
            val p=path[index]
            val k=keys.indices.minBy { distance(p,keys[it]) }
            if(distance(p,keys[k])>(if(index==0 || index==path.lastIndex) 1.2 else 0.7)) continue
            val repeated=k==previous && path.subList(lastIndex,index+1).any { distance(it,keys[k])>0.85 }
            if(k!=previous || repeated) { result+=k;previous=k;lastIndex=index }
        }
        return result.take(24)
    }

    @Suppress("LongParameterList")
    fun decode(
        points: List<Point>, keys: List<Key>, table: DoublePinyin.Table, inventory: Set<String>,
        compact: Boolean, dict: ConversionDictionary, ngrams: CjkNgrams,
        user: Lattice.UserData? = null, context: String? = null, additionalCodes: List<String> = emptyList(), pauseWeight: Double = 2.0, referenceKeys: List<Key>? = null,
    ): List<Result> {
        if (dict.isEmpty || inventory.isEmpty()) return emptyList()
        val codes = DoublePinyin.codesOf(table, inventory)
        val search = Search(points, keys, codes, pauseWeight)
        val intended=intentKeys(points,keys)
        val pairCode=if(intended.size==2) intended.joinToString("") { keys[it].letters.take(1) } else null
        val endpointPath=pairCode?.let { Path(it,search.cost(it)) }?.takeIf { it.cost.isFinite() }
        val paths = (listOfNotNull(endpointPath) + search.paths() + additionalCodes.filter { it.length in 2..MAX_KEYS && it.length % 2 == 0 }
            .map { Path(it, search.cost(it)) }).filter { it.cost.isFinite() }
            .sortedBy { it.cost }.distinctBy { path -> path.code.map { c -> keys.indexOfFirst { c in it.letters } } }.take(POOL)
        val graphs=HashMap<String,Lattice.Graph>()
        fun graphFor(path: Path): Lattice.Graph? {
            graphs[path.code]?.let { return it }
            val graph=if(compact) {
                val edges=CompactPinyin.doubleGraph(CompactPinyin.encode(path.code,keys.map { it.letters }),table,inventory)
                Lattice.Graph(IntArray(edges.size) { it },Array(edges.size) { i -> edges[i].map { Lattice.Arc(it.to,it.reading,it.cost,guess=it.cost<0.0) } })
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
            return Lattice.decode(graph,dict,ngrams,
                Lattice.Opts(limit=16,maxAmbiguousSpan=Int.MAX_VALUE,coverageOrder=true,hanLevels=true),
                user,Lattice.UserScope.ALL,context).filter { it.consumed==path.code.length }.distinctBy { it.reading }.take(4).map { word ->
                val languageScore=(word.score+ln(dict.totalFreq.coerceAtLeast(1).toDouble()))/units
                Result(path.code,word.reading,languageScore-path.cost*2.0-units*0.5-if(path.neighbour) 2.0 else 0.0,path.cost,path.neighbour,raw=if(compact) CompactPinyin.encode(path.code,keys.map { it.letters }) else path.code)
            }
        }
        val endpointReadings=if(endpointPath!=null) syllables(keys[intended[0]].letters,keys[intended[1]].letters,table,inventory,compact,dict,ngrams,user).map {
            it.copy(code=endpointPath.code,cost=endpointPath.cost,
                score=it.score+ln(dict.totalFreq.coerceAtLeast(1).toDouble())-endpointPath.cost*2.0-0.5)
        } else emptyList()
        fun followsPair(result: Result)=endpointPath!=null && result.code==endpointPath.code && endpointReadings.any { it.reading==result.reading }
        val direct=(endpointReadings+paths.flatMap { path -> graphFor(path)?.let { rankWords(path,it) }.orEmpty() })
            .sortedWith(compareByDescending<Result> { followsPair(it) }.thenByDescending { it.score })
        val seeds=direct.distinctBy { it.code }.take(1).map { result -> paths.first { it.code==result.code } }
        val repaired=search.neighbours(seeds).flatMap { path -> graphFor(path)?.let { rankWords(path,it) }.orEmpty() }
        val ranked=(direct+repaired).sortedWith(compareBy<Result> { it.neighbour }.thenByDescending { followsPair(it) }.thenByDescending { it.score })
        val variants=mutableListOf<Result>()
        if(compact) {
            // Diversity is bounded to the leading physical path, two initial syllables and a beam of four.
            // A dictionary whole word must not suppress a composable reading such as ni-xi-huan.
            data class Prefix(val graph: Lattice.Graph,val node: Int,val score: Double)
            for(best in ranked.filterNot { it.neighbour }.distinctBy { it.code }.take(1)) {
                val path=paths.first { it.code==best.code }
                var frontier=listOf(Prefix(graphFor(path)!!,0,best.score))
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
        val primary=ranked.filterNot { it.neighbour }.distinctBy { it.reading }.take(8)
        val seen=primary.mapTo(HashSet()) { it.reading }
        val diverse=(variants+ranked.filterNot { it.neighbour }.drop(8)).sortedByDescending { it.score }
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
    private fun resample(points: List<Point>): List<Point> {
        val clean = points.filter { it.x.isFinite() && it.y.isFinite() }.fold(mutableListOf<Point>()) { acc, p -> acc.apply {
            if (lastOrNull() != p) add(p)
        } }
        if (clean.size < 2) return emptyList()
        val arc = DoubleArray(clean.size)
        for (i in 1 until clean.size) arc[i] = arc[i - 1] + hypot(clean[i].x - clean[i - 1].x, clean[i].y - clean[i - 1].y)
        if (arc.last() < 0.05) return emptyList()
        var index = 1
        return List(SAMPLES) { j ->
            val at = arc.last() * j / (SAMPLES - 1)
            while (index < clean.lastIndex && (arc[index] < at || arc[index] == arc[index - 1])) index++
            val span = arc[index] - arc[index - 1]
            val f = if (span > 0.0) (at - arc[index - 1]) / span else 1.0
            Point(clean[index - 1].x + (clean[index].x - clean[index - 1].x) * f,
                clean[index - 1].y + (clean[index].y - clean[index - 1].y) * f,
                (clean[index - 1].t + (clean[index].t - clean[index - 1].t) * f).toLong())
        }
    }
}
