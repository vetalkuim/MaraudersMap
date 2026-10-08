package com.vetalkuim.maraudersmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class WalkAreaTest {

    private val dp = 2f
    private val screenW = 1080f
    private val screenH = 2400f
    private val cell = WalkArea.CELL_DP * dp

    /** Сетка чернил, где [drawn] возвращает true для закрашенных точек экрана. */
    private fun ink(drawn: (Float, Float) -> Boolean): BooleanArray {
        val (cols, rows) = WalkArea.gridSize(screenW, screenH, cell)
        return BooleanArray(cols * rows) { c -> drawn((c % cols + 0.5f) * cell, (c / cols + 0.5f) * cell) }
    }

    /** Рисунок как у карты: большой блок посередине с полями сверху и снизу и узкими по бокам. */
    private fun castle(x: Float, y: Float) = x in 60f..1020f && y in 500f..1900f

    private fun area(drawn: (Float, Float) -> Boolean) = WalkArea.fromInk(screenW, screenH, cell, dp, ink(drawn))

    @Test
    fun `на рисунке и рядом с ним ходить нельзя, на чистой бумаге — можно`() {
        val area = area(::castle)
        assertFalse(area.isFrame)
        assertFalse(area.isFree(540f, 1200f))
        assertFalse(area.isFree(540f, 500f - (WalkArea.CLEARANCE_DP - 4f) * dp))
        assertTrue(area.isFree(540f, 200f))
        assertTrue(area.isFree(540f, 2200f))
        assertTrue(area.isFree(20f, 1200f))
    }

    @Test
    fun `путь обходит рисунок`() {
        val area = area(::castle)
        val path = area.path(540f, 200f, 540f, 2200f)
        assertNotNull(path)
        path!!
        assertEquals(Point(540f, 2200f), path.last())
        var x = 540f
        var y = 200f
        for (p in path) {
            assertTrue(area.lineOfSight(x, y, p.x, p.y))
            x = p.x
            y = p.y
        }
    }

    @Test
    fun `островок внутри рисунка не используется — туда не войти с края`() {
        val area = area { x, y -> castle(x, y) && !(x in 300f..780f && y in 900f..1500f) }
        assertFalse(area.isFree(540f, 1200f))
    }

    @Test
    fun `если свободной бумаги у края нет — полосы по 10 процентов экрана`() {
        val area = area { _, _ -> true }
        assertTrue(area.isFrame)
        assertTrue(area.isFree(screenW * 0.05f, screenH / 2))
        assertTrue(area.isFree(screenW / 2, screenH * 0.95f))
        assertFalse(area.isFree(screenW * 0.15f, screenH * 0.15f))
        assertFalse(area.isFree(screenW / 2, screenH / 2))
    }

    @Test
    fun `путники ходят и оставляют следы только на чистой бумаге`() {
        val inkGrid = ink(::castle)
        val (cols, _) = WalkArea.gridSize(screenW, screenH, cell)
        fun onInk(x: Float, y: Float): Boolean {
            if (x < 0f || y < 0f || x >= screenW || y >= screenH) return false
            return inkGrid[(y / cell).toInt() * cols + (x / cell).toInt()]
        }
        val world = MapCreatures(dp, Random(7)).apply {
            travelerCount = 5
            dementorCount = 2
            resize(screenW, screenH)
            warmUp()
            walkArea = WalkArea.fromInk(screenW, screenH, cell, dp, inkGrid)
        }
        val seen = HashSet<Traveler>()
        repeat(30 * 300) {
            world.update(1f / 30f)
            assertEquals(5, world.travelers.size)
            seen += world.travelers
            for (t in world.travelers) assertFalse("путник на рисунке: ${t.x}, ${t.y}", onInk(t.x, t.y))
            val foot = world.footprints.lastOrNull()
            if (foot != null) assertFalse("след на рисунке: ${foot.x}, ${foot.y}", onInk(foot.x, foot.y))
        }
        // Уходят за край и приходят новые, как и без карты.
        assertTrue(seen.size > 5)
    }

    @Test
    fun `по полосам у краёв путники не заходят в середину экрана`() {
        val world = MapCreatures(dp, Random(3)).apply {
            travelerCount = 3
            resize(screenW, screenH)
            warmUp()
            walkArea = WalkArea.fromInk(screenW, screenH, cell, dp, ink { _, _ -> true })
        }
        val margin = 0.1f
        repeat(30 * 200) {
            world.update(1f / 30f)
            for (t in world.travelers) {
                val inMiddle = t.x in screenW * (margin + 0.03f)..screenW * (1 - margin - 0.03f) &&
                    t.y in screenH * (margin + 0.03f)..screenH * (1 - margin - 0.03f)
                assertFalse("путник в середине: ${t.x}, ${t.y}", inMiddle)
            }
        }
    }

    @Test
    fun `путник на рисунке после появления карты переходит на чистую бумагу`() {
        val world = MapCreatures(dp, Random(5)).apply {
            travelerCount = 5
            resize(screenW, screenH)
            warmUp()
        }
        world.walkArea = area(::castle)
        for (t in world.travelers) {
            if (t.x in 0f..screenW && t.y in 0f..screenH) assertTrue(world.walkArea!!.isFree(t.x, t.y))
        }
        val nearest = world.travelers.minOf { hypot(it.x - 540f, it.y - 1200f) }
        assertTrue(nearest > 500f)
    }
}
