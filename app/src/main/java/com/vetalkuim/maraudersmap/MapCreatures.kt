package com.vetalkuim.maraudersmap

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** След ступни. [angle] — направление движения в радианах, носок смотрит туда же. */
class Footprint(
    var x: Float,
    var y: Float,
    val angle: Float,
    val left: Boolean,
    val born: Float,
)

/** Путник: идёт по плавной кривой от точки к точке и оставляет следы. */
class Traveler(val name: String?, var x: Float, var y: Float, var heading: Float) {
    var targetX = x
    var targetY = y

    /** Сколько ещё точек пройти, прежде чем уйти за край. */
    var targetsLeft = 0

    /** Уходит за край: после этого его заменит новый. */
    var leaving = false

    var nextLeft = false
    /** Пройдено всего и с последнего шага. */
    var walked = 0f
    var stride = 0f

    /** Сдвиг фазы виляния, чтобы путники не петляли в такт. */
    var meanderPhase = 0f

    /** Подпись плавно догоняет последний след. */
    var labelX = Float.NaN
    var labelY = Float.NaN
    var lastFoot: Footprint? = null
}

/**
 * Дементор: плывёт над картой по ветру, всегда лицом вперёд — в сторону [DementorVariant.heading].
 * Не разворачивается: пересекает экран и улетает за край, а вместо него прилетает другой.
 * Координаты — центр картинки.
 */
class Dementor(val variant: DementorVariant, var x: Float, var y: Float, val seed: Float) {
    var vx = 0f
    var vy = 0f

    /** Масштаб картинки: меньше 1, когда дементор опускается к путнику. */
    var scale = 1f

    /** Лишний после уменьшения числа дементоров: улетит, и его не заменят. */
    var retiring = false
}

/**
 * Слой 2 без Android: путники с их следами и дементоры. Все размеры в пикселях, [dp] — пикселей в одном dp,
 * время — в секундах. Отрисовкой занимается [CreatureRenderer].
 */
class MapCreatures(
    val dp: Float,
    private val random: Random = Random.Default,
) {
    var width = 0f
        private set
    var height = 0f
        private set

    /** Время мира; стоит, пока обои не видны. */
    var time = 0f
        private set

    var travelerCount = DEFAULT_TRAVELERS
        set(value) {
            field = value.coerceIn(0, MAX_COUNT)
        }

    var dementorCount = DEFAULT_DEMENTORS
        set(value) {
            field = value.coerceIn(0, MAX_COUNT)
        }

    val travelers = mutableListOf<Traveler>()
    val dementors = mutableListOf<Dementor>()
    val footprints = ArrayDeque<Footprint>()

    /** Есть что рисовать и двигать; без этого кадры не нужны. */
    val isIdle: Boolean
        get() = travelers.isEmpty() && footprints.isEmpty() && dementors.isEmpty() &&
            travelerCount == 0 && dementorCount == 0

    /**
     * Меняет размер мира. После поворота экрана все координаты пересчитываются пропорционально,
     * поэтому путники и следы остаются на своих местах относительно карты.
     */
    fun resize(newWidth: Float, newHeight: Float) {
        if (newWidth <= 0f || newHeight <= 0f) return
        if (width > 0f && height > 0f && (newWidth != width || newHeight != height)) {
            val sx = newWidth / width
            val sy = newHeight / height
            for (t in travelers) {
                t.x *= sx; t.y *= sy
                t.targetX *= sx; t.targetY *= sy
                t.labelX *= sx; t.labelY *= sy
            }
            for (f in footprints) {
                f.x *= sx; f.y *= sy
            }
            for (d in dementors) {
                d.x *= sx; d.y *= sy
            }
        }
        width = newWidth
        height = newHeight
    }

    /**
     * Прогоняет несколько секунд жизни сразу, чтобы в предпросмотре и при первом показе
     * путники уже шли и за ними тянулась цепочка следов.
     */
    fun warmUp(seconds: Float = WARM_UP_S) {
        populate()
        var left = seconds
        while (left > 0f) {
            update(min(left, WARM_UP_STEP_S))
            left -= WARM_UP_STEP_S
        }
    }

    /** Расставляет недостающих путников прямо на экране, без входа с края. */
    private fun populate() {
        while (travelers.size < travelerCount) {
            val x = random.range(EDGE_MARGIN_DP * dp, width - EDGE_MARGIN_DP * dp)
            val y = random.range(EDGE_MARGIN_DP * dp, height - EDGE_MARGIN_DP * dp)
            travelers += newTraveler(x, y, random.nextFloat() * 2f * PI.toFloat())
        }
        while (dementors.size < dementorCount) {
            val variant = DementorVariant.pick(dementors.map { it.variant }, random)
            dementors += Dementor(
                variant,
                random.range(width * 0.15f, width * 0.85f),
                random.range(height * 0.15f, height * 0.85f),
                random.nextFloat() * 100f,
            )
        }
    }

    fun update(dtSeconds: Float) {
        if (width <= 0f || height <= 0f) return
        val dt = dtSeconds.coerceIn(0f, MAX_STEP_S)
        time += dt
        travelers.removeAll { it.leaving && isOutside(it.x, it.y, LEAVE_MARGIN_DP * dp) }
        balanceTravelers()
        for (t in travelers) moveTraveler(t, dt)
        dementors.removeAll { isGone(it) }
        balanceDementors()
        for (d in dementors) moveDementor(d, dt)
        while (footprints.isNotEmpty() && time - footprints.first().born > FOOT_LIFE_S) {
            footprints.removeFirst()
        }
    }

    /** На экране столько путников, сколько задано: лишние уходят, ушедших заменяют новые. */
    private fun balanceTravelers() {
        var staying = travelers.count { !it.leaving }
        for (t in travelers) {
            if (staying <= travelerCount) break
            if (!t.leaving) {
                startLeaving(t)
                staying--
            }
        }
        while (travelers.size < travelerCount) travelers += enteringTraveler()
    }

    private fun newTraveler(x: Float, y: Float, heading: Float): Traveler {
        val used = travelers.mapNotNullTo(HashSet()) { it.name }
        val name = NAMES.firstOrNull { it !in used }
        return Traveler(name, x, y, heading).apply {
            nextLeft = random.nextBoolean()
            meanderPhase = random.nextFloat() * 2f * PI.toFloat()
            targetsLeft = random.nextInt(MIN_TARGETS, MAX_TARGETS + 1)
            pickTarget(this)
        }
    }

    /** Новый путник входит с любого края экрана. */
    private fun enteringTraveler(): Traveler {
        val outside = LEAVE_MARGIN_DP * dp * 0.5f
        val (x, y) = when (random.nextInt(4)) {
            0 -> -outside to random.range(0f, height)
            1 -> width + outside to random.range(0f, height)
            2 -> random.range(0f, width) to -outside
            else -> random.range(0f, width) to height + outside
        }
        val t = newTraveler(x, y, 0f)
        t.heading = atan2(t.targetY - y, t.targetX - x)
        return t
    }

    /** Случайная точка не ближе [EDGE_MARGIN_DP] к краю и не ближе [MIN_HOP_DP] к предыдущей. */
    private fun pickTarget(t: Traveler) {
        val margin = EDGE_MARGIN_DP * dp
        var bestX = width / 2
        var bestY = height / 2
        var bestDistance = -1f
        repeat(TARGET_TRIES) {
            val x = random.range(margin, width - margin)
            val y = random.range(margin, height - margin)
            val d = hypot(x - t.targetX, y - t.targetY)
            if (d >= MIN_HOP_DP * dp) {
                t.targetX = x
                t.targetY = y
                return
            }
            if (d > bestDistance) {
                bestDistance = d
                bestX = x
                bestY = y
            }
        }
        t.targetX = bestX
        t.targetY = bestY
    }

    /** Цель — точка за ближайшим краем. */
    private fun startLeaving(t: Traveler) {
        t.leaving = true
        val out = LEAVE_MARGIN_DP * dp * 2f
        val toLeft = t.x
        val toRight = width - t.x
        val toTop = t.y
        val toBottom = height - t.y
        when (minOf(toLeft, toRight, toTop, toBottom)) {
            toLeft -> { t.targetX = -out; t.targetY = t.y }
            toRight -> { t.targetX = width + out; t.targetY = t.y }
            toTop -> { t.targetX = t.x; t.targetY = -out }
            else -> { t.targetX = t.x; t.targetY = height + out }
        }
    }

    private fun moveTraveler(t: Traveler, dt: Float) {
        val speed = SPEED_DP * dp
        val dx = t.targetX - t.x
        val dy = t.targetY - t.y
        val distance = hypot(dx, dy)
        // Следующая точка выбирается заранее, чтобы путник срезал поворот дугой.
        if (!t.leaving && distance < ARRIVE_DP * dp) {
            t.targetsLeft--
            if (t.targetsLeft <= 0) startLeaving(t) else pickTarget(t)
        }

        // Поворот ограничен по скорости — путь получается плавной кривой без углов.
        // Вблизи цели поворот резче, иначе путник кружил бы вокруг точки.
        // Лёгкое виляние, чтобы длинный переход не выглядел прочерченным по линейке.
        val desired = atan2(t.targetY - t.y, t.targetX - t.x) +
            MEANDER * sin(t.walked / (MEANDER_WAVE_DP * dp) + t.meanderPhase)
        val maxTurn = max(TURN_RATE, 2f * speed / max(distance, 1f)) * dt
        t.heading += angleDiff(desired, t.heading).coerceIn(-maxTurn, maxTurn)

        val step = speed * dt
        t.x += cos(t.heading) * step
        t.y += sin(t.heading) * step
        t.walked += step
        t.stride += step
        while (t.stride >= STEP_DP * dp) {
            t.stride -= STEP_DP * dp
            leaveFootprint(t)
        }
        updateLabel(t, dt)
    }

    private fun leaveFootprint(t: Traveler) {
        // Левая нога — слева от линии движения: при оси y вниз это (sin, −cos).
        val side = if (t.nextLeft) 1f else -1f
        val offset = FOOT_OFFSET_DP * dp * side
        val foot = Footprint(
            x = t.x + sin(t.heading) * offset,
            y = t.y - cos(t.heading) * offset,
            angle = t.heading,
            left = t.nextLeft,
            born = time,
        )
        footprints.addLast(foot)
        t.lastFoot = foot
        t.nextLeft = !t.nextLeft
    }

    private fun updateLabel(t: Traveler, dt: Float) {
        val foot = t.lastFoot ?: return
        val goalX = foot.x
        val goalY = foot.y - LABEL_RISE_DP * dp
        if (t.labelX.isNaN()) {
            t.labelX = goalX
            t.labelY = goalY
            return
        }
        val k = 1f - exp(-dt / LABEL_LAG_S)
        t.labelX += (goalX - t.labelX) * k
        t.labelY += (goalY - t.labelY) * k
    }

    /** Лишние дементоры просто долетают до края; улетевших заменяют новые. */
    private fun balanceDementors() {
        var staying = dementors.count { !it.retiring }
        for (d in dementors) {
            if (staying <= dementorCount) break
            if (!d.retiring) {
                d.retiring = true
                staying--
            }
        }
        while (dementors.size < dementorCount) dementors += enteringDementor()
    }

    /** Новый дементор появляется за тем краем, откуда он полетит лицом вперёд. */
    private fun enteringDementor(): Dementor {
        val variant = DementorVariant.pick(dementors.map { it.variant }, random)
        val halfW = dementorHeight(1f) * variant.aspect / 2
        val halfH = dementorHeight(1f) / 2
        val gap = 2f * dp
        val (x, y) = when (variant.heading) {
            Heading.LEFT -> width + halfW + gap to random.range(height * 0.15f, height * 0.85f)
            Heading.RIGHT -> -halfW - gap to random.range(height * 0.15f, height * 0.85f)
            Heading.UP -> random.range(width * 0.15f, width * 0.85f) to height + halfH + gap
        }
        return Dementor(variant, x, y, random.nextFloat() * 100f)
    }

    /** Высота картинки дементора на экране. */
    fun dementorHeight(scale: Float): Float = DEMENTOR_HEIGHT_DP * dp * scale

    /** Дементор целиком улетел за край, к которому летел, — пора заменить его новым. */
    fun isGone(d: Dementor): Boolean {
        val h = dementorHeight(d.scale)
        return isPastExit(d.variant.heading, d.x, d.y, h * d.variant.aspect / 2, h / 2, width, height)
    }

    private fun moveDementor(d: Dementor, dt: Float) {
        val heading = d.variant.heading
        // Поперёк направления полёта: для левых и правых — по вертикали, для летящего вверх — по горизонтали.
        val px = -heading.dy
        val py = heading.dx
        val across = d.x * px + d.y * py
        val extent = if (heading == Heading.UP) width else height

        val forward = CRUISE_DP * dp
        var lateral = WIND_DP * dp *
            (0.6f * sin(time * 0.35f + d.seed) + 0.4f * sin(time * 0.83f + d.seed * 2.3f))
        // Ветер не уносит за боковые края: у границы полосы дементора мягко возвращает.
        val low = extent * BAND
        val high = extent * (1f - BAND)
        if (across < low) lateral += (low - across) * RETURN_RATE
        if (across > high) lateral -= (across - high) * RETURN_RATE

        val k = 1f - exp(-dt / DEMENTOR_INERTIA_S)
        d.vx += (heading.dx * forward + px * lateral - d.vx) * k
        d.vy += (heading.dy * forward + py * lateral - d.vy) * k

        // Только вперёд: задом наперёд дементор не летает.
        val along = d.vx * heading.dx + d.vy * heading.dy
        val minForward = MIN_FORWARD_DP * dp
        if (along < minForward) {
            d.vx += heading.dx * (minForward - along)
            d.vy += heading.dy * (minForward - along)
        }
        d.x += d.vx * dt
        d.y += d.vy * dt
        d.scale += (1f - d.scale) * (1f - exp(-dt / SCALE_LAG_S))
    }

    private fun isOutside(x: Float, y: Float, margin: Float) =
        x < -margin || x > width + margin || y < -margin || y > height + margin

    companion object {
        const val MAX_COUNT = 5
        const val DEFAULT_TRAVELERS = 3
        const val DEFAULT_DEMENTORS = 2

        /** Имена по порядку; новый путник берёт первое свободное. */
        val NAMES = listOf("Путник", "Странница", "Бродяга", "Скиталец", "Пилигрим")

        const val STEP_DP = 30f
        const val SPEED_DP = 48f
        const val FOOT_OFFSET_DP = 7f
        const val EDGE_MARGIN_DP = 32f
        const val MIN_HOP_DP = 120f
        const val LABEL_RISE_DP = 26f

        const val FOOT_LIFE_S = 3f
        const val FOOT_FADE_IN_S = 0.35f

        /** Наибольшая непрозрачность следа: 200 из 255. */
        const val FOOT_MAX_ALPHA = 200

        private const val ARRIVE_DP = 70f
        private const val LEAVE_MARGIN_DP = 40f
        private const val TURN_RATE = 1.1f
        private const val MEANDER = 0.3f
        private const val MEANDER_WAVE_DP = 70f
        private const val LABEL_LAG_S = 0.3f
        private const val MIN_TARGETS = 3
        private const val MAX_TARGETS = 6
        private const val TARGET_TRIES = 20
        private const val MAX_STEP_S = 0.1f
        private const val WARM_UP_S = 4.5f
        private const val WARM_UP_STEP_S = 1f / 30f

        /** Высота дементора на экране при обычном масштабе. */
        const val DEMENTOR_HEIGHT_DP = 130f
        private const val CRUISE_DP = 20f
        private const val MIN_FORWARD_DP = 6f
        private const val WIND_DP = 9f
        private const val BAND = 0.12f
        private const val RETURN_RATE = 0.4f
        private const val DEMENTOR_INERTIA_S = 0.8f
        private const val SCALE_LAG_S = 0.6f

        /** Картинка с полуразмерами [halfW]×[halfH] целиком за краем, к которому летит. */
        fun isPastExit(heading: Heading, x: Float, y: Float, halfW: Float, halfH: Float, width: Float, height: Float) =
            when (heading) {
                Heading.LEFT -> x + halfW < 0f
                Heading.RIGHT -> x - halfW > width
                Heading.UP -> y + halfH < 0f
            }

        /** Непрозрачность следа 0..1: проявляется за [FOOT_FADE_IN_S], затем равномерно гаснет. */
        fun footprintOpacity(age: Float, life: Float = FOOT_LIFE_S): Float = when {
            age < 0f || age >= life -> 0f
            age < FOOT_FADE_IN_S -> age / FOOT_FADE_IN_S
            else -> 1f - (age - FOOT_FADE_IN_S) / (life - FOOT_FADE_IN_S)
        }

        /** Разница углов, приведённая к (−π, π]. */
        fun angleDiff(a: Float, b: Float): Float {
            var d = (a - b) % (2f * PI.toFloat())
            if (d > PI) d -= 2f * PI.toFloat()
            if (d <= -PI) d += 2f * PI.toFloat()
            return d
        }

        private fun Random.range(from: Float, to: Float): Float =
            if (to <= from) (from + to) / 2 else from + nextFloat() * (to - from)
    }
}
