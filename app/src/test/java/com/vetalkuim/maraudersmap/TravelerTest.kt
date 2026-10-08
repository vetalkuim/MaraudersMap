package com.vetalkuim.maraudersmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class TravelerTest {

    private val dp = 2f
    private val screenW = 1080f
    private val screenH = 2400f

    private fun world(travelers: Int, dementors: Int = 0, seed: Int = 1) = MapCreatures(dp, Random(seed)).apply {
        travelerCount = travelers
        dementorCount = dementors
        resize(screenW, screenH)
        warmUp()
    }

    @Test
    fun `на экране столько путников, сколько задано, ушедших заменяют`() {
        for (count in 0..MapCreatures.MAX_COUNT) {
            val world = world(count)
            val seen = HashSet<Traveler>()
            repeat(30 * 120) {
                world.update(FRAME)
                assertEquals(count, world.travelers.size)
                seen += world.travelers
            }
            if (count > 0) assertTrue(seen.size > count)
        }
    }

    @Test
    fun `имена по умолчанию и без повторов`() {
        val world = world(3)
        assertEquals(listOf("Путник", "Странница", "Бродяга"), world.travelers.map { it.name })
        repeat(30 * 300) {
            world.update(FRAME)
            val names = world.travelers.map { it.name }
            assertEquals(names.size, names.toSet().size)
        }
    }

    @Test
    fun `ноги чередуются, шаг — 30 dp`() {
        val world = world(1, seed = 5)
        val traveler = world.travelers.single()
        val feet = mutableListOf<Footprint>()
        repeat(30 * 20) {
            world.update(FRAME)
            val foot = traveler.lastFoot
            if (foot != null && feet.lastOrNull() !== foot && traveler in world.travelers) feet += foot
        }
        assertTrue(feet.size > 10)
        for ((a, b) in feet.zipWithNext()) {
            assertTrue(a.left != b.left)
            // Между соседними следами — шаг вдоль пути плюс смещение ног в стороны.
            val distance = hypot(b.x - a.x, b.y - a.y) / dp
            assertTrue("шаг $distance dp", distance in 25f..36f)
        }
    }

    @Test
    fun `след проявляется за 0,35 с и гаснет к 3 с`() {
        assertEquals(0f, MapCreatures.footprintOpacity(0f), 0f)
        assertEquals(1f, MapCreatures.footprintOpacity(MapCreatures.FOOT_FADE_IN_S), 1e-6f)
        assertTrue(MapCreatures.footprintOpacity(2f) < MapCreatures.footprintOpacity(1f))
        assertEquals(0f, MapCreatures.footprintOpacity(MapCreatures.FOOT_LIFE_S), 0f)
        assertEquals(200, MapCreatures.FOOT_MAX_ALPHA)
    }

    @Test
    fun `путник боится дементора и ускоряет шаг`() {
        val world = world(MapCreatures.MAX_COUNT, dementors = MapCreatures.MAX_COUNT, seed = 9)
        var scared = 0
        var hurried = 0
        repeat(30 * 300) {
            val before = world.travelers.associateWith { it.x to it.y }
            val present = world.dementors.toSet()
            world.update(FRAME)
            for (t in world.travelers) {
                val d = world.dementors.minByOrNull { hypot(it.x - t.x, it.y - t.y) } ?: continue
                // Дементор сдвигается после путника, поэтому радиус берётся с небольшим запасом;
                // на только что влетевшего с края путник отреагирует в следующем кадре.
                if (d in present && hypot(d.x - t.x, d.y - t.y) < MapCreatures.FEAR_DP * dp * 0.9f) {
                    assertTrue("путник не испугался дементора рядом", world.time < t.calmUntil)
                    scared++
                }
                val (x0, y0) = before[t] ?: continue
                if (world.time < t.hurryUntil) {
                    val speed = hypot(t.x - x0, t.y - y0) / FRAME / dp
                    assertEquals(MapCreatures.SPEED_DP * MapCreatures.HURRY_FACTOR, speed, 0.5f)
                    hurried++
                }
            }
        }
        assertTrue(scared > 0)
        assertTrue(hurried > 0)
    }

    @Test
    fun `после поворота экрана путники и следы пересчитываются пропорционально`() {
        val world = world(3)
        val travelers = world.travelers.map { Triple(it, it.x / screenW, it.y / screenH) }
        val feet = world.footprints.map { Triple(it, it.x / screenW, it.y / screenH) }
        assertTrue(feet.isNotEmpty())
        world.resize(screenH, screenW)
        assertEquals(travelers.map { it.first }, world.travelers)
        for ((t, fx, fy) in travelers) {
            assertEquals(fx, t.x / screenH, 1e-4f)
            assertEquals(fy, t.y / screenW, 1e-4f)
        }
        for ((f, fx, fy) in feet) {
            assertEquals(fx, f.x / screenH, 1e-4f)
            assertEquals(fy, f.y / screenW, 1e-4f)
        }
    }

    private companion object {
        const val FRAME = 1f / 30f
    }
}
