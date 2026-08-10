package render

import dive.Tuning
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the god rays are: the surface-anchored shafts of daylight that fall diagonally through
 * the shallow water. Pure Kotlin, no engine imports, so the geometry is unit-testable — the
 * lights themselves are issued by `DiveLighting.drawLightShafts`.
 *
 * ## WHY THEY ARE LIGHTS AND NOT PAINT
 *
 * The owner's instruction was literally "create the god-rays as actual lights", and it is the
 * only thing that can work here. `GlobalIlluminationSystem` MULTIPLIES `mainSurface` by the
 * computed light map, so anything painted onto `mainSurface` as albedo is scaled by whatever
 * light reaches it — and the whole point of the deep is that very little does. A painted shaft
 * would therefore be brightest exactly where the water is already bright and would fade out
 * along with everything else as it descended, which is backwards. That is the same argument
 * `d6faaa5` made for the diver's rim (see CLAUDE.md), and it applies here with more force
 * because a shaft is much larger than a rim and spans a much larger range of ambient.
 *
 * ## THE GEOMETRY, AND WHAT IS ANCHORED
 *
 * A shaft hangs FROM THE SURFACE. That is its defining property — it is what makes it read as
 * daylight coming in from above rather than as a glow in the middle of the water — so [topX] is
 * the anchor and the quad's CENTRE is derived from it ([centreX] / [centreDepth]), never the
 * other way round. `GiSceneRenderer.drawLight` takes a centre-origin quad plus an angle, so the
 * derivation has to reproduce the rotation `scene.vert` applies:
 *
 * ```
 * scene.vert:101   offset = (vertexPos - vec2(0.5)) * adjustedSize * rotate(radians(angle))
 * scene.vert:50-55 mat2 rotate(a) { return mat2(c, s, -s, c); }      // COLUMN-major
 * ```
 *
 * `v * m` in GLSL is a row-vector product, so the quad's local down-axis `(0, 1)` comes out at
 * world `(sin a, cos a)` — in world coordinates, where +x is right and +y IS DEPTH and runs
 * DOWN. A shaft that leans to the right as it descends therefore wants a POSITIVE angle, and
 * its bottom end lands at `top + length * (sin a, cos a)`. `LightShaftsTest` re-applies that
 * matrix independently and checks the top edge lands back on the surface at [topX]; getting the
 * sign wrong would hang the shafts UP out of the water, which is the kind of thing that looks
 * plausible in a thumbnail.
 *
 * ## THE TABLE IS AUTHORED, NOT SEEDED
 *
 * Pearl and vent placement come from the daily seed because they are gameplay — what a player
 * has to learn. Shafts are scenery: they never move, block nothing, and are worth no points, so
 * seeding them would only make two days' screenshots incomparable for no gain. They are five
 * hand-placed numbers, spread across the [Tuning.COLUMN_HALF_WIDTH] column with deliberately
 * unequal spacing and widths so the eye does not read a repeating pattern.
 *
 * The lengths are what the DEPTH RAMP acts on: `DiveLighting.shaftDaylightForDepth` is evaluated
 * at each shaft's own [centreDepth], so a longer shaft is automatically dimmer, and one long
 * enough to reach the Trench is automatically nothing at all. Adding a sixth shaft cannot lift
 * the deep no matter what length it is given.
 */
object LightShafts
{
    /**
     * The sun's tilt from vertical, in degrees, and the `angle` handed to `drawLight` — the two
     * are the same number, which is only true because of the world-space derivation in the class
     * doc, and is why this is stated once.
     *
     * ONE ANGLE FOR ALL FIVE. There is one sun. Shafts that fanned would read as several light
     * sources, or as an underwater lamp, and the mockup's whole trick is that the diagonal is
     * consistent enough to tell you which way is up before you have found the surface line.
     *
     * 18 degrees: steep enough that a 50 m shaft only wanders 15 m sideways (so it stays inside
     * the column rather than disappearing into the rock wall), shallow enough to be obviously
     * not vertical at a glance.
     */
    const val TILT_DEGREES = 18f

    /** Where each shaft meets the surface, in world metres. */
    private val topX = floatArrayOf(-34f, -15f, 2f, 21f, 37f)

    /** How wide each shaft is across its spine, in metres. */
    private val widthMetres = floatArrayOf(3.5f, 2.2f, 4.6f, 2.6f, 4f)

    /** How far each shaft reaches along its own axis, in metres. */
    private val lengthMetres = floatArrayOf(58f, 74f, 47f, 66f, 54f)

    val count: Int get() = topX.size

    /**
     * Where shaft [index] meets the surface. The ANCHOR — [centreX] and [centreDepth] are derived
     * from it, and `LightShaftsTest` walks the derivation back to here through `scene.vert`'s own
     * rotation matrix.
     */
    fun topAnchorX(index: Int) = topX[index]

    fun width(index: Int) = widthMetres[index]

    fun length(index: Int) = lengthMetres[index]

    /**
     * The quad's centre, derived from the surface anchor by walking half the shaft's length along
     * the tilt. `sin` for x and `cos` for depth, with NO negation anywhere: unlike
     * `DiveLighting.torchOffsetY`, this is not converting out of `GiSceneRenderer`'s
     * counter-clockwise-from-+x-with-+y-up heading convention — it is reproducing `scene.vert`'s
     * quad rotation, which is done in world space where +y is already depth. The two conventions
     * differ by exactly that flip, and mixing them up is why this says so here.
     */
    fun centreX(index: Int) = topAnchorX(index) + lengthMetres[index] * 0.5f * sin(tiltRadians())

    /** @see centreX */
    fun centreDepth(index: Int) =
        Tuning.SURFACE_DEPTH + lengthMetres[index] * 0.5f * cos(tiltRadians())

    /**
     * The larger of the quad's two sides — what the on-screen test is given, because
     * `Camera.showsSquare` takes a single size and a shaft is ten times longer than it is wide.
     * Testing the long side against both axes keeps a shaft that is only just off the top of the
     * frame from being culled while its tail is still lighting water that is on it.
     */
    fun boundingSize(index: Int) = maxOf(widthMetres[index], lengthMetres[index])

    private fun tiltRadians() = Math.toRadians(TILT_DEGREES.toDouble()).toFloat()
}
