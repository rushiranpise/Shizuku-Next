package moe.shizuku.manager.shell

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.channels.Channel

/**
 * The shell's session and its transcript, for as long as the app is running.
 *
 * The shell is opened from the Labs list rather than being a page of its own, which means it is
 * built when it is opened and thrown away when it is left: anything held in `remember` inside it
 * leaves with it. That is fine for which sheet is open and what is typed in the field, and it is
 * not fine for the session and the output, because a shell you cannot leave for a moment without
 * losing what you ran is not a shell. They live here instead.
 *
 * [incoming] belongs here for the same reason and takes a little explaining: the command writes
 * its output from another thread through this channel, and the screen drains it on the main one.
 * Left unread while the screen is closed, an unlimited channel simply keeps the lines until it is
 * drained again, so coming back shows everything that arrived in the meantime rather than the
 * gap where the screen was not watching.
 */
object ShellContinuity {

    /** The working directory and anything exported, carried from command to command. */
    val session: ShellSession by lazy { ShellSession() }

    /** Everything that has been run and everything it printed. */
    val lines = mutableStateListOf<ShellLine>()

    /** Output on its way to [lines]. */
    val incoming = Channel<ShellLine>(Channel.UNLIMITED)
}
