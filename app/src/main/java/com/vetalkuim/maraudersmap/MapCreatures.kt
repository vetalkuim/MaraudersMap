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

/**
 * След ступни. [angle] — направление движения в радианах, носок смотрит туда же.
 * [chill] — множитель непрозрачности: след рядом с дементором бледнее, будто его выстудило.
 */
class Footprint(
    var x: Float,
    var y: Float,
    val angle: Float,
    val left: Boolean,
    val born: Float,
    val chill: Float = 1f,
)

/** Путник: идёт по плавной кривой от точки к точке и оставляет следы. */
class Traveler(var name: String?, var x: Float, var y: Float, var heading: Float) {
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

    /** До этого времени путник идёт быстрее — убегает от дементора. */
    var hurryUntil = 0f

    /** Раньше этого времени новый испуг не сбивает с пути. */
    var calmUntil = 0f
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

    var mode = Mode.CRUISE
    var modeUntil = 0f
    var nextApproachAt = 0f

    /** Путник, к которому дементор подлетает или возле которого держится. */
    var prey: Traveler? = null

    /** Поворот головы к путнику на экране: −1 — влево, 1 — вправо. */
    var gaze = 0f

    /** 0 — голова поворачивается сама по себе, 1 — смотрит на путника. */
    var gazeWeight = 0f

    enum class Mode {
        /** Плывёт по ветру. */
        CRUISE,

        /** Подлетает к путнику впереди. */
        APPROACH,

        /** Опустился к путнику: меньше и быстрее. */
        NEAR,

        /** Взлетает и уплывает подальше. */
        LEAVE,
    }
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

    /**
     * Имена путников из настроек. Сколько строк — столько путников на экране;
     * путник с пустым именем ходит без подписи.
     * Путник, чьё имя исправили, переименовывается на месте; тот, чьё имя удалили, уходит за край.
     */
    var travelerNames: List<String> = NAMES.take(DEFAULT_TRAVELERS)
        set(value) {
            field = value.map(String::trim).take(MAX_TRAVELERS)
            renameTravelers()
        }

    /** Число путников; запись задаёт им имена по умолчанию из [NAMES]. */
    var travelerCount: Int
        get() = travelerNames.size
        set(value) {
            travelerNames = NAMES.take(value.coerceIn(0, NAMES.size))
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
            ).apply { nextApproachAt = time + random.range(FIRST_APPROACH_MIN_S, FIRST_APPROACH_MAX_S) }
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

    /** Имена из [travelerNames], которые не носит ни один остающийся путник (с учётом повторов). */
    private fun freeNames(): MutableList<String> {
        val free = travelerNames.toMutableList()
        for (t in travelers) {
            if (!t.leaving) t.name?.let { free.remove(it) }
        }
        return free
    }

    /** Путники с исчезнувшими именами берут новые имена из списка, а если их не хватает — уходят. */
    private fun renameTravelers() {
        val free = travelerNames.toMutableList()
        val unnamed = travelers.filter { t -> !t.leaving && t.name?.let { free.remove(it) } != true }
        for (t in unnamed) {
            if (free.isNotEmpty()) t.name = free.removeAt(0) else startLeaving(t)
        }
    }

    private fun newTraveler(x: Float, y: Float, heading: Float): Traveler {
        // Имя того, кто ещё уходит за край, — только если других свободных нет.
        val free = freeNames()
        val leaving = travelers.filter { it.leaving }.mapNotNullTo(HashSet()) { it.name }
        val name = free.firstOrNull { it !in leaving } ?: free.firstOrNull()
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
        avoidDementors(t)
        val hurry = time < t.hurryUntil
        val speed = SPEED_DP * dp * (if (hurry) HURRY_FACTOR else 1f)
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
        val turnRate = if (hurry) TURN_RATE * 2f else TURN_RATE
        val maxTurn = max(turnRate, 2f * speed / max(distance, 1f)) * dt
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

    /** Путник боится дементоров: если дементор близко, сворачивает от него и ненадолго ускоряет шаг. */
    private fun avoidDementors(t: Traveler) {
        if (time < t.calmUntil) return
        val d = nearestDementor(t.x, t.y) ?: return
        if (hypot(d.x - t.x, d.y - t.y) > FEAR_DP * dp) return
        t.hurryUntil = time + HURRY_S
        t.calmUntil = time + CALM_S
        // Уходящий за край путник просто прибавляет шаг: его цель и так подальше отсюда.
        if (t.leaving) return
        val away = atan2(t.y - d.y, t.x - d.x) + random.range(-FLEE_SPREAD, FLEE_SPREAD)
        val margin = EDGE_MARGIN_DP * dp
        t.targetX = (t.x + cos(away) * FLEE_DP * dp).coerceIn(margin, max(margin, width - margin))
        t.targetY = (t.y + sin(away) * FLEE_DP * dp).coerceIn(margin, max(margin, height - margin))
    }

    /** Множитель непрозрачности следа в точке: чем ближе дементор, тем бледнее. */
    fun chillAt(x: Float, y: Float): Float {
        val d = nearestDementor(x, y) ?: return 1f
        return coldChill(hypot(d.x - x, d.y - y) / dp)
    }

    private fun nearestDementor(x: Float, y: Float): Dementor? =
        dementors.minByOrNull { hypot(it.x - x, it.y - y) }

    private fun leaveFootprint(t: Traveler) {
        // Левая нога — слева от линии движения: при оси y вниз это (sin, −cos).
        val side = if (t.nextLeft) 1f else -1f
        val offset = FOOT_OFFSET_DP * dp * side
        val x = t.x + sin(t.heading) * offset
        val y = t.y - cos(t.heading) * offset
        val foot = Footprint(
            x = x,
            y = y,
            angle = t.heading,
            left = t.nextLeft,
            born = time,
            chill = chillAt(x, y),
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
        return Dementor(variant, x, y, random.nextFloat() * 100f).apply {
            nextApproachAt = time + random.range(FIRST_APPROACH_MIN_S, FIRST_APPROACH_MAX_S)
        }
    }

    /** Время от времени дементор выбирает путника впереди, подлетает, держится рядом и улетает. */
    private fun updateMode(d: Dementor) {
        val prey = d.prey
        val preyLost = prey == null || prey !in travelers
        when (d.mode) {
            Dementor.Mode.CRUISE -> {
                if (time < d.nextApproachAt || d.retiring) return
                val target = pickPrey(d)
                if (target == null) {
                    d.nextApproachAt = time + RETRY_APPROACH_S
                } else {
                    d.prey = target
                    d.mode = Dementor.Mode.APPROACH
                    d.modeUntil = time + APPROACH_TIMEOUT_S
                }
            }
            Dementor.Mode.APPROACH -> when {
                preyLost || time >= d.modeUntil || !isAhead(d, prey!!, -NEAR_DP * dp) -> leave(d)
                hypot(prey.x - d.x, prey.y - d.y) < NEAR_DP * dp -> {
                    d.mode = Dementor.Mode.NEAR
                    d.modeUntil = time + random.range(NEAR_MIN_S, NEAR_MAX_S)
                }
            }
            Dementor.Mode.NEAR -> if (preyLost || time >= d.modeUntil) leave(d)
            Dementor.Mode.LEAVE -> if (time >= d.modeUntil) {
                d.mode = Dementor.Mode.CRUISE
                d.prey = null
                d.nextApproachAt = time + random.range(APPROACH_MIN_S, APPROACH_MAX_S)
            }
        }
    }

    private fun leave(d: Dementor) {
        d.mode = Dementor.Mode.LEAVE
        d.modeUntil = time + LEAVE_S
    }

    /** Ближайший путник впереди: назад дементор не летит. */
    private fun pickPrey(d: Dementor): Traveler? = travelers
        .filter { !it.leaving && isAhead(d, it, NEAR_DP * dp * 0.5f) && isAhead(d, it, -PREY_RANGE_DP * dp, far = true) }
        .minByOrNull { hypot(it.x - d.x, it.y - d.y) }

    /**
     * Путник впереди дементора не ближе [minAhead] по направлению полёта.
     * С [far] проверяется обратное: не дальше −[minAhead].
     */
    private fun isAhead(d: Dementor, t: Traveler, minAhead: Float, far: Boolean = false): Boolean {
        val h = d.variant.heading
        val ahead = (t.x - d.x) * h.dx + (t.y - d.y) * h.dy
        return if (far) ahead <= -minAhead else ahead >= minAhead
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
        // Поперёк направления полёта: для левых и правых — вниз по экрану, для летящего вверх — вправо.
        // Ось всегда смотрит в положительную сторону, чтобы полоса [BAND, 1 − BAND] считалась от края экрана.
        val px = if (heading == Heading.UP) 1f else 0f
        val py = if (heading == Heading.UP) 0f else 1f
        val across = d.x * px + d.y * py
        val extent = if (heading == Heading.UP) width else height

        updateMode(d)
        val prey = d.prey
        var forward = CRUISE_DP * dp
        var lateral = WIND_DP * dp *
            (0.6f * sin(time * 0.35f + d.seed) + 0.4f * sin(time * 0.83f + d.seed * 2.3f))
        var scale = 1f
        when (d.mode) {
            Dementor.Mode.CRUISE -> Unit
            Dementor.Mode.APPROACH -> if (prey != null) {
                forward = APPROACH_DP * dp
                val preyAcross = prey.x * px + prey.y * py
                lateral += ((preyAcross - across) * APPROACH_GAIN).coerceIn(-APPROACH_DP * dp, APPROACH_DP * dp)
            }
            Dementor.Mode.NEAR -> if (prey != null) {
                // Держится рядом с путником и двигается быстрее, чем на подлёте.
                scale = NEAR_SCALE
                val nearMax = APPROACH_DP * dp * NEAR_SPEED_FACTOR
                val preyAlong = prey.x * heading.dx + prey.y * heading.dy
                val along = d.x * heading.dx + d.y * heading.dy
                forward = ((preyAlong - along) * NEAR_GAIN).coerceIn(0f, nearMax)
                val preyAcross = prey.x * px + prey.y * py
                lateral = ((preyAcross - across) * NEAR_GAIN).coerceIn(-nearMax, nearMax)
            }
            Dementor.Mode.LEAVE -> {
                forward = LEAVE_DP * dp
                if (prey != null) {
                    val preyAcross = prey.x * px + prey.y * py
                    lateral += if (preyAcross > across) -LEAVE_DP * dp else LEAVE_DP * dp
                }
            }
        }
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
        d.scale += (scale - d.scale) * (1f - exp(-dt / SCALE_LAG_S))
        lookAtPrey(d, dt)
    }

    /**
     * Подлетая к путнику и держась рядом, дементор поворачивает к нему голову.
     * Сам он не разворачивается: всегда летит лицом вперёд и улетает за край.
     */
    private fun lookAtPrey(d: Dementor, dt: Float) {
        val prey = d.prey?.takeIf { d.mode == Dementor.Mode.APPROACH || d.mode == Dementor.Mode.NEAR }
        val dx = if (prey != null) prey.x - d.x else 0f
        val k = 1f - exp(-dt / GAZE_LAG_S)
        if (prey != null) d.gaze += ((dx / (NEAR_DP * dp)).coerceIn(-1f, 1f) - d.gaze) * k
        d.gazeWeight += ((if (prey != null) 1f else 0f) - d.gazeWeight) * k
    }

    private fun isOutside(x: Float, y: Float, margin: Float) =
        x < -margin || x > width + margin || y < -margin || y > height + margin

    companion object {
        const val MAX_COUNT = 5

        /** Сколько путников можно завести в настройках. */
        const val MAX_TRAVELERS = 10
        const val DEFAULT_TRAVELERS = 3
        const val DEFAULT_DEMENTORS = 1

        /** Имена по умолчанию: первые [DEFAULT_TRAVELERS] — для новых настроек. */
        val NAMES = listOf("Путник", "Странница", "Бродяга", "Скиталец", "Пилигрим")

        const val STEP_DP = 30f
        const val SPEED_DP = 24f
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
        private const val CRUISE_DP = 10f
        private const val MIN_FORWARD_DP = 3f
        private const val WIND_DP = 4.5f
        private const val BAND = 0.12f
        private const val RETURN_RATE = 0.4f
        private const val DEMENTOR_INERTIA_S = 0.8f
        private const val SCALE_LAG_S = 0.6f

        private const val APPROACH_DP = 13f
        private const val APPROACH_GAIN = 0.6f
        private const val NEAR_DP = 150f
        private const val NEAR_GAIN = 1.5f
        private const val NEAR_SPEED_FACTOR = 1.5f
        const val NEAR_SCALE = 0.85f
        private const val LEAVE_DP = 16f
        private const val PREY_RANGE_DP = 450f
        private const val FIRST_APPROACH_MIN_S = 3f
        private const val FIRST_APPROACH_MAX_S = 10f
        private const val APPROACH_MIN_S = 15f
        private const val APPROACH_MAX_S = 30f
        private const val RETRY_APPROACH_S = 3f
        private const val APPROACH_TIMEOUT_S = 15f
        private const val NEAR_MIN_S = 3f
        private const val NEAR_MAX_S = 5f
        private const val LEAVE_S = 5f

        private const val GAZE_LAG_S = 0.4f

        /** Путник пугается дементора ближе этого расстояния. */
        const val FEAR_DP = 140f
        const val HURRY_FACTOR = 1.6f
        private const val HURRY_S = 2f
        private const val CALM_S = 2.5f
        private const val FLEE_DP = 160f
        private const val FLEE_SPREAD = 0.5f

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

        /** Холод: ближе [COLD_DP] к дементору след бледнеет, вплотную — до [COLD_MIN]. */
        const val COLD_DP = 160f
        const val COLD_MIN = 0.35f

        /** Множитель непрозрачности следа на расстоянии [distanceDp] от дементора. */
        fun coldChill(distanceDp: Float): Float =
            if (distanceDp >= COLD_DP) 1f else COLD_MIN + (1f - COLD_MIN) * (distanceDp / COLD_DP).coerceAtLeast(0f)

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
