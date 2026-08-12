package render

import dive.Tuning
import dive.Zone
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What is worth asserting about a field of drifting dots, and what is not.
 *
 * Nothing here checks that the motes look nice — a still frame cannot show drift, and
 * `CLAUDE.md` is explicit that `EPT_SCREENSHOT`'s output is not the frame. What IS checkable, and
 * what breaks silently, is the arithmetic underneath:
 *
 *  - the HASH, because a bad one produces visible rows and columns of identical motes and no test
 *    that merely calls it would notice;
 *  - the CULL MARGIN, because too small a margin pops motes at the frame edge only while the
 *    camera is moving, which no screenshot catches;
 *  - the COUNT, because the density has to hold at aspect ratios nobody has the panel for yet;
 *  - the COLOUR and the SIZE, because §1.1 of `docs/superpowers/specs/2026-08-11-outstanding-work
 *    .md` makes both of them a GAME MECHANIC — the anglerfish's lure is drawn identically to a
 *    pearl and the only tell is motion, so ambient glowing points must not be mistakable for one;
 *  - the CLOCK's exact wrap, because it only misbehaves on the second afternoon of an unattended
 *    two-day run;
 *  - the SURFACE's layering, because the engine assigns a zOrder by creation order when one is not
 *    given, so the whole effect can be moved behind the world by adding an unrelated surface.
 */
class MotesTest
{
    @BeforeTest
    fun clearClock() = Motes.unpin()

    @AfterTest
    fun restoreClock() = Motes.unpin()

    // ---- The hash --------------------------------------------------------------------------

    @Test
    fun `every hashed value is deterministic and inside the unit interval`()
    {
        for (cellY in -40..40)
        {
            for (cellX in -40..40)
            {
                for (stream in 0..7)
                {
                    val v = Motes.unitFloat(cellX, cellY, stream)
                    assertTrue(v >= 0f && v < 1f, "unitFloat($cellX, $cellY, $stream) = $v is outside [0, 1)")
                    assertEquals(v, Motes.unitFloat(cellX, cellY, stream), "the hash is not a function of its inputs")
                }
            }
        }
    }

    /**
     * A LATTICE ONLY HIDES ITS GRID IF THE HASH AVALANCHES, and a hash that does not still passes
     * every "is it in range" test.
     *
     * The classic spatial hash `x * 73856093 xor y * 19349663` with no finalizer leaves the low
     * bits strongly correlated with the input, and the low bits are most of what a `[0, 1)`
     * conversion keeps — so neighbouring cells come out with nearly the same value and the field
     * reads as rows and columns of identically bright motes.
     *
     * Two independent uniform draws differ by 1/3 on average. Adjacent cells are required to reach
     * most of that: a correlated hash collapses this figure toward zero.
     */
    @Test
    fun `neighbouring cells are decorrelated, not merely in range`()
    {
        for (stream in 0..7)
        {
            var totalX = 0.0
            var totalY = 0.0
            var samples = 0
            for (cellY in -60..60)
            {
                for (cellX in -60..60)
                {
                    totalX += abs(Motes.unitFloat(cellX, cellY, stream) - Motes.unitFloat(cellX + 1, cellY, stream))
                    totalY += abs(Motes.unitFloat(cellX, cellY, stream) - Motes.unitFloat(cellX, cellY + 1, stream))
                    samples++
                }
            }
            val meanX = totalX / samples
            val meanY = totalY / samples
            assertTrue(meanX > 0.28, "stream $stream: horizontally adjacent cells differ by only $meanX on average (two independent uniform draws differ by 0.333) — the hash is not avalanching and the field will show vertical banding")
            assertTrue(meanY > 0.28, "stream $stream: vertically adjacent cells differ by only $meanY on average — the hash is not avalanching and the field will show horizontal banding")
        }
    }

    @Test
    fun `the hashed values are spread evenly across the unit interval`()
    {
        val buckets = IntArray(16)
        for (cellY in -32..31)
        {
            for (cellX in -32..31)
            {
                val bucket = (Motes.unitFloat(cellX, cellY, Motes.STREAM_JITTER_X) * buckets.size).toInt()
                buckets[bucket]++
            }
        }
        val expected = 64.0 * 64.0 / buckets.size
        buckets.forEachIndexed { i, count ->
            assertTrue(
                count > expected * 0.7 && count < expected * 1.3,
                "bucket $i holds $count of an expected $expected — the jitter is not uniform, so the motes will clump"
            )
        }
    }

    @Test
    fun `the streams of one cell are independent of each other`()
    {
        // Same cell, different property: two streams that agreed would tie a mote's size to its
        // position, which reads as a gradient across the field.
        var agreements = 0
        for (cellY in -30..30)
        {
            for (cellX in -30..30)
            {
                val a = Motes.unitFloat(cellX, cellY, Motes.STREAM_SIZE)
                val b = Motes.unitFloat(cellX, cellY, Motes.STREAM_BRIGHTNESS)
                if (abs(a - b) < 0.02f) agreements++
            }
        }
        // 61 * 61 = 3721 cells; two independent uniform draws land within 0.02 of each other about
        // 4% of the time, i.e. ~147. A shared stream would give 3721.
        assertTrue(agreements < 400, "size and brightness agreed on $agreements of 3721 cells — the two streams are not independent")
    }

    // ---- The lattice -----------------------------------------------------------------------

    @Test
    fun `cell indices floor, so the grid is uniform through the origin`()
    {
        assertEquals(0, Motes.cellIndex(0f))
        assertEquals(0, Motes.cellIndex(Motes.CELL_METRES * 0.99f))
        assertEquals(1, Motes.cellIndex(Motes.CELL_METRES))
        assertEquals(-1, Motes.cellIndex(-0.01f), "a truncating conversion would put -0.01 in cell 0 and make the cell at the origin twice as wide")
        assertEquals(-1, Motes.cellIndex(-Motes.CELL_METRES))
        assertEquals(-2, Motes.cellIndex(-Motes.CELL_METRES - 0.01f))
    }

    @Test
    fun `a cell's origin and its index are inverses`()
    {
        for (index in -50..50)
        {
            assertEquals(index, Motes.cellIndex(Motes.cellOrigin(index)))
            assertEquals(index, Motes.cellIndex(Motes.cellOrigin(index) + Motes.CELL_METRES * 0.5f))
        }
    }

    /**
     * THE CULLING CONTRACT, and it is the one property the lattice cannot survive being wrong
     * about.
     *
     * `Motes.render` visits the cells overlapping the visible rect expanded by
     * `CULL_MARGIN_METRES`. That is only correct if a mote's drawn QUAD — its drifted centre plus
     * half its size — can never leave its own cell by more than that margin. If it can, motes
     * appear and vanish along the frame edge as the camera eases, which is invisible in a still
     * and obvious in motion.
     *
     * Swept over a full wrap, so it covers every phase of every rate option rather than the one
     * moment a fixed `t` would test.
     */
    @Test
    fun `no mote's quad can leave its own cell by more than the cull margin`()
    {
        val steps = 60
        var worstX = 0f
        var worstY = 0f
        for (cellY in -12..12)
        {
            for (cellX in -12..12)
            {
                val halfSize = Motes.sizeMetres(cellX, cellY) * 0.5f
                for (step in 0 until steps)
                {
                    val t = Motes.WRAP_SECONDS * step / steps
                    val x = Motes.moteX(cellX, cellY, t)
                    val depth = Motes.moteDepth(cellX, cellY, t)

                    val leftOverhang = Motes.cellOrigin(cellX) - (x - halfSize)
                    val rightOverhang = (x + halfSize) - (Motes.cellOrigin(cellX) + Motes.CELL_METRES)
                    val topOverhang = Motes.cellOrigin(cellY) - (depth - halfSize)
                    val bottomOverhang = (depth + halfSize) - (Motes.cellOrigin(cellY) + Motes.CELL_METRES)

                    worstX = max(worstX, max(leftOverhang, rightOverhang))
                    worstY = max(worstY, max(topOverhang, bottomOverhang))
                }
            }
        }
        assertTrue(
            worstX <= Motes.CULL_MARGIN_METRES + 1e-4f,
            "a mote's quad reaches ${worstX} m outside its cell horizontally, past the ${Motes.CULL_MARGIN_METRES} m cull margin — motes will pop in and out at the frame edge whenever the camera moves"
        )
        assertTrue(
            worstY <= Motes.CULL_MARGIN_METRES + 1e-4f,
            "a mote's quad reaches ${worstY} m outside its cell vertically, past the ${Motes.CULL_MARGIN_METRES} m cull margin"
        )
        // And the margin is not wildly generous either — a margin far larger than the worst case
        // is wasted cells every frame. The horizontal axis is the binding one by construction.
        assertTrue(
            worstX > Motes.CULL_MARGIN_METRES * 0.9f,
            "the cull margin (${Motes.CULL_MARGIN_METRES} m) is far larger than the worst overhang (${worstX} m), so every frame visits cells that can never contribute"
        )
    }

    // ---- The count -------------------------------------------------------------------------

    /**
     * How many motes a frame actually draws, computed from the same visible rect the camera
     * produces (`CameraRig.pixelsPerMetre` is the one place a metre becomes a pixel) and the same
     * alpha threshold `Motes.render` skips on.
     */
    /** As [visibleMoteCount], but only the motes that are also GI emitters. @see Motes.glows */
    private fun visibleGlowingCount(pixelWidth: Float, pixelHeight: Float, cameraDepth: Float) =
        visibleMoteCount(pixelWidth, pixelHeight, cameraDepth) { cellX, cellY -> Motes.glows(cellX, cellY) }

    private fun visibleMoteCount(
        pixelWidth: Float,
        pixelHeight: Float,
        cameraDepth: Float,
        include: (Int, Int) -> Boolean = { _, _ -> true }
    ): Int
    {
        val pixelsPerMetre = CameraRig.pixelsPerMetre(pixelWidth, pixelHeight)
        val halfWidth = pixelWidth / pixelsPerMetre * 0.5f
        val minCellX = Motes.cellIndex(-halfWidth - Motes.CULL_MARGIN_METRES)
        val maxCellX = Motes.cellIndex(halfWidth + Motes.CULL_MARGIN_METRES)
        val minCellY = max(
            Motes.cellIndex(cameraDepth - Motes.CULL_MARGIN_METRES),
            Motes.cellIndex(Tuning.SURFACE_DEPTH - Motes.CULL_MARGIN_METRES)
        )
        val maxCellY = Motes.cellIndex(cameraDepth + pixelHeight / pixelsPerMetre + Motes.CULL_MARGIN_METRES)

        var drawn = 0
        for (cellY in minCellY..maxCellY)
        {
            for (cellX in minCellX..maxCellX)
            {
                val depth = Motes.moteDepth(cellX, cellY, Motes.phaseSeconds)
                val alpha = Motes.depthGain(depth) * Motes.brightness(cellX, cellY) *
                    Motes.pulse(cellX, cellY, Motes.phaseSeconds)
                if (alpha >= Motes.MIN_VISIBLE_ALPHA && include(cellX, cellY)) drawn++
            }
        }
        return drawn
    }

    @Test
    fun `the field holds a few hundred motes at every aspect ratio the booth might have`()
    {
        val panels = listOf(
            Triple("16:9 (1920x1080)", 1920f, 1080f),
            Triple("4:3 dev window (2400x1800 physical)", 2400f, 1800f),
            Triple("21:9 (2560x1080)", 2560f, 1080f),
            Triple("32:9 (3840x1080)", 3840f, 1080f)
        )
        for ((name, w, h) in panels)
        {
            // Swept over the whole dive rather than sampled at one depth: the count varies with
            // where the cell grid happens to fall against the frame, and the ends of the range are
            // what a re-tuned CELL_METRES would push out first.
            var fewest = Int.MAX_VALUE
            var most = 0
            var cameraDepth = 6f
            while (cameraDepth <= Tuning.MAX_DEPTH)
            {
                val count = visibleMoteCount(w, h, cameraDepth)
                fewest = min(fewest, count)
                most = max(most, count)
                cameraDepth += 0.1f
            }
            assertTrue(
                fewest >= 100 && most <= 250,
                "$name draws $fewest..$most motes over a dive; the design range is 100..250. Motes.CELL_METRES is the only dial"
            )
        }
    }

    @Test
    fun `above the waterline the field draws nothing at all`()
    {
        // The diver at the surface: the camera top is 24 m of sky above the water (Sky.SPAN_METRES).
        val count = visibleMoteCount(1920f, 1080f, cameraDepth = -Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_SCREEN_FRACTION)
        assertTrue(count > 0, "a frame taken at the surface should still show the motes in the water below the waterline")

        // …and no mote in that frame is above the water.
        for (cellY in Motes.cellIndex(-40f)..Motes.cellIndex(0f) - 1)
        {
            for (cellX in -10..10)
            {
                val depth = Motes.moteDepth(cellX, cellY, 0f)
                if (depth < Tuning.SURFACE_DEPTH)
                {
                    assertEquals(0f, Motes.depthGain(depth), "a mote at depth $depth is above the waterline and would be drawn in the sky")
                }
            }
        }
    }

    // ---- The depth response ----------------------------------------------------------------

    @Test
    fun `the gain is exactly zero at and above the waterline`()
    {
        assertEquals(0f, Motes.depthGain(Tuning.SURFACE_DEPTH))
        assertEquals(0f, Motes.depthGain(Tuning.SURFACE_DEPTH - 0.01f))
        assertEquals(0f, Motes.depthGain(-50f))
        assertTrue(Motes.depthGain(Motes.SURFACE_FADE_METRES) > 0f, "the motes never fade in below the waterline")
    }


    @Test
    fun `the gain never exceeds the peak the brightness test is written against`()
    {
        var depth = Tuning.SURFACE_DEPTH
        while (depth <= Tuning.MAX_DEPTH + 40f)
        {
            assertTrue(Motes.depthGain(depth) <= Motes.PEAK_ALPHA + 1e-5f, "the gain at $depth m exceeds PEAK_ALPHA")
            depth += 0.25f
        }
    }

    // ---- The anglerfish's tell -------------------------------------------------------------

    /** Hue in degrees, 0..360, the standard HSV definition. */
    private fun hue(r: Float, g: Float, b: Float): Float
    {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val h = when
        {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    /** Relative luminance, Rec. 709 — the same weights the rest of this codebase quotes. */
    private fun luminance(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * THE ONE THAT IS A GAME RULE RATHER THAN AN ART OPINION.
     *
     * `docs/superpowers/specs/2026-08-11-outstanding-work.md` §1.1: *"The anglerfish renders
     * identically to a pearl and the only tell is motion... Fill the scene with glowing points and
     * a suspicious light stops being unusual — the trap stops working."* The resolution named there,
     * and the one taken: *"scenery glows a visibly different colour/shape from pearls (cool
     * cyan-green vs warm amber is cheapest and arguably better art)"*.
     *
     * So this is not "the motes are blue because blue is nice". It is: a player must be able to tell
     * a mote from a pearl, and therefore from the lure, AT A GLANCE AND IN PERIPHERAL VISION, or the
     * anglerfish stops being a trap and becomes a coin flip.
     *
     * Asserted against `DiveRenderer.pearlColor` itself — the reason that constant is `internal`
     * rather than `private`. A test written against a transcription of `(1, 0.78, 0.35)` would keep
     * passing after somebody cooled the pearls, which is exactly the drift it exists to catch.
     */
    @Test
    fun `the motes are far enough from the pearls' amber to keep the anglerfish's trap working`()
    {
        val pearl = DiveRenderer.pearlColor
        val pearlHue = hue(pearl.red, pearl.green, pearl.blue)
        val moteHue = hue(Motes.MOTE_RED, Motes.MOTE_GREEN, Motes.MOTE_BLUE)

        val raw = abs(moteHue - pearlHue)
        val separation = min(raw, 360f - raw)
        assertTrue(
            separation > 120f,
            "the motes' hue ($moteHue deg) is only $separation deg from the pearls' ($pearlHue deg). See spec 2026-08-11-outstanding-work.md 1.1: the anglerfish's lure is drawn identically to a pearl and the only tell is motion, so ambient glowing points must read as a different colour at a glance"
        )

        // The channel ORDER is reversed, which is the same statement in the form a colourblind
        // player or a dim frame still gets: the pearls' brightest channel is red, the motes' is blue.
        assertTrue(Motes.MOTE_BLUE > Motes.MOTE_GREEN, "the motes' blue channel must dominate — they are cool")
        assertTrue(Motes.MOTE_GREEN > Motes.MOTE_RED, "the motes must be blue-green, not blue-magenta")
        assertTrue(pearl.red > pearl.green && pearl.green > pearl.blue, "the pearls are no longer warm; re-read spec 1.1 before changing the motes to match")

        // And the two are separated in the channels themselves, not only in the derived hue.
        assertTrue(
            Motes.MOTE_RED < pearl.red * 0.4f,
            "the motes' red (${Motes.MOTE_RED}) is not far enough below the pearls' (${pearl.red}) — warming them toward amber is exactly what spec 1.1 forbids"
        )
        assertTrue(
            Motes.MOTE_BLUE > pearl.blue * 2f,
            "the motes' blue (${Motes.MOTE_BLUE}) is not far enough above the pearls' (${pearl.blue})"
        )
    }

    /**
     * The second half of the same rule: a mote must not be mistakable for a pearl by SIZE either.
     * `Framing.PEARL_SIZE_METRES` is the pearl's and the lure's quad; a glowing point that big is a
     * pearl as far as the player is concerned.
     */
    @Test
    fun `no mote is anywhere near the size of a pearl`()
    {
        assertTrue(
            Motes.MAX_SIZE_METRES < Framing.PEARL_SIZE_METRES * 0.8f,
            "the largest mote (${Motes.MAX_SIZE_METRES} m) is not clearly smaller than a pearl (${Framing.PEARL_SIZE_METRES} m) — see spec 2026-08-11-outstanding-work.md 1.1"
        )
        assertTrue(Motes.MIN_SIZE_METRES > 0f && Motes.MIN_SIZE_METRES < Motes.MAX_SIZE_METRES)

        for (cellY in -20..20)
        {
            for (cellX in -20..20)
            {
                val size = Motes.sizeMetres(cellX, cellY)
                assertTrue(size >= Motes.MIN_SIZE_METRES && size <= Motes.MAX_SIZE_METRES, "a mote at ($cellX, $cellY) is $size m, outside the authored range")
            }
        }
    }

    /**
     * The third half: a mote must not be BRIGHTER than a pearl. The pearls are what the game is
     * about and what the player is looking for; ambient scenery that outshines them buries the
     * signal in the noise, on top of the §1.1 problem.
     */
    @Test
    fun `the brightest possible mote stays below the pearls' own albedo`()
    {
        val pearl = DiveRenderer.pearlColor
        val pearlLuminance = luminance(pearl.red, pearl.green, pearl.blue)
        val brightestMote = luminance(Motes.MOTE_RED, Motes.MOTE_GREEN, Motes.MOTE_BLUE) *
            Motes.PEAK_ALPHA * Motes.MAX_BRIGHTNESS

        assertTrue(
            brightestMote < pearlLuminance * 0.75f,
            "the brightest a mote can be ($brightestMote) is not clearly below a pearl's albedo ($pearlLuminance). Ambient scenery must not compete with the thing the game is about"
        )
    }

    // ---- The motion ------------------------------------------------------------------------

    @Test
    fun `every rate is a whole number of cycles per wrap, so the wrap is invisible`()
    {
        for (cycles in Motes.DRIFT_X_CYCLES + Motes.DRIFT_Y_CYCLES + Motes.PULSE_CYCLES)
        {
            assertTrue(cycles > 0, "a non-positive cycle count ($cycles) either freezes a mote or runs it backwards")
        }
        for (cellY in -20..20)
        {
            for (cellX in -20..20)
            {
                assertEquals(Motes.moteX(cellX, cellY, 0f), Motes.moteX(cellX, cellY, Motes.WRAP_SECONDS), 1e-3f, "cell ($cellX, $cellY) is not back where it started at the wrap — the animation will jump every ${Motes.WRAP_SECONDS} s")
                assertEquals(Motes.moteDepth(cellX, cellY, 0f), Motes.moteDepth(cellX, cellY, Motes.WRAP_SECONDS), 1e-3f, "cell ($cellX, $cellY)'s depth is not back where it started at the wrap")
                assertEquals(Motes.pulse(cellX, cellY, 0f), Motes.pulse(cellX, cellY, Motes.WRAP_SECONDS), 1e-3f, "cell ($cellX, $cellY)'s pulse is not back where it started at the wrap")
            }
        }
    }

    @Test
    fun `the two drift axes never share a rate, so a mote's path is not a straight line`()
    {
        for (x in Motes.DRIFT_X_CYCLES)
        {
            for (y in Motes.DRIFT_Y_CYCLES)
            {
                assertTrue(x != y, "a mote drifting at $x cycles on both axes traces a straight line segment, which reads as mechanical")
            }
        }
    }

    @Test
    fun `the field is genuinely moving, and slowly`()
    {
        var moved = 0
        var fastest = 0f
        val dt = 1f
        for (cellY in -15..15)
        {
            for (cellX in -15..15)
            {
                var worst = 0f
                for (step in 0 until 40)
                {
                    val t = Motes.WRAP_SECONDS * step / 40f
                    val dx = Motes.moteX(cellX, cellY, t + dt) - Motes.moteX(cellX, cellY, t)
                    val dd = Motes.moteDepth(cellX, cellY, t + dt) - Motes.moteDepth(cellX, cellY, t)
                    worst = max(worst, max(abs(dx), abs(dd)))
                }
                if (worst > 0.001f) moved++
                fastest = max(fastest, worst)
            }
        }
        assertEquals(31 * 31, moved, "some motes are not moving at all")

        // THE CEILING IS THE ANGLERFISH'S DRIFT, NOT A NUMBER SOMEBODY LIKED.
        //
        // It was a flat 0.15 m/s, which the owner's "they should float a bit more around" ran
        // straight through — the amplitudes are now 2.2 m and 1.4 m and the peak is ~0.26 m/s. The
        // question that bound was really asking is "do these still read as SUSPENDED", and the game
        // already contains the speed at which drifting stops being ambient and starts being a
        // creature: Tuning.ANGLERFISH_DRIFT_SPEED, commented in that file as "the tell".
        //
        // A real pearl sits still and the lure drifts toward you; that is the whole trap, and §1.1
        // of the outstanding-work spec is about not polluting it. Colour separation is what keeps a
        // mote from being mistaken for the lure (see the colour test); this keeps the FIELD from
        // making drift itself unremarkable. A third of the fish's speed leaves the two clearly
        // different motions rather than a continuum.
        val ceiling = Tuning.ANGLERFISH_DRIFT_SPEED / 3f
        assertTrue(
            fastest < ceiling,
            "the fastest mote covers $fastest m in a second, against a ceiling of $ceiling " +
                "(a third of the anglerfish's ${Tuning.ANGLERFISH_DRIFT_SPEED} m/s tell) — at that " +
                "speed the field drifts like the lure does and marine snow stops reading as suspended"
        )
    }

    @Test
    fun `the pulse breathes without ever blinking out`()
    {
        var lowest = Float.MAX_VALUE
        var highest = 0f
        for (cellY in -15..15)
        {
            for (cellX in -15..15)
            {
                for (step in 0 until 60)
                {
                    val p = Motes.pulse(cellX, cellY, Motes.WRAP_SECONDS * step / 60f)
                    lowest = min(lowest, p)
                    highest = max(highest, p)
                }
            }
        }
        assertTrue(lowest >= 1f - Motes.PULSE_AMOUNT - 1e-4f, "the pulse dips to $lowest, below the authored floor")
        assertTrue(highest <= 1f + 1e-4f, "the pulse reaches $highest, above 1 — brightness lives in the depth ramp, not here")
        assertTrue(lowest > 0.5f, "the pulse takes a mote down to $lowest of its brightness, which reads as a blink; a blinking point in this game means an anglerfish")
        assertTrue(highest - lowest > 0.2f, "the pulse spans only ${highest - lowest} — the field will read as a static texture")
    }

    // ---- The clock -------------------------------------------------------------------------

    @Test
    fun `the clock wraps, and rejects the garbage a stalled frame produces`()
    {
        Motes.advance(10f)
        assertEquals(10f, Motes.phaseSeconds, 1e-4f)

        Motes.advance(Float.NaN)
        Motes.advance(-5f)
        Motes.advance(0f)
        assertEquals(10f, Motes.phaseSeconds, 1e-4f, "a NaN, negative or zero delta must not reach the clock")

        Motes.advance(Motes.WRAP_SECONDS)
        assertTrue(Motes.phaseSeconds < Motes.WRAP_SECONDS, "the clock did not wrap")
        assertEquals(10f, Motes.phaseSeconds, 1e-2f)
    }

    @Test
    fun `pinning the phase freezes the field, which is what the capture harness needs`()
    {
        Motes.pin(42f)
        val before = Motes.moteX(3, 7, Motes.phaseSeconds)
        Motes.advance(5f)
        assertEquals(42f, Motes.phaseSeconds, 1e-4f, "the pin did not hold")
        assertEquals(before, Motes.moteX(3, 7, Motes.phaseSeconds), 1e-6f)

        Motes.pin(-1f)
        assertTrue(Motes.phaseSeconds >= 0f && Motes.phaseSeconds < Motes.WRAP_SECONDS, "a negative pin was not folded into the wrap")
        Motes.pin(Motes.WRAP_SECONDS * 3.5f)
        assertEquals(Motes.WRAP_SECONDS * 0.5f, Motes.phaseSeconds, 1e-2f, "an enormous pin was not folded into the wrap")
    }

    @Test
    fun `advancing in small steps lands in the same place as one big step`()
    {
        Motes.advance(1f / 30f)
        val coarse = Motes.phaseSeconds
        Motes.unpin()
        repeat(4) { Motes.advance(1f / 120f) }
        assertEquals(coarse, Motes.phaseSeconds, 1e-4f, "the drift is not frame-rate independent")
    }

    // ---- The surface -----------------------------------------------------------------------



    /**
     * The clock is advanced from `onUpdate`, OUTSIDE the lifecycle gate, and drawn from `onRender`.
     *
     * Both halves matter and neither is provable without an engine. `RunLifecycle.simulationAdvances`
     * is FALSE in IDLE, so a mote field advanced inside that gate would be frozen solid on the
     * attract screen — which is the screen a booth queue spends most of its time looking at, and
     * `WaterSurface`'s clock note is the precedent this follows.
     */
    @Test
    fun `the field is advanced unconditionally and drawn every frame`()
    {
        val code = File("src/main/kotlin/EnPustTil.kt").readText()
        val advance = code.indexOf("Motes.advance(engine.data.deltaTime)")
        val gate = code.indexOf("if (lifecycle.simulationAdvances)")
        val onUpdate = code.indexOf("override fun onUpdate()")
        val onRender = code.indexOf("override fun onRender()")

        assertTrue(advance >= 0, "nothing advances the mote clock; the field will be frozen")
        assertTrue(gate in 0 until onUpdate, "onFixedUpdate's simulation gate moved — re-read this test")
        assertTrue(advance > onUpdate, "the mote clock is advanced from the fixed tick, which is gated on RunLifecycle.simulationAdvances and is FALSE in IDLE — the field would be frozen on the attract screen")
        assertTrue(code.contains("System.getenv(Motes.PIN_ENV)"), "EnPustTil no longer reads Motes.PIN_ENV (${Motes.PIN_ENV}), so a pinned capture cannot reproduce the field")
    }

    /**
     * THE MOTES ARE ON `main`, WHICH IS WHAT SUBJECTS THEM TO THE GI MULTIPLY.
     *
     * They had a surface of their own until it was measured at 140 m: exempt from the light map,
     * they were the brightest thing in the Abyss (peak 213.5 against the brightest pearl's 194.2)
     * and the field read as a starfield. The owner's instruction was to remove the exemption.
     *
     * This is asserted structurally rather than by value because the failure is silent and total:
     * a mote drawn to any other surface is simply not darkened by depth, and no unit test of the
     * arithmetic could tell, since every number in `Motes` would be identical either way.
     */
    @Test
    fun `the motes are drawn onto the world surface, so GI darkens them with everything else`()
    {
        val renderer = File("src/main/kotlin/render/DiveRenderer.kt").readText()
        val world = renderer.substringAfter("fun render(").substringBefore("\n    }")

        assertTrue(
            "Motes.render(surface, cam)" in world,
            "DiveRenderer no longer draws the motes onto its own surface — if they have been given " +
                "a surface of their own again they escape the GI multiply, and the Abyss gets its " +
                "starfield back"
        )
        // LAST in the world pass: suspended matter sits between the camera and everything else.
        assertTrue(
            world.indexOf("Motes.render(") > world.indexOf("drawDiver("),
            "the motes are drawn before the diver, so he floats in front of the water he is in"
        )
        // And nothing may quietly re-create a surface for them.
        val app = File("src/main/kotlin/EnPustTil.kt").readText()
        assertTrue(
            "name = Motes.SURFACE_NAME" !in app && "\"motes\"" !in app,
            "a motes surface has been created again; that is the exemption from the light map that " +
                "this change removed"
        )
    }

    // --- THE GLOWING SUBSET ---------------------------------------------------------------
    //
    // Added when the owner asked for the motes to "be more of a light source", which reversed this
    // file's own "a mote is not a drawLight and casts nothing". The reversal is safe only inside
    // bounds, and these are the bounds.

    /**
     * THE EMITTER COUNT ON SCREEN IS BOUNDED IN ABSOLUTE TERMS.
     *
     * The first version of this test compared the glowing fraction against `1f / Motes.GLOW_IN` —
     * i.e. against the very constant it was meant to guard — so setting `GLOW_IN = 1` and making
     * EVERY mote an emitter sailed through it. Caught by mutation, not by reading it. The bound has
     * to be an absolute count, because what it protects is absolute: every emitter is a quad
     * rasterised into `gi_local_scene` AND a region the torch's rays terminate on, and neither cost
     * cares what fraction of the field it represents.
     *
     * 20..90 keeps the emitters the same order as the pearls and vents already on screen. The
     * ceiling is the one that matters — the 4x GI resolution bump of 2026-08-12 is underneath this.
     */
    @Test
    fun `the glowing motes on screen stay the same order as the pearls already lit`()
    {
        val panels = listOf(
            Triple("16:9 (1920x1080)", 1920f, 1080f),
            Triple("21:9 (2560x1080)", 2560f, 1080f),
            Triple("32:9 (3840x1080)", 3840f, 1080f)
        )
        for ((name, w, h) in panels)
        {
            var fewest = Int.MAX_VALUE
            var most = 0
            var cameraDepth = 6f
            while (cameraDepth <= Tuning.MAX_DEPTH)
            {
                val count = visibleGlowingCount(w, h, cameraDepth)
                fewest = min(fewest, count)
                most = max(most, count)
                cameraDepth += 0.5f
            }
            assertTrue(
                fewest >= 20 && most <= 90,
                "$name lights $fewest..$most motes over a dive; the design range is 20..90. Every " +
                    "one is a GI emitter and a small occluder of the torch — Motes.GLOW_IN is the dial"
            )
        }
    }

    @Test
    fun `the glowing subset is decorrelated from how big a mote looks`()
    {
        // Chosen from its own hash stream, so glow must not track size — otherwise the field reads
        // as two classes of object rather than one substance catching the light unevenly.
        var glowingSizeSum = 0f; var glowingCount = 0
        var darkSizeSum = 0f; var darkCount = 0
        for (cellY in -40..40)
        {
            for (cellX in -40..40)
            {
                val size = Motes.sizeMetres(cellX, cellY)
                if (Motes.glows(cellX, cellY)) { glowingSizeSum += size; glowingCount++ }
                else { darkSizeSum += size; darkCount++ }
            }
        }
        val glowingMean = glowingSizeSum / glowingCount
        val darkMean = darkSizeSum / darkCount
        assertTrue(
            abs(glowingMean - darkMean) < 0.05f,
            "glowing motes average $glowingMean m and dark ones $darkMean m — the glow stream has " +
                "become correlated with the size stream, so the light picks out the big ones"
        )
    }

    /**
     * THE FIELD IS A WASH, NOT A SECOND LIGHTING RIG.
     *
     * The torch was made the deep's primary light source at the owner's request on 2026-08-12
     * (`DiveLighting.diverIntensityByZone`, 2.0 at the surface rising to 6.0 in the Abyss) and the
     * pearls were cut to a marker glow so that the beam is what reveals them. Motes that emit are
     * the third thing in that balance and must not disturb it: a single mote has to be far below a
     * pearl, which is itself far below the torch.
     */
    @Test
    fun `a glowing mote is dimmer than a pearl, which is dimmer than the torch`()
    {
        val brightestMote = Motes.GLOW_INTENSITY * Motes.PEAK_ALPHA
        val pearl = DiveLighting.pearlIntensity()
        val torchInTheDark = DiveLighting.diverIntensityForDepth(Tuning.MAX_DEPTH)

        assertTrue(
            brightestMote < pearl * 0.5f,
            "the brightest mote emits $brightestMote against a pearl's $pearl — a mote at half a " +
                "pearl's output stops being an ambient wash and starts competing with the thing " +
                "the player is hunting for"
        )
        assertTrue(
            pearl < torchInTheDark,
            "the torch is no longer the brightest light in the deep"
        )
    }

    @Test
    fun `the lights and the dots come out of the same traversal`()
    {
        // A light offset from the dot it belongs to is the drift class CLAUDE.md devotes a section
        // to, and the only structural defence is that there is exactly one cell walk. Both consumers
        // must go through Motes.forEachVisible; a second hand-rolled loop is the regression.
        val motes = File("src/main/kotlin/render/Motes.kt").readText()
        val lighting = File("src/main/kotlin/render/DiveLighting.kt").readText()

        assertTrue(
            motes.contains("fun render(surface: Surface, cam: Camera)") &&
                motes.substringAfter("fun render(surface: Surface, cam: Camera)").contains("forEachVisible"),
            "Motes.render no longer walks the cells through forEachVisible"
        )
        assertTrue(
            lighting.contains("Motes.forEachVisible"),
            "DiveLighting.drawMoteLights no longer walks the cells through Motes.forEachVisible, so " +
                "the emitters and the dots are derived separately and can drift apart"
        )
        assertTrue(
            lighting.contains("intensity = Motes.GLOW_INTENSITY * alpha"),
            "a glowing mote's emission no longer carries its own alpha, so it can light the water " +
                "from a depth at which the mote itself is invisible"
        )
    }
}
