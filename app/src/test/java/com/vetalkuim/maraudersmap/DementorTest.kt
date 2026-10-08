package com.vetalkuim.maraudersmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DementorTest {

    private val dp = 2f
    private val screenW = 1080f
    private val screenH = 2400f

    private fun world(dementors: Int, travelers: Int = 3, seed: Int = 1) = MapCreatures(dp, Random(seed)).apply {
        dementorCount = dementors
        travelerCount = travelers
        resize(screenW, screenH)
        warmUp()
    }

    private fun MapCreatures.run(seconds: Float, check: MapCreatures.() -> Unit = {}) {
        var t = 0f
        while (t < seconds) {
            update(FRAME)
            check()
            t += FRAME
        }
    }

    @Test
    fun `на экране столько дементоров, сколько задано, и улетевших заменяют`() {
        for (count in 0..MapCreatures.MAX_COUNT) {
            val world = world(count)
            val seen = HashSet<Dementor>()
            world.run(120f) {
                assertEquals(count, dementors.size)
                seen += dementors
            }
            // За две минуты дементоры успевают улететь, и вместо них прилетают новые.
            if (count > 0) assertTrue(seen.size > count)
        }
    }

    @Test
    fun `лишние дементоры улетают, а новые прилетают с края`() {
        val world = world(5)
        world.dementorCount = 2
        world.update(FRAME)
        assertEquals(2, world.dementors.count { !it.retiring })
        // Лишние не исчезают на месте, а долетают до края.
        assertEquals(5, world.dementors.size)
        world.run(120f)
        assertEquals(2, world.dementors.size)

        world.dementorCount = 4
        world.update(FRAME)
        assertEquals(4, world.dementors.size)
    }

    @Test
    fun `пока вариантов хватает, дементоры не повторяются`() {
        for (count in 1..MapCreatures.MAX_COUNT) {
            world(count, seed = count).run(300f) {
                val variants = dementors.map { it.variant }
                assertEquals(variants.size, variants.toSet().size)
            }
        }
    }

    @Test
    fun `повтор варианта — только когда свободных не осталось`() {
        val all = DementorVariant.ALL
        val random = Random(3)
        repeat(50) {
            val inUse = all.shuffled(random).take(all.size - 1)
            assertFalse(DementorVariant.pick(inUse, random) in inUse)
        }
        assertTrue(DementorVariant.pick(all, random) in all)
    }

    @Test
    fun `направления на картинках`() {
        val headings = DementorVariant.ALL.associate { it.number to it.heading }
        assertEquals(Heading.LEFT, headings[1])
        assertEquals(Heading.RIGHT, headings[2])
        assertEquals(Heading.UP, headings[3])
        assertEquals(Heading.LEFT, headings[4])
        assertEquals(Heading.RIGHT, headings[5])
        assertEquals(Heading.RIGHT, headings[6])
        assertEquals(Heading.RIGHT, headings[7])
    }

    @Test
    fun `дементор уходит со сцены только за тем краем, к которому летит`() {
        val half = 50f
        // Летит влево: пора заменить, когда целиком ушёл за левый край.
        assertFalse(MapCreatures.isPastExit(Heading.LEFT, 10f, 500f, half, half, screenW, screenH))
        assertFalse(MapCreatures.isPastExit(Heading.LEFT, -49f, 500f, half, half, screenW, screenH))
        assertTrue(MapCreatures.isPastExit(Heading.LEFT, -51f, 500f, half, half, screenW, screenH))
        // Только что появившийся справа ещё не «улетел», хотя он тоже за экраном.
        assertFalse(MapCreatures.isPastExit(Heading.LEFT, screenW + 51f, 500f, half, half, screenW, screenH))

        assertTrue(MapCreatures.isPastExit(Heading.RIGHT, screenW + 51f, 500f, half, half, screenW, screenH))
        assertFalse(MapCreatures.isPastExit(Heading.RIGHT, -51f, 500f, half, half, screenW, screenH))

        assertTrue(MapCreatures.isPastExit(Heading.UP, 500f, -51f, half, half, screenW, screenH))
        assertFalse(MapCreatures.isPastExit(Heading.UP, 500f, screenH + 51f, half, half, screenW, screenH))
    }

    @Test
    fun `дементоры не летают задом наперёд и не разворачиваются`() {
        val world = world(MapCreatures.MAX_COUNT)
        val headings = HashMap<Dementor, Heading>()
        world.run(300f) {
            for (d in dementors) {
                val along = d.vx * d.variant.heading.dx + d.vy * d.variant.heading.dy
                assertTrue("летит назад: $along", along > 0f)
                assertEquals(headings.getOrPut(d) { d.variant.heading }, d.variant.heading)
            }
        }
    }

    @Test
    fun `возле путника дементор опускается ниже — становится меньше`() {
        val world = world(MapCreatures.MAX_COUNT, travelers = MapCreatures.MAX_COUNT)
        var minScale = 1f
        var near = 0
        world.run(300f) {
            for (d in dementors) {
                minScale = minOf(minScale, d.scale)
                if (d.mode == Dementor.Mode.NEAR) near++
            }
        }
        assertTrue(near > 0)
        assertTrue(minScale < 0.9f)
    }

    @Test
    fun `после поворота экрана дементоры остаются на своих местах`() {
        val world = world(3)
        val before = world.dementors.map { Triple(it, it.x / screenW, it.y / screenH) }
        world.resize(screenH, screenW)
        assertEquals(before.map { it.first }, world.dementors)
        for ((d, fx, fy) in before) {
            assertEquals(fx, d.x / screenH, 1e-4f)
            assertEquals(fy, d.y / screenW, 1e-4f)
        }
    }

    @Test
    fun `следы рядом с дементором бледнее`() {
        assertEquals(1f, MapCreatures.coldChill(MapCreatures.COLD_DP), 0f)
        assertEquals(MapCreatures.COLD_MIN, MapCreatures.coldChill(0f), 1e-6f)
        assertTrue(MapCreatures.coldChill(40f) < MapCreatures.coldChill(120f))

        val world = world(1, travelers = 0)
        val d = world.dementors.single()
        assertTrue(world.chillAt(d.x, d.y) < 0.5f)
        assertEquals(1f, world.chillAt(d.x + MapCreatures.COLD_DP * dp * 2, d.y), 0f)
    }

    @Test
    fun `без дементоров и путников кадры не нужны`() {
        val world = world(0, travelers = 0)
        assertTrue(world.isIdle)
        world.dementorCount = 1
        assertNotEquals(true, world.isIdle)
    }

    private companion object {
        const val FRAME = 1f / 30f
    }
}
