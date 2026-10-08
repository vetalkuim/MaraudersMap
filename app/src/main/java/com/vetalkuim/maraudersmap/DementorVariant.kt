package com.vetalkuim.maraudersmap

import kotlin.random.Random

/** Куда летит дементор на рисунке; задом наперёд он не летает. */
enum class Heading(val dx: Float, val dy: Float) {
    LEFT(-1f, 0f),
    RIGHT(1f, 0f),
    UP(0f, -1f),
}

/**
 * Вариант дементора: картинка `dementor_<number>.webp`, направление полёта и овал лица
 * в капюшоне. [aspect] — ширина к высоте; лицо — центр и полуоси в долях ширины и высоты картинки.
 */
class DementorVariant(
    val number: Int,
    val heading: Heading,
    val aspect: Float,
    val faceX: Float,
    val faceY: Float,
    val faceRx: Float,
    val faceRy: Float,
) {
    companion object {
        /** Сколько картинок — столько и вариантов. */
        val ALL = listOf(
            DementorVariant(1, Heading.LEFT, 329f / 457f, 0.380f, 0.116f, 0.033f, 0.044f),
            DementorVariant(2, Heading.RIGHT, 351f / 556f, 0.558f, 0.096f, 0.031f, 0.055f),
            DementorVariant(3, Heading.UP, 453f / 551f, 0.492f, 0.101f, 0.031f, 0.054f),
            DementorVariant(4, Heading.LEFT, 463f / 420f, 0.393f, 0.111f, 0.024f, 0.061f),
            DementorVariant(5, Heading.RIGHT, 318f / 520f, 0.692f, 0.087f, 0.035f, 0.043f),
            DementorVariant(6, Heading.RIGHT, 379f / 484f, 0.697f, 0.115f, 0.018f, 0.042f),
            DementorVariant(7, Heading.RIGHT, 422f / 547f, 0.595f, 0.099f, 0.031f, 0.051f),
        )

        /** Случайный вариант, которого нет на экране; повторы — только когда вариантов не хватает. */
        fun pick(inUse: Collection<DementorVariant>, random: Random, all: List<DementorVariant> = ALL): DementorVariant {
            val free = all.filter { it !in inUse }
            return if (free.isNotEmpty()) free.random(random) else all.random(random)
        }
    }
}
