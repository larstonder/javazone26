package render

import ScreenText
import dive.DiveSim
import no.njoh.pulseengine.core.graphics.api.Camera
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color

/**
 * The faint "O2" written across each oxygen vent, so the animated blob reads as *air* rather
 * than as one more shiny thing in the water.
 *
 * The owner asked for this after seeing the new iridescent plume land: *"a faint O2 text on top
 * of the bubble so it makes more sense for the player that it's more oxygen"*. A vent and a pearl
 * are both iridescent metaballs drawn through [IridescenceRenderer], both roughly circular, and
 * the only thing separating them at a glance is size and hue — which is a weak signal in the
 * Twilight and no signal at all in the Trench, where the water has taken most of the colour out
 * of both. A two-character label is the cheapest possible fix and it is the one the arcade
 * cabinets this game is imitating would have used.
 *
 * ## Everything about it that is decided here, and why
 *
 * ### The string is `"O2"`, with an ASCII digit, and it lives in [ScreenText]
 *
 * NOT `"O₂"`. U+2082 SUBSCRIPT TWO is far outside the default font's baked U+0020..U+011F atlas,
 * and a glyph outside that atlas renders as **nothing at all** — no ink and no x-advance,
 * silently (see `DefaultFont`). The subscript form would have shipped a vent labelled "O" and
 * nothing would have said so. [ScreenText.VENT_OXYGEN] is in `ScreenText.all()`, which
 * `AttractScreenTest` sweeps, so a later "tidy-up" to the typographic form fails the build.
 *
 * ### It is drawn on the `"hud"` SURFACE, not on `main` with the vent
 *
 * Two independent reasons, and either alone would be enough:
 *
 *  - **Nothing in this game renders text in world space.** Every string goes through a
 *    screen-pixel surface, and `Surface.drawText`'s `fontSize` is in that surface's own units —
 *    on `main` that is METRES, so this label would be asking the baked font atlas for a glyph
 *    about 0.6 units tall. The atlas is not built for that, and the result is a filtering
 *    problem rather than a layout one.
 *  - **`main` is MULTIPLIED by the GI light map** (`CLAUDE.md`, and `DiveRenderer
 *    .GI_REFLECTANCE_FLOOR` for what that costs). A label drawn there would be at the mercy of
 *    wherever the diver happens to be pointing his torch, and the deepest vent sits at 107.0 m
 *    under the shipping seed (measured: 49.2 / 76.1 / 107.0 m for Kelp / Twilight / Trench at
 *    `DAILY_SEED` 20260902) where there is very little light to be at the mercy of. "Faint" has
 *    to be a value somebody chose, which means it has to be authored on the surface GI does not touch.
 *
 * ### It is anchored through `Camera.worldPosToScreenPos`, in `onRender`, this frame
 *
 * The same route — and for the same reason — as the HUD's air ring around the diver. That call
 * multiplies by `mainCamera`'s view matrix, built once in `gfx.initFrame` before any of our code
 * ran and used for the very frame the vent is being drawn into. Deriving the anchor any other way
 * (from `DiveCamera.depth`, most temptingly) reads camera state from a different point in the
 * frame and puts the label one frame ahead of the blob whenever the camera is easing. That is the
 * one drift risk `CLAUDE.md` still names, and it is only a risk for things on this surface.
 *
 * ### The layout is a RELATIONSHIP, so it is pure and asserted
 *
 * [fontSizeFor] and [centreOffsetPixels] take numbers and return numbers, with no `Surface` and no
 * GL context, exactly as [AttractLayout], [ControlHints] and [Hud]'s bubble-ring functions do. The
 * property worth testing is not "the right pixels came out" but *the label stays inside the plume
 * it is labelling at every aspect ratio* — see `VentLabelTest`, which is where the two constants
 * below are actually pinned.
 *
 * ## The two judgement calls
 *
 * ### How faint, and what a SPENT vent's label does
 *
 * [LIVE_DISPLAYED_ALPHA] is 0.38 — present at arcade viewing distance, subordinate to every
 * numeric HUD element (the tape body is 0.55, its graduations 0.72, the clock plate 0.82), and
 * well below the plume it sits on so it reads as a marking ON the blob rather than as a caption
 * pinned over it.
 *
 * A spent vent keeps its label, DIMMED — it does not lose it. That is not a new decision, it is
 * the one `DiveRenderer.drawAirPockets` already made and states: *"dimmed rather than hidden once
 * spent — knowing where a used vent was is what lets a player plan the next dive around it"*. A
 * label that vanished on use would contradict the draw colour immediately beside it, and would
 * take away exactly the information the dimming exists to preserve. [SPENT_DIM_FACTOR] is 0.40,
 * chosen to sit alongside the step the vent's own two draw colours make (`airPocketColor`
 * (0.65, 0.95, 1) to `airPocketSpentColor` (0.22, 0.34, 0.42) is a little over a third of the
 * linear length), so the writing fades with the plume rather than surviving it.
 *
 * ### Where on the blob it sits
 *
 * OVERLAID, not floating above. "On top of the bubble" is ambiguous in English and the resolution
 * chosen here is the one that keeps the label attached to the object: text drawn clear of the
 * silhouette is a second UI element hovering in the water, and at 55 m of visible depth there is
 * often another vent or a pearl in the gap it would hover in.
 *
 * DEAD CENTRE, and it was NOT centred first — the owner asked for it centred after seeing the
 * offset version on a real frame. That is worth recording rather than quietly overwriting,
 * because the two arguments for offsetting it are still true and someone will rediscover them:
 *
 *  - The plume's iridescent banding is strongest through the middle of the metaball, where it is
 *    thickest and where [IridescentMaterial.VENT]'s film has the most substrate behind it. Faint
 *    text laid across that is competing with the busiest part of the picture.
 *  - `Tuning.AIR_POCKET_PICKUP_RADIUS` is centred on `pocket.x, pocket.depth`, so the vent's
 *    centre is precisely where the diver's sprite is at the moment the breath is taken — a
 *    centred label is the one position guaranteed to be underneath him at the instant it matters.
 *
 * Both were reasoned, neither was measured, and a capture beat them: at 0.40 of the vent's height
 * the glyphs are small enough that the banding does not in fact swamp them, and the moment the
 * diver covers the label is the moment the player is no longer reading it. This is the same
 * precedence every appearance question in this codebase settles by — see the ACES/bloom
 * non-linearity notes in [IridescentMaterial], where algebra lost to a screenshot twice.
 *
 * [CENTRE_OFFSET_FRACTION] is still the one number that moves it, and `VentLabelTest` bounds it
 * rather than pinning it, so restoring an offset stays green while pushing the label off the blob
 * does not.
 */
object VentLabel
{
    /**
     * The label's font size as a fraction of the vent's on-screen HEIGHT.
     *
     * DERIVED FROM THE VENT, not from the screen. Every other piece of text in this game is a
     * fraction of screen height, because that is the only resolution- and aspect-independent
     * anchor a screen-space element has (`CLAUDE.md`: `engine.window.width/height` are PHYSICAL
     * framebuffer pixels and the booth panel's aspect is not known in advance). This one has a
     * better anchor available: it labels a specific object of a known world size, so sizing it
     * from that object's own on-screen extent keeps it proportional to the thing it is written on
     * at every aspect ratio AND through every camera ease — including the width-bound regime above
     * 1.6419 where the visible depth, and therefore the pixels-per-metre, changes with the panel
     * (`CameraRig.pixelsPerMetre`).
     *
     * 0.40 of a 2.4 m vent is 18.7 px on a 1080p 16:9 booth panel (37.4 px at 4K, 28.8 px in the
     * 1200x900 dev window, whose framebuffer is 2400x1800 on a Retina Mac — `engine.window.*` are
     * PHYSICAL pixels, which is why these are not the numbers the config file suggests). CHOSEN
     * AGAINST THE HUD'S OWN SMALLEST TEXT, not by eye: [Hud]'s travelling depth readout is 0.018
     * of screen height = 19.4 px on that panel and its graduation labels are 15 px, so this lands
     * between them. An earlier 0.26 gave 12.2 px,
     * BELOW everything else on the surface — and text too small to read is not faint, it is
     * absent, which is not what was asked for. Faintness is carried by [LIVE_DISPLAYED_ALPHA];
     * this constant only has to keep the label legible and inside its own blob.
     *
     * The upper bound is `VentLabelTest`'s fit assertion, which bites at about 0.56 (the vent's
     * cell is TALLER than it is wide, so its WIDTH is what runs out first) and, paired with
     * [CENTRE_OFFSET_FRACTION], at 0.50 vertically.
     */
    const val FONT_FRACTION_OF_VENT_HEIGHT = 0.40f

    /**
     * How far the label's centre sits from the vent's centre, as a signed fraction of the vent's
     * HEIGHT. Negative is UP: world and screen y both run downward here.
     *
     * ZERO — the label is centred on the blob, by the owner's call on a real frame. The class doc
     * has the two arguments for offsetting it that the capture overruled, and they are kept there
     * because they are the reasons someone will reach for a non-zero value again.
     */
    const val CENTRE_OFFSET_FRACTION = 0f

    /**
     * The opacity the live label is meant to DISPLAY at — not the number written into a `Color`.
     *
     * ALPHA ON THE HUD SURFACE IS SQUARED: a `Color(1, 1, 1, 0.15)` fill was measured landing as
     * RGBA(38, 38, 38, 6), i.e. 255 x 0.15 for the colour and 255 x 0.15 x 0.15 for the alpha (see
     * [Hud.tapeBg], which carries the full capture). An element authored at 38% would therefore
     * display at about 14% and read as nothing — which is exactly what happened to the depth tape
     * before anyone measured it. So this states the displayed value and [Hud.authoredAlphaFor]
     * does the pre-correction, as every other translucent element on this surface does.
     *
     * RAISED FROM 0.38 after the owner saw it on a real frame and asked for it brighter. The
     * ceiling is not arbitrary: `VentLabelTest` holds this strictly below the depth tape's
     * displayed 0.55 ([Hud.tapeBg], whose authored alpha squares to exactly that), because the
     * tape is the quietest instrument on this surface and a label at or above it stops reading as
     * a marking on an object and starts reading as a second readout. 0.50 is a third brighter
     * than the first attempt and still clears that bound with room in it.
     */
    const val LIVE_DISPLAYED_ALPHA = 0.50f

    /** See the class doc: consistent with the live/spent step `DiveRenderer` already draws. */
    const val SPENT_DIM_FACTOR = 0.40f

    /** [LIVE_DISPLAYED_ALPHA] after a vent has been breathed this dive. Dimmed, never zero. */
    const val SPENT_DISPLAYED_ALPHA = LIVE_DISPLAYED_ALPHA * SPENT_DIM_FACTOR

    /**
     * Pre-allocated, because this is the render path and a `Color` per vent per frame is exactly
     * the per-frame allocation `CLAUDE.md` forbids. Four of them rather than two mutated in place:
     * `Color` has four mutable float fields, so dimming "the" ink for a spent vent would re-tint
     * the live one for every vent drawn after it in the same frame — the shared-mutable-singleton
     * trap [Hud.legendInk] was split out to avoid.
     *
     * The ink is the vent's own pale cyan rather than white, so the writing belongs to the plume.
     *
     * `internal`, not private, ON PURPOSE: `VentLabelTest` reads the alpha back off these and
     * squares it. That is the one assertion that catches the trap this whole surface sets — an
     * author writing [LIVE_DISPLAYED_ALPHA] straight into the `Color` and shipping a label at 14%
     * instead of 38%. There is no way to see that from the outside of a private field.
     */
    internal val liveInk = Color(0.85f, 0.97f, 1f, Hud.authoredAlphaFor(LIVE_DISPLAYED_ALPHA))
    internal val spentInk = Color(0.72f, 0.82f, 0.88f, Hud.authoredAlphaFor(SPENT_DISPLAYED_ALPHA))

    /**
     * THE SHADOW IS NOT [drawTextWithOutline], AND THAT IS THE POINT.
     *
     * That helper hardcodes `setDrawColor(0f, 0f, 0f, 1f)` for its offset pass — a FULLY OPAQUE
     * black. Behind a 38%-opacity label that outline would be the most solid thing on screen and
     * the label would read as a black double-image with a faint cyan ghost beside it: the opposite
     * of what was asked for. So the same two-pass idea is reproduced here at the ink's own
     * opacity, reusing [textOutlineOffset] so the offset still scales with the framebuffer instead
     * of being a fixed pixel count.
     *
     * It is worth having at all for the reason `drawTextWithOutline` exists: a vent is drawn in
     * the sunlit Shallows and in the near-black Trench, and pale ink alone reads well against only
     * one of those.
     */
    internal val liveShadow = Color(0f, 0f, 0f, Hud.authoredAlphaFor(LIVE_DISPLAYED_ALPHA))
    internal val spentShadow = Color(0f, 0f, 0f, Hud.authoredAlphaFor(SPENT_DISPLAYED_ALPHA))

    /** Centred on the anchor in both axes — see [Hud]'s `TEXT_CENTRED_ON_Y` for what 0.5 means. */
    private const val TEXT_CENTRED = 0.5f

    /**
     * The label's font size, in screen pixels, for a vent drawn [ventHeightMetres] tall at
     * [pixelsPerMetre].
     *
     * Returns 0 for a degenerate camera — frame one before `initFrame` has run against a written
     * camera, or mid-resize, where `pixelsPerMetre` can be zero or negative — and [render] skips
     * the draw entirely on that. Same one-frame-nothing-drawn trade [DiveRenderer.stripCount]
     * already makes deliberately, and far better than asking the engine to rasterise glyphs at a
     * negative size. Written `!(x > 0f)` so NaN falls out here too.
     */
    fun fontSizeFor(ventHeightMetres: Float, pixelsPerMetre: Float): Float
    {
        val size = ventHeightMetres * pixelsPerMetre * FONT_FRACTION_OF_VENT_HEIGHT
        return if (!(size > 0f)) 0f else size
    }

    /**
     * The label's centre, in screen pixels, relative to the vent's centre. Negative is up.
     * Signed and separate from [fontSizeFor] so `VentLabelTest` can assert the two TOGETHER —
     * the property that matters is where the label's BOX ends up, which needs both.
     */
    fun centreOffsetPixels(ventHeightMetres: Float, pixelsPerMetre: Float): Float =
        ventHeightMetres * pixelsPerMetre * CENTRE_OFFSET_FRACTION

    /**
     * Draw one "O2" per on-screen vent onto [surface], which must be the `"hud"` surface.
     *
     * [cam] is the world camera, passed in rather than fetched, exactly as `DiveRenderer` and
     * `DiveLighting` take it — only `EnPustTil` names `engine.gfx.mainCamera` at all, and
     * `MainCameraOwnershipTest`'s allow-list is exact set equality. It is READ here and never
     * written.
     *
     * [pixelsPerMetre] is the HUD's own scale, already computed in `EnPustTil.onRender` from this
     * frame's matrix as the screen distance between two world points one metre apart. Handed in
     * rather than recomputed: a second derivation is a second thing that can disagree, and the
     * camera has no roll or perspective, so one metre is the same number of pixels wherever it is
     * measured.
     *
     * ## Culling delegates to `DiveRenderer.ventCullSizeFor`
     *
     * The same square, through the same function, as the draw this labels. The label lies entirely
     * inside the vent's rect (`VentLabelTest` asserts it), so a vent that is culled has nothing to
     * label — and sharing the function rather than restating `max(height, width)` means the two
     * cannot drift apart if the sheet is ever re-baked at a different aspect.
     */
    fun render(surface: Surface, sim: DiveSim, cam: Camera, pixelsPerMetre: Float, screenHeight: Float)
    {
        val height = Framing.AIR_POCKET_SIZE_METRES
        val fontSize = fontSizeFor(height, pixelsPerMetre)
        if (fontSize <= 0f) return

        val cullSize = DiveRenderer.ventCullSizeFor(height)
        val offset = centreOffsetPixels(height, pixelsPerMetre)
        val shadowOffset = textOutlineOffset(screenHeight)

        sim.airPockets.forEach { pocket ->
            if (!cam.showsSquare(pocket.x, pocket.depth, cullSize)) return@forEach

            // The returned Vector2f is the camera's SHARED instance (Camera.kt:85), clobbered by
            // the next call — both components are read out into locals before anything else asks.
            // No allocation: that is the whole reason the engine hands back one buffer.
            val anchor = cam.worldPosToScreenPos(pocket.x, pocket.depth)
            val x = anchor.x
            val y = anchor.y + offset

            val ink = if (pocket.usedThisDive) spentInk else liveInk
            val shadow = if (pocket.usedThisDive) spentShadow else liveShadow

            surface.setDrawColor(shadow)
            surface.drawText(
                ScreenText.VENT_OXYGEN,
                x + shadowOffset, y + shadowOffset,
                fontSize = fontSize, xOrigin = TEXT_CENTRED, yOrigin = TEXT_CENTRED
            )
            surface.setDrawColor(ink)
            surface.drawText(
                ScreenText.VENT_OXYGEN,
                x, y,
                fontSize = fontSize, xOrigin = TEXT_CENTRED, yOrigin = TEXT_CENTRED
            )
        }
    }
}
