package score

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Promote a fully-written [temp] file into [live] via an atomic filesystem rename.
 *
 * WHY THIS EXISTS: `engine.data.saveObject`/`saveObjectAsync` write DIRECTLY to the
 * target path with no temp file of their own — verified by decompiling `DataImpl`: it
 * serialises with Jackson, then calls `kotlin.io.FilesKt.writeBytes(file, bytes)`, which
 * truncates and overwrites in place. A hard power-off mid-write can leave `scoreboard
 * .json` truncated — and this game runs unattended for two days on hardware nobody is
 * watching. [ScoreRepository] instead has the engine write to a `.tmp` filename first,
 * then calls this function to swap it into place.
 *
 * GUARANTEE ACTUALLY ACHIEVED: writing to a temp file and only ever promoting a FULLY
 * WRITTEN temp file into [live] via an atomic rename means a crash can only ever leave
 * the OLD complete [live] file or the NEW complete one in place — never a half-written
 * one, because [live] itself is never opened for writing. This is a real, testable
 * atomic-replace on filesystems that support it (POSIX rename on macOS/Linux; NTFS on
 * Windows — the booth's actual OS — via `ReplaceFile`/`MoveFileEx`, which is what
 * `Files.move(ATOMIC_MOVE)` maps to). If the platform cannot guarantee atomicity,
 * [AtomicMoveNotSupportedException] is caught and this falls back to a plain
 * (non-atomic) replace rather than losing the write entirely.
 *
 * WHAT THIS DOES **NOT** PROTECT AGAINST: the temp file's own write being interrupted
 * mid-flight (harmless — that temp file is simply overwritten or ignored next time,
 * never promoted, so [live] is unaffected); a crash in the narrow window between "temp
 * write finished" and "this function being called" (equivalent to the write never
 * having happened — the safe outcome); or true hardware power loss before the OS has
 * flushed its write-back cache (this does not call `fsync`/`force`, so it guarantees no
 * TORN file, not that every acknowledged write has reached the physical disk).
 *
 * No `no.njoh.pulseengine` import — plain `java.io`/`java.nio.file` only — so this is
 * unit-testable against a real (temp-directory) filesystem without booting the engine.
 *
 * @return true if [live] now holds [temp]'s former contents, false if the promotion
 *   could not happen at all (e.g. [temp] does not exist) — [live] is left untouched.
 * @param onFailure called with the exception when the rename genuinely fails (as
 *   opposed to [temp] simply not existing, which is reported only through the return
 *   value). On Windows, `Files.move` can throw `AccessDeniedException` when an indexer
 *   or AV holds [live] open — this used to be swallowed by a bare `catch { false }` with
 *   no log and no rethrow, so a booth score could vanish with no trace anywhere. The
 *   caller decides what "trace" means (see `ScoreRepository.onSaveFailure`); this
 *   function only guarantees the exception is not silently discarded.
 */
fun promoteAtomically(temp: File, live: File, onFailure: (Exception) -> Unit = {}): Boolean
{
    if (!temp.exists()) return false
    return try
    {
        Files.move(temp.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    }
    catch (e: AtomicMoveNotSupportedException)
    {
        Files.move(temp.toPath(), live.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }
    catch (e: Exception)
    {
        // This used to be a bare `false`. On Windows, Files.move can fail with
        // AccessDeniedException when an indexer or AV holds the target open, and the
        // caller discarded the return value — so a booth score vanished with no trace in
        // any log, on a board the day's prizes are drawn from.
        onFailure(e)
        false
    }
}
