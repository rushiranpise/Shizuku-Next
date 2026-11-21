package moe.shizuku.manager.manage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.shell.ShellBackend
import moe.shizuku.manager.shell.ShellSession
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine

/**
 * What can be *done* to the saved wifi networks, as the shell does it.
 *
 * Reading the list is [WifiPasswords], which is one privileged binder call because the keys are
 * not in any output an app or a shell command prints. Doing something to a network is the other
 * way round: the platform's own `cmd wifi` already speaks every operation wanted here - it lists
 * networks with the ids the platform knows them by, forgets one by that id, and connects to one
 * given a security type and a passphrase - and it is written to be run by exactly the uid this
 * app borrows. So there is no hidden interface to reach for and no shape to guess at, and what
 * the app adds is the absent half: the key, which only the privileged read can produce.
 *
 * Every operation is checked rather than trusted. `cmd wifi` prints nothing on success and little
 * on failure, and exits 0 for both, so a write that reports itself done is not evidence that it
 * was: each one re-reads the list afterwards and answers with what is now true. That is also why
 * the results are counts and booleans rather than throwables - the caller shows a person what
 * happened, and "the network is gone from the list" is the thing that happened.
 */
object WifiControl {

    private const val TAG = "WifiControl"

    /** One saved network as `cmd wifi` sees it: the id every write needs, and how it is secured. */
    data class Entry(val id: Int, val ssid: String, val security: String)

    /** The radio's state and, when something is connected, the name it is connected to. */
    data class State(val enabled: Boolean, val connected: String?)

    /**
     * What came of asking to join a network.
     *
     * Three answers rather than a boolean because two of them need saying differently: a phone
     * whose radio is off cannot join anything, and "that did not work" is no help with a switch to
     * flip. The platform's own command is the reason this must be asked either way - `connect-network`
     * exits 0 on a phone with wifi off without joining anything.
     */
    enum class Join { CONNECTED, RADIO_OFF, FAILED }

    /** The platform's own commands: the list, and the one line saying what is joined. */
    private const val LIST = "cmd wifi list-networks"
    private const val STATUS = "cmd wifi status"

    /** The `^` the platform appends to the second security type of a transition-mode network. */
    private const val TRANSITION = '^'

    suspend fun state(): State? {
        // Asked twice, because this one read comes back with neither of the two lines it prints
        // often enough to matter - a command run through a shell that has just been spawned can
        // answer short - and a state nobody can read is a state the screen cannot act on.
        var unreadable: String? = null
        repeat(2) {
            val result = runCommand(STATUS) ?: return null
            if (result.first != 0) return null

            val state = stateOf(result.second)
            if (state != null) {
                Diag.info(
                    TAG,
                    "$STATUS: exit ${result.first}, ${result.second.lines().size} lines, state $state"
                )
                return state
            }
            unreadable = result.second
        }

        // What it did say, so that a short read can be told from an output format that changed.
        Diag.warn(
            TAG,
            "$STATUS said neither line twice: " + unreadable.orEmpty().trim().replace('\n', ' ')
        )
        return null
    }

    /** The saved networks, one row per id, or null when there is no shell to ask. */
    suspend fun saved(): List<Entry>? = runCommand(LIST)?.let { (code, printed) ->
        if (code != 0) return@let null
        val entries = entries(printed)
        // Written down because this is the one call whose silence is invisible on the screen: no
        // rows means every row's actions are disabled, which looks exactly like a phone with
        // nothing saved unless somebody can see what the command actually answered.
        Diag.info(TAG, "$LIST: exit $code, ${printed.lines().size} lines, ${entries.size} rows")
        entries
    }

    /**
     * Connects to a saved network, giving `cmd wifi` the key this app already read out of the
     * privileged list.
     *
     * The security type is [WifiPasswords.SavedNetwork.security] - the label the read worked out
     * from the config's own key management - because it is the one describing the key being sent
     * with it.
     */
    suspend fun connect(ssid: String, security: String, password: String): Join {
        // Asked before the command rather than after it: nothing can be joined with the radio off,
        // and saying so is worth more than watching a command do nothing successfully.
        val before = state()
        if (before?.enabled == false) {
            Diag.info(TAG, "connect to $ssid: not attempted, the radio is off")
            return Join.RADIO_OFF
        }

        val type = type(security)
        val command = buildString {
            append("cmd wifi connect-network ").append(quote(ssid)).append(' ').append(type)
            if (password.isNotEmpty() && type != "open" && type != "owe") {
                append(' ').append(quote(password))
            }
        }
        val result = runCommand(command) ?: return Join.FAILED

        // Read back by name rather than by "something is connected", and by name is where the
        // platform's own exit code stops being an answer: it is 0 for a command that did nothing.
        val after = state()
        val verdict = joinVerdict(before?.enabled, result.first, after?.connected, ssid)

        // A command that failed on a phone whose radio is off failed for that reason, whatever it
        // answered: saying so is the difference between a switch somebody can flip and a password
        // they would go looking for instead.
        val answer = if (verdict == Join.FAILED && after?.enabled == false) Join.RADIO_OFF else verdict

        Diag.info(
            TAG,
            "connect to $ssid: exit ${result.first}, joined now: ${after?.connected ?: "nothing"}, $answer"
        )
        return answer
    }

    /**
     * What a connection attempt came to.
     *
     * A phone that said the radio was off cannot have joined anything whatever the command
     * answered; otherwise the name the status reports afterwards is the answer, and anything but
     * the name that was asked for is a failure.
     */
    internal fun joinVerdict(
        radioEnabled: Boolean?,
        exit: Int,
        joined: String?,
        ssid: String
    ): Join = when {
        radioEnabled == false -> Join.RADIO_OFF
        exit != 0 -> Join.FAILED
        joined?.equals(ssid, ignoreCase = true) == true -> Join.CONNECTED
        else -> Join.FAILED
    }

    /**
     * Adds a network back, from a file rather than from the phone.
     *
     * This is the one write whose security type and key come from outside: an export carries both,
     * because on another phone there is nothing to read them from. The platform's own flags carry
     * the two things a file can also say - a hidden network, and one that may not be joined by
     * itself - so a file written by the app this was ported from comes back as what it described.
     */
    suspend fun add(network: WifiBackup.Network): Boolean {
        val type = type(network.security)
        val command = buildString {
            append("cmd wifi add-network ").append(quote(network.ssid)).append(' ').append(type)
            if (network.password.isNotEmpty() && type != "open" && type != "owe") {
                append(' ').append(quote(network.password))
            }
            if (network.hidden) append(" -h")
            if (!network.autojoin) append(" -d")
        }

        val result = runCommand(command) ?: return false
        if (result.first != 0) Diag.warn(TAG, "adding ${network.ssid} exited ${result.first}")
        return result.first == 0
    }

    /**
     * Adds every network a file held, and answers how many of them the phone has now.
     *
     * Counted against one listing taken after the whole file rather than after each network: every
     * check here is a shell command of its own, and a file of thirty networks is thirty of them.
     */
    suspend fun importAll(networks: List<WifiBackup.Network>): Int {
        networks.forEach { network -> runCatching { add(network) } }

        val names = saved().orEmpty().map { it.ssid.lowercase() }.toSet()
        val present = networks.count { it.ssid.lowercase() in names }
        Diag.info(TAG, "import: ${networks.size} in the file, $present on the phone now")
        return present
    }

    /**
     * Forgets one network and answers whether it is gone.
     *
     * The id is the whole point of asking `list-networks` first: `cmd wifi` forgets by the
     * platform's own id, not by name, and two configs of the same name are exactly the case this
     * screen exists to show.
     */
    suspend fun forget(id: Int): Boolean {
        val result = runCommand("cmd wifi forget-network $id") ?: return false
        val gone = saved()?.none { it.id == id } == true
        Diag.info(TAG, "forget $id: exit ${result.first}, gone: $gone")
        return gone
    }

    /**
     * Switches the Wi-Fi client on, for the start that took it down with its hotspot.
     *
     * True when the command ran and succeeded, false when it ran and failed, null when there was
     * no shell to run it in (the server is not up yet and the phone is not rooted).
     */
    suspend fun enable(): Boolean? {
        val result = runCommand("cmd wifi set-wifi-enabled enabled") ?: return null
        return result.first == 0
    }

    /**
     * Forgets every saved network, one at a time, and answers how many went.
     *
     * Asked for as a list of ids rather than by clearing the store: each operation is the same
     * one the single forget uses, so a failure leaves the rest of the list reachable instead of
     * half-cleared, and the count that comes back is what actually disappeared.
     */
    suspend fun forgetAll(): Int {
        val before = saved() ?: return 0
        var forgotten = 0
        for (entry in before) {
            runCommand("cmd wifi forget-network ${entry.id}")
            if (saved()?.none { it.id == entry.id } == true) forgotten++
        }
        Diag.info(TAG, "forget all: $forgotten of ${before.size} gone")
        return forgotten
    }

    /**
     * The saved networks `cmd wifi` prints, one row per id.
     *
     * Its columns are padded rather than separated, and an SSID has spaces in it, so the split is
     * on runs of two spaces. A network whose security supports a transitional second type is
     * printed twice, the second marked with `^`; both rows are one network, and the first is the
     * one kept.
     */
    internal fun entries(printed: String): List<Entry> {
        val byId = linkedMapOf<Int, Entry>()

        printed.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Network Id")) return@forEach

            val columns = trimmed.split(TWO_OR_MORE_SPACES)
            if (columns.size < 3) return@forEach
            val id = columns[0].toIntOrNull() ?: return@forEach
            val ssid = columns[1].trim()
            val security = columns[2].trim().trimEnd(TRANSITION)
            if (ssid.isEmpty()) return@forEach

            byId.putIfAbsent(id, Entry(id = id, ssid = ssid, security = security))
        }

        return byId.values.sortedBy { it.ssid.lowercase() }
    }

    /**
     * What `cmd wifi status` says, or null when it said neither of the two things it can say.
     *
     * Enabled is claimed only on the platform's own line for it, never by default: a read taken
     * while the radio is being switched prints neither line, and "not disabled" read as enabled is
     * how a phone with wifi switched off comes back saying it is on.
     */
    internal fun stateOf(printed: String): State? = when {
        printed.contains("Wifi is disabled", ignoreCase = true) -> State(false, null)
        printed.contains("Wifi is enabled", ignoreCase = true) -> State(true, connected(printed))
        else -> null
    }

    /**
     * Which platform id belongs to each name, from what `cmd wifi` listed.
     *
     * [previous] is kept when the shell's answer is empty while the privileged read says networks
     * exist. A read taken in the moment after a forget has come back with no rows at all - as has
     * a status read with no output whatsoever, which is what the log showed on a device - and
     * taking any of those as the truth would disable every action on the screen that has just been
     * told what it forgot, until somebody refreshed it by hand.
     */
    internal fun idsBySsid(
        saved: List<Entry>,
        networks: Int,
        previous: Map<String, Int>
    ): Map<String, Int> = if (saved.isEmpty() && networks > 0) {
        previous
    } else {
        saved.associate { it.ssid.lowercase() to it.id }
    }

    /** The name in `cmd wifi status`, or null when nothing is connected. */
    internal fun connected(printed: String): String? {
        val match = CONNECTED.find(printed) ?: return null
        val name = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
        return name
            .takeIf { it.isNotEmpty() }
            // What the platform prints when the radio is on and nothing is joined.
            ?.takeIf { !it.startsWith("<") }
    }

    /** The security type `connect-network` wants, from the label the read worked out. */
    internal fun type(security: String): String = when {
        security.startsWith("WPA3") -> "wpa3"
        security.startsWith("WPA2") || security.startsWith("WPA") -> "wpa2"
        security.startsWith("WEP") -> "wep"
        security.equals("OWE", ignoreCase = true) -> "owe"
        else -> "open"
    }

    /**
     * One command through the shell this app already has, as (exit code, everything it printed).
     *
     * Root when the device has it, because `cmd wifi` reaches the same service either way and a
     * rooted phone should not need the server running; Shizuku otherwise. Null when there is
     * neither, which is the caller's way of saying "nothing was attempted".
     */
    private suspend fun runCommand(command: String): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val rooted = runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
            if (!rooted && !ShizukuStateMachine.isRunning()) return@withContext null

            val backend = if (rooted) ShellBackend.ROOT else ShellBackend.SHIZUKU
            val printed = StringBuilder()

            val code = runCatching {
                ShellSession().run(backend, command) { line ->
                    printed.append(line.text).append('\n')
                }
            }.getOrElse { throwable ->
                // Both the stderr the command wrote and the reason it could not run are worth
                // having in the on-device log: this is a screen where "it did nothing" is
                // otherwise indistinguishable from a refusal.
                Diag.warn(TAG, "$command did not run", throwable)
                return@withContext null
            }

            if (code != 0) Diag.warn(TAG, "$command exited $code: ${printed.toString().trim()}")
            code to printed.toString()
        }

    /**
     * A shell word for an argument, quoted when it needs to be.
     *
     * An SSID is a name somebody else chose and a passphrase is whatever was typed, so both can
     * hold a space, a quote or a `$`. Single quotes are what a shell takes literally; the only
     * character they cannot hold is the one that ends them, which is written as a closed quote,
     * an escaped quote and a reopened one.
     */
    internal fun quote(word: String): String = "'" + word.replace("'", "'\\''") + "'"

    private val TWO_OR_MORE_SPACES = Regex("\\s{2,}")

    /** `SSID: "name"`, or the bare form older builds print. */
    private val CONNECTED = Regex("""SSID:\s*(?:"([^"]*)"|([^,]+))""")
}
