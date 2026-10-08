package com.vetalkuim.maraudersmap

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** Точка на экране, в пикселях. */
data class Point(val x: Float, val y: Float)

/** Край экрана, через который путник приходит или уходит. */
enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

/**
 * Где путникам можно ходить: сетка клеток размером [cell] пикселей поверх экрана.
 * Клетка свободна, если в ней и рядом с ней нет чернил карты — следы не ложатся на рисунок.
 * Годятся только свободные области, выходящие к краю экрана: путники приходят с края и уходят за него.
 * Без такой области путники ходят по полосам в 10 % экрана у каждого края ([frame]).
 * Без Android: маску чернил готовит [MapRenderer].
 */
class WalkArea private constructor(
    val width: Float,
    val height: Float,
    val cell: Float,
    val cols: Int,
    val rows: Int,
    /** Номер области для каждой клетки; −1 — сюда ходить нельзя. */
    private val region: IntArray,
    /** true — запасной вариант: полосы у краёв вместо свободной от рисунка бумаги. */
    val isFrame: Boolean,
) {
    private val freeCells = region.indices.filter { region[it] >= 0 }.toIntArray()
    private val borderCells = freeCells.filter { isBorder(it) }.toIntArray()
    private val cellsByRegion = freeCells.groupBy { region[it] }

    /** Есть хоть одна клетка, куда можно ступить. */
    val isEmpty: Boolean get() = freeCells.isEmpty()

    fun isFree(x: Float, y: Float): Boolean = regionAt(x, y) >= 0

    /** Номер области в точке; −1 — нельзя ходить или за краем экрана. */
    fun regionAt(x: Float, y: Float): Int {
        val c = indexAt(x, y)
        return if (c < 0) -1 else region[c]
    }

    /** Случайная свободная точка; с [inRegion] — только в этой области. */
    fun randomPoint(random: Random, inRegion: Int = -1): Point? {
        if (inRegion < 0) {
            if (freeCells.isEmpty()) return null
            return center(freeCells[random.nextInt(freeCells.size)])
        }
        val cells = cellsByRegion[inRegion] ?: return null
        return center(cells[random.nextInt(cells.size)])
    }

    /** Ближайшая к ([x], [y]) свободная точка; с [inRegion] — только в этой области. */
    fun nearestFree(x: Float, y: Float, inRegion: Int = -1): Point? {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (c in freeCells) {
            if (inRegion >= 0 && region[c] != inRegion) continue
            val p = center(c)
            val d = (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y)
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return if (best < 0) null else center(best)
    }

    /** Случайная свободная клетка у края экрана — отсюда входит новый путник. */
    fun randomEntry(random: Random): Pair<Point, Edge>? {
        if (borderCells.isEmpty()) return null
        val c = borderCells[random.nextInt(borderCells.size)]
        return center(c) to edgeOf(c)
    }

    /** Ближайшая по пути клетка у края в той же области — туда уходит путник. */
    fun nearestExit(x: Float, y: Float): Pair<Point, Edge>? {
        val start = indexAt(x, y)
        if (start < 0 || region[start] < 0) return null
        val seen = BooleanArray(region.size)
        val queue = ArrayDeque<Int>()
        queue.addLast(start)
        seen[start] = true
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (isBorder(c)) return center(c) to edgeOf(c)
            val col = c % cols
            val row = c / cols
            for ((dc, dr) in ORTHOGONAL) {
                val nc = col + dc
                val nr = row + dr
                if (nc !in 0 until cols || nr !in 0 until rows) continue
                val n = nr * cols + nc
                if (!seen[n] && region[n] >= 0) {
                    seen[n] = true
                    queue.addLast(n)
                }
            }
        }
        return null
    }

    /**
     * Путь по свободным клеткам из ([x0], [y0]) в ([x1], [y1]): точки поворота без начальной,
     * последняя — сама цель. Прямые участки спрямлены, где путь виден насквозь. null — пути нет.
     */
    fun path(x0: Float, y0: Float, x1: Float, y1: Float): List<Point>? {
        val start = indexAt(x0, y0)
        val goal = indexAt(x1, y1)
        if (start < 0 || goal < 0 || region[start] < 0 || region[goal] < 0) return null
        if (region[start] != region[goal]) return null
        if (start == goal || lineOfSight(x0, y0, x1, y1)) return listOf(Point(x1, y1))

        val cost = FloatArray(region.size) { Float.MAX_VALUE }
        val from = IntArray(region.size) { -1 }
        val closed = BooleanArray(region.size)
        val goalCol = goal % cols
        val goalRow = goal / cols
        fun estimate(c: Int): Float {
            val dc = abs(c % cols - goalCol).toFloat()
            val dr = abs(c / cols - goalRow).toFloat()
            return max(dc, dr) + (SQRT2 - 1f) * min(dc, dr)
        }
        val open = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        cost[start] = 0f
        open.add(estimate(start) to start)
        while (open.isNotEmpty()) {
            val c = open.poll().second
            if (closed[c]) continue
            if (c == goal) break
            closed[c] = true
            val col = c % cols
            val row = c / cols
            for ((dc, dr) in NEIGHBOURS) {
                val nc = col + dc
                val nr = row + dr
                if (nc !in 0 until cols || nr !in 0 until rows) continue
                val n = nr * cols + nc
                if (region[n] < 0 || closed[n]) continue
                // По диагонали — только если не срезаем угол рисунка.
                if (dc != 0 && dr != 0 && (region[row * cols + nc] < 0 || region[nr * cols + col] < 0)) continue
                val step = if (dc != 0 && dr != 0) SQRT2 else 1f
                val g = cost[c] + step
                if (g < cost[n]) {
                    cost[n] = g
                    from[n] = c
                    open.add(g + estimate(n) to n)
                }
            }
        }
        if (from[goal] < 0) return null

        val cells = ArrayList<Int>()
        var c = goal
        while (c != start) {
            cells += c
            c = from[c]
        }
        cells.reverse()
        // Спрямление: из текущей точки идём к самой дальней клетке, видной насквозь.
        val points = cells.map(::center).toMutableList()
        points[points.lastIndex] = Point(x1, y1)
        val result = ArrayList<Point>()
        var px = x0
        var py = y0
        var i = 0
        while (i < points.size) {
            var j = points.lastIndex
            while (j > i && !lineOfSight(px, py, points[j].x, points[j].y)) j--
            result += points[j]
            px = points[j].x
            py = points[j].y
            i = j + 1
        }
        return result
    }

    /** Отрезок целиком проходит по свободным клеткам. */
    fun lineOfSight(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val length = hypot(x1 - x0, y1 - y0)
        val steps = max(1, ceil(length / (cell / 3f)).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            if (!isFree(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)) return false
        }
        return true
    }

    private fun indexAt(x: Float, y: Float): Int {
        if (x < 0f || y < 0f || x >= width || y >= height) return -1
        val col = min((x / cell).toInt(), cols - 1)
        val row = min((y / cell).toInt(), rows - 1)
        return row * cols + col
    }

    private fun center(c: Int): Point {
        val x = min((c % cols + 0.5f) * cell, width - 1f)
        val y = min((c / cols + 0.5f) * cell, height - 1f)
        return Point(x, y)
    }

    private fun isBorder(c: Int): Boolean {
        val col = c % cols
        val row = c / cols
        return col == 0 || row == 0 || col == cols - 1 || row == rows - 1
    }

    private fun edgeOf(c: Int): Edge {
        val col = c % cols
        val row = c / cols
        val toLeft = col
        val toRight = cols - 1 - col
        val toTop = row
        val toBottom = rows - 1 - row
        return when (minOf(toLeft, toRight, toTop, toBottom)) {
            toLeft -> Edge.LEFT
            toRight -> Edge.RIGHT
            toTop -> Edge.TOP
            else -> Edge.BOTTOM
        }
    }

    companion object {
        /** Размер клетки сетки. */
        const val CELL_DP = 6f

        /** Сколько чистой бумаги оставлять между следом и рисунком. */
        const val CLEARANCE_DP = 12f

        /** Область меньше этой доли экрана слишком тесна для прогулки. */
        const val MIN_AREA = 0.03f

        /** Запасной вариант: ширина полос у краёв — доля ширины и высоты экрана. */
        const val FRAME = 0.1f

        private val SQRT2 = sqrt(2f)
        private val ORTHOGONAL = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val NEIGHBOURS = ORTHOGONAL + listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

        fun gridSize(width: Float, height: Float, cell: Float): Pair<Int, Int> =
            max(1, ceil(width / cell).toInt()) to max(1, ceil(height / cell).toInt())

        /**
         * Свободная от рисунка бумага. [ink] — сетка [gridSize] по строкам: true, если в клетке есть чернила.
         * Если свободных областей у края не хватает для прогулки — полосы у краёв ([frame]).
         */
        fun fromInk(width: Float, height: Float, cell: Float, dp: Float, ink: BooleanArray): WalkArea {
            val (cols, rows) = gridSize(width, height, cell)
            require(ink.size == cols * rows)
            val blocked = dilate(ink, cols, rows, ceil(CLEARANCE_DP * dp / cell).toInt())
            val region = IntArray(cols * rows) { -1 }
            val minCells = (cols * rows * MIN_AREA).toInt()
            var next = 0
            val seen = BooleanArray(cols * rows)
            for (startCell in 0 until cols * rows) {
                if (seen[startCell] || blocked[startCell]) continue
                // Область целиком: клетки, соединённые по сторонам.
                val cells = ArrayList<Int>()
                var touchesEdge = false
                val queue = ArrayDeque<Int>()
                queue.addLast(startCell)
                seen[startCell] = true
                while (queue.isNotEmpty()) {
                    val c = queue.removeFirst()
                    cells += c
                    val col = c % cols
                    val row = c / cols
                    if (col == 0 || row == 0 || col == cols - 1 || row == rows - 1) touchesEdge = true
                    for ((dc, dr) in ORTHOGONAL) {
                        val nc = col + dc
                        val nr = row + dr
                        if (nc !in 0 until cols || nr !in 0 until rows) continue
                        val n = nr * cols + nc
                        if (!seen[n] && !blocked[n]) {
                            seen[n] = true
                            queue.addLast(n)
                        }
                    }
                }
                if (touchesEdge && cells.size >= minCells) {
                    for (c in cells) region[c] = next
                    next++
                }
            }
            if (next == 0) return frame(width, height, cell)
            return WalkArea(width, height, cell, cols, rows, region, isFrame = false)
        }

        /** Полосы шириной [FRAME] экрана вдоль всех четырёх краёв — одна область по кругу. */
        fun frame(width: Float, height: Float, cell: Float): WalkArea {
            val (cols, rows) = gridSize(width, height, cell)
            val region = IntArray(cols * rows) { c ->
                val x = (c % cols + 0.5f) * cell
                val y = (c / cols + 0.5f) * cell
                val inFrame = x < width * FRAME || x > width * (1f - FRAME) ||
                    y < height * FRAME || y > height * (1f - FRAME)
                if (inFrame) 0 else -1
            }
            return WalkArea(width, height, cell, cols, rows, region, isFrame = true)
        }

        /** Расширяет чернила на [radius] клеток по кругу: два прохода — по строкам и по столбцам. */
        private fun dilate(ink: BooleanArray, cols: Int, rows: Int, radius: Int): BooleanArray {
            if (radius <= 0) return ink.copyOf()
            // Расстояние до ближайших чернил считается по квадрату; круг — проверкой суммы квадратов.
            val inf = Int.MAX_VALUE / 4
            val dx = IntArray(cols * rows) { inf }
            for (row in 0 until rows) {
                var last = -inf
                for (col in 0 until cols) {
                    if (ink[row * cols + col]) last = col
                    dx[row * cols + col] = min(dx[row * cols + col], col - last)
                }
                last = inf
                for (col in cols - 1 downTo 0) {
                    if (ink[row * cols + col]) last = col
                    dx[row * cols + col] = min(dx[row * cols + col], last - col)
                }
            }
            val blocked = BooleanArray(cols * rows)
            val r2 = radius * radius
            for (col in 0 until cols) {
                for (row in 0 until rows) {
                    var hit = false
                    val from = max(0, row - radius)
                    val to = min(rows - 1, row + radius)
                    for (r in from..to) {
                        val h = dx[r * cols + col]
                        if (h <= radius && h * h + (r - row) * (r - row) <= r2) {
                            hit = true
                            break
                        }
                    }
                    blocked[row * cols + col] = hit
                }
            }
            return blocked
        }
    }
}
