package dive

/**
 * One frame of player intent.
 *
 * The stick is a full 2D swim direction. [kick] multiplies speed in whichever
 * direction you are heading, which is why the spec calls it both the descent
 * tool and the escape tool — and why escaping costs just as much air as diving.
 *
 * @param vertical -1 = swim up, +1 = swim down, 0 = neutral (passive sink)
 */
data class DiveInput(
    val horizontal: Float,
    val vertical: Float,
    val kick: Boolean,
    val bleed: Boolean
)
{
    companion object { val NONE = DiveInput(0f, 0f, kick = false, bleed = false) }
}
