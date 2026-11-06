package rikka.shizuku.server

import android.os.FileObserver
import android.util.Log
import java.io.File
import java.util.*
import java.util.concurrent.CopyOnWriteArraySet

interface ApkChangedListener {
    fun onApkChanged()
}

private val observers = Collections.synchronizedMap(HashMap<String, ApkChangedObserver>())

object ApkChangedObservers {

    /**
     * Starts watching the folder that holds [apkPath] and adds [listener].
     *
     * Returns false when the folder is not there yet, so the caller can retry instead of
     * leaving the watch unregistered.
     */
    @JvmStatic
    fun start(apkPath: String, listener: ApkChangedListener): Boolean {
        // inotify watchs inode, if the there are still processes holds the file, DELTE_SELF will not be triggered
        // so we need to watch the parent folder

        val path = File(apkPath).parent ?: return false
        synchronized(observers) {
            val existing = observers[path]
            if (existing != null) {
                existing.addListener(listener)
                return true
            }
            if (!File(path).isDirectory) {
                return false
            }
            val observer = ApkChangedObserver(path)
            observers[path] = observer
            observer.startWatching()
            observer.addListener(listener)
            return true
        }
    }

    /**
     * Removes [listener] from every observer and stops any observer left without listeners.
     *
     * The map is synchronized while it is iterated because an APK event removes its own
     * observer from another thread.
     */
    @JvmStatic
    fun stop(listener: ApkChangedListener) {
        synchronized(observers) {
            val iterator = observers.entries.iterator()
            while (iterator.hasNext()) {
                val observer = iterator.next().value
                observer.removeListener(listener)

                if (!observer.hasListeners()) {
                    iterator.remove()
                    observer.stopWatching()
                }
            }
        }
    }
}

/** Watches one APK folder for a `base.apk` deletion. */
class ApkChangedObserver(private val path: String) : FileObserver(path, DELETE) {

    private val listeners = CopyOnWriteArraySet<ApkChangedListener>()

    /** Adds [listener], returning false when it was already registered. */
    fun addListener(listener: ApkChangedListener): Boolean {
        return listeners.add(listener)
    }

    /** Removes [listener], returning false when it was not registered. */
    fun removeListener(listener: ApkChangedListener): Boolean {
        return listeners.remove(listener)
    }

    /** True while at least one listener still needs this observer. */
    fun hasListeners(): Boolean {
        return listeners.isNotEmpty()
    }

    /**
     * Stops the watch on a `base.apk` deletion, removes this observer from the shared map, and
     * tells every listener.
     */
    override fun onEvent(event: Int, path: String?) {
        Log.d("ShizukuServer", "onEvent: ${eventToString(event)} $path")

        if ((event and 0x00008000 /* IN_IGNORED */) != 0 || path == null) {
            return
        }

        if (path == "base.apk") {
            stopWatching()
            // Out of the map as well, or a later start() for the same folder would be handed
            // this stopped observer back and the new listener would never hear anything.
            observers.remove(this.path, this)
            ArrayList(listeners).forEach { it.onApkChanged() }
        }
    }

    /** Starts the inotify watch and logs its path. */
    override fun startWatching() {
        super.startWatching()
        Log.d("ShizukuServer", "start watching $path")
    }

    /** Stops the inotify watch and logs its path. */
    override fun stopWatching() {
        super.stopWatching()
        Log.d("ShizukuServer", "stop watching $path")
    }
}

private fun eventToString(event: Int): String {
    val sb = StringBuilder()
    if (event and FileObserver.ACCESS == FileObserver.ACCESS) {
        sb.append("ACCESS").append(" | ")
    }
    if (event and FileObserver.MODIFY == FileObserver.MODIFY) {
        sb.append("MODIFY").append(" | ")
    }
    if (event and FileObserver.ATTRIB == FileObserver.ATTRIB) {
        sb.append("ATTRIB").append(" | ")
    }
    if (event and FileObserver.CLOSE_WRITE == FileObserver.CLOSE_WRITE) {
        sb.append("CLOSE_WRITE").append(" | ")
    }
    if (event and FileObserver.CLOSE_NOWRITE == FileObserver.CLOSE_NOWRITE) {
        sb.append("CLOSE_NOWRITE").append(" | ")
    }
    if (event and FileObserver.OPEN == FileObserver.OPEN) {
        sb.append("OPEN").append(" | ")
    }
    if (event and FileObserver.MOVED_FROM == FileObserver.MOVED_FROM) {
        sb.append("MOVED_FROM").append(" | ")
    }
    if (event and FileObserver.MOVED_TO == FileObserver.MOVED_TO) {
        sb.append("MOVED_TO").append(" | ")
    }
    if (event and FileObserver.CREATE == FileObserver.CREATE) {
        sb.append("CREATE").append(" | ")
    }
    if (event and FileObserver.DELETE == FileObserver.DELETE) {
        sb.append("DELETE").append(" | ")
    }
    if (event and FileObserver.DELETE_SELF == FileObserver.DELETE_SELF) {
        sb.append("DELETE_SELF").append(" | ")
    }
    if (event and FileObserver.MOVE_SELF == FileObserver.MOVE_SELF) {
        sb.append("MOVE_SELF").append(" | ")
    }

    if (event and 0x00008000 == 0x00008000) {
        sb.append("IN_IGNORED").append(" | ")
    }

    if (event and 0x40000000 == 0x40000000) {
        sb.append("IN_ISDIR").append(" | ")
    }

    return if (sb.isNotEmpty()) {
        sb.substring(0, sb.length - 3)
    } else {
        sb.toString()
    }
}
