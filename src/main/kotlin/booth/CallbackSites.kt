package booth

/**
 * The five names [CallbackGuard] wraps `EnPustTil`'s engine callbacks with — `onCreate`,
 * `onFixedUpdate`, `onUpdate`, `onRender`, `onDestroy`. Constants rather than literals at
 * each `guard.run(...)` call site, so a typo cannot silently create a sixth counter that
 * never merges with the site it meant to name, and so the booth status line
 * (`render.BoothStatus`) and the log agree on what a site is called.
 *
 * Lives here, beside [CallbackGuard], rather than in `EnPustTil`'s own companion object:
 * `CallbackGuard` is these five names' only real consumer (`EnPustTil` just supplies the
 * literal strings back to it), and putting them in the package that owns the concept means
 * `EnPustTil`'s companion object can stay `private` — no widening its other, unrelated
 * constants (`DAILY_SEED`, `STICK_DEADZONE`, `HUD_Z_ORDER`) just to make these five visible
 * to a test in a different package. `internal`, not `private`: `render.BoothStatusTest` and
 * the root-package `AttractScreenTest` both loop every real site name through
 * `BoothStatus.line`'s drawability check, so a future rename (an en dash slipped into a call
 * site name, say) cannot silently vanish from the attract screen with no test failing — see
 * `AttractScreenTest`'s class doc for the defect class that already happened once.
 */
internal object CallbackSites
{
    const val CREATE = "onCreate"
    const val FIXED_UPDATE = "onFixedUpdate"
    const val UPDATE = "onUpdate"
    const val RENDER = "onRender"
    const val DESTROY = "onDestroy"
}
