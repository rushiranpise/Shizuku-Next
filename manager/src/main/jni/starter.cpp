#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <ctime>
#include <cstring>
#include <libgen.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/wait.h>
#include <cerrno>
#include <cstdarg>
#include <string>
#include <termios.h>
#include "android.h"
#include "misc.h"
#include "selinux.h"
#include "cgroup.h"

// Set before logging.h so the tag is this one rather than the default.
#define LOG_TAG "ShizukuServiceStarter"
#include "logging.h"

#ifdef DEBUG
#define JAVA_DEBUGGABLE
#endif

/*
 * Every message goes to logcat as well as stderr. The device exploits that start this
 * binary run it from another app, whose stdout is not ours to read, so without this the
 * whole start is silent: the manager waits a minute for a binder, reports a timeout, and
 * nothing says whether this process even ran.
 */
static FILE *s_manager_log = nullptr;

/** Writes one already formatted message to the manager's copy of this log, if it has one. */
static void log_to_manager(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

static void log_to_manager(const char *fmt, ...) {
    if (s_manager_log == nullptr) return;

    va_list args;
    va_start(args, fmt);
    vfprintf(s_manager_log, fmt, args);
    va_end(args);
    fflush(s_manager_log);
}

// Every message goes to stdout, stderr and logcat as before, and a copy is written where
// the manager can read it. It is a copy, not a redirection: the adb and root starts show
// their progress through this process's stdout, and that has to keep working.
#define perrorf(...) do { fprintf(stderr, __VA_ARGS__); LOGE(__VA_ARGS__); log_to_manager(__VA_ARGS__); } while (0)
#define info(...) do { printf(__VA_ARGS__); LOGI(__VA_ARGS__); fflush(stdout); log_to_manager(__VA_ARGS__); } while (0)

#define EXIT_FATAL_SET_CLASSPATH 3
#define EXIT_FATAL_FORK 4
#define EXIT_FATAL_APP_PROCESS 5
#define EXIT_FATAL_UID 6
#define EXIT_FATAL_PM_PATH 7
#define EXIT_FATAL_KILL 9
#define EXIT_FATAL_BINDER_BLOCKED_BY_SELINUX 10

#define SERVER_NAME "shizuku_server"

/** How many times to exec the server before giving up, and how far apart. */
#define SERVER_ATTEMPTS 16
#define SERVER_ATTEMPT_INTERVAL_US 16000

/** How long the parent watches the forked server before it reports success, and how often. */
#define SERVER_WATCH_ATTEMPTS 15
#define SERVER_WATCH_INTERVAL_US 200000

/** How large the manager's copy of the starter's log may grow before it is started over. */
#define MANAGER_LOG_MAX_SIZE (256 * 1024)
#define SERVER_CLASS_PATH "rikka.shizuku.server.ShizukuService"

#if defined(__arm__)
#define ABI "arm"
#elif defined(__i386__)
#define ABI "x86"
#elif defined(__x86_64__)
#define ABI "x86_64"
#elif defined(__aarch64__)
#define ABI "arm64"
#endif

static void run_server(const char *dex_path, const char *main_class, const char *process_name) {
    if (setenv("CLASSPATH", dex_path, true)) {
        LOGE("can't set CLASSPATH\n");
        exit(EXIT_FATAL_SET_CLASSPATH);
    }

#define ARG(v) char **v = nullptr; \
    char buf_##v[PATH_MAX]; \
    size_t v_size = 0; \
    uintptr_t v_current = 0;
#define ARG_PUSH(v, arg) v_size += sizeof(char *); \
if (v == nullptr) { \
    v = (char **) malloc(v_size); \
} else { \
    v = (char **) realloc(v, v_size);\
} \
v_current = (uintptr_t) v + v_size - sizeof(char *); \
*((char **) v_current) = arg ? strdup(arg) : nullptr;

#define ARG_END(v) ARG_PUSH(v, nullptr)

#define ARG_PUSH_FMT(v, fmt, ...) snprintf(buf_##v, PATH_MAX, fmt, __VA_ARGS__); \
    ARG_PUSH(v, buf_##v)

/*
 * The hidden APIs, in every build rather than only a debugging one.
 *
 * Android refuses the platform's hidden APIs to a process that is not marked debuggable, and
 * calling those APIs is the whole of what this server is for: without the marking, a release
 * build's server starts and then cannot grant a permission, read a package or reach the
 * activity manager, which looks like a broken release and works in a debug build because a
 * debug build happens to pass this already. It is a runtime argument, so it costs the APK
 * nothing.
 *
 * The compiler filter is set back to speed with it, because a debuggable process otherwise
 * runs its own code through the interpreter instead of the version compiled at install time.
 */
#define ARG_PUSH_DEBUGGABLE_PARAMS(v) \
    ARG_PUSH(v, "-Xcompiler-option"); \
    ARG_PUSH(v, "--debuggable"); \
    ARG_PUSH(v, "-Xcompiler-option"); \
    ARG_PUSH(v, "--compiler-filter=speed")

/*
 * A debugger port is another matter, and stays in debug builds: it is a listening socket and
 * a JDWP client on the other end of it, which a release has no business offering.
 */
#ifdef JAVA_DEBUGGABLE
#define ARG_PUSH_DEBUG_ONLY(v, arg) ARG_PUSH(v, arg)
#define ARG_PUSH_JDWP_PARAMS(v) \
    if (android_get_device_api_level() >= 30) { \
        ARG_PUSH(v, "-XjdwpProvider:adbconnection"); \
        ARG_PUSH(v, "-XjdwpOptions:suspend=n,server=y"); \
    } else if (android_get_device_api_level() >= 28) { \
        ARG_PUSH(v, "-XjdwpProvider:internal"); \
        ARG_PUSH(v, "-XjdwpOptions:transport=dt_android_adb,suspend=n,server=y"); \
    } else { \
        ARG_PUSH(v, "-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y"); \
    }
#else
#define ARG_PUSH_JDWP_PARAMS(v)
#define ARG_PUSH_DEBUG_ONLY(v, arg)
#endif

    char lib_path[PATH_MAX]{0};
    snprintf(lib_path, PATH_MAX, "%s/lib/%s", dirname(dex_path), ABI);

    ARG(argv)
    ARG_PUSH(argv, "/system/bin/app_process")
    ARG_PUSH_FMT(argv, "-Djava.class.path=%s", dex_path)
    ARG_PUSH_FMT(argv, "-Dshizuku.library.path=%s", lib_path)
    ARG_PUSH_DEBUGGABLE_PARAMS(argv)
    ARG_PUSH_JDWP_PARAMS(argv)
    ARG_PUSH(argv, "/system/bin")
    ARG_PUSH_FMT(argv, "--nice-name=%s", process_name)
    ARG_PUSH(argv, main_class)
    ARG_PUSH_DEBUG_ONLY(argv, "--debug")
    ARG_END(argv)

    LOGD("exec app_process");

    if (execvp((const char *) argv[0], argv)) {
        // Returns instead of exiting, so the caller can try again: the first exec in the
        // context a device exploit gives this process can fail where the next succeeds, and
        // both forks that are known to start reliably from an exploit (pascua28, wr3cckl3ss)
        // retry here while every other fork exits silently and leaves nothing to go on.
        perrorf("warn: can't exec %s (%d: %s)\n", (const char *) argv[0], errno,
                strerror(errno));
    }
}

static void start_server(const char *path, const char *main_class, const char *process_name) {
    int fds[2];
    if (pipe(fds) < 0) {
        perrorf("fatal: can't create pipe\n");
        exit(EXIT_FATAL_FORK);
    }

    pid_t pid = fork();
    switch (pid) {
        case -1: {
            perrorf("fatal: can't fork\n");
            exit(EXIT_FATAL_FORK);
        }
        case 0: {
            LOGD("child");
            close(fds[0]);
            setsid();
            chdir("/");
            int fd = open("/dev/null", O_RDWR);
            if (fd != -1) {
                dup2(fd, STDIN_FILENO);
                dup2(fd, STDOUT_FILENO);
                dup2(fd, STDERR_FILENO);
                if (fd > 2) close(fd);
            }
            
            char ready = 1;
            write(fds[1], &ready, 1);
            close(fds[1]);

            // Run to completion on success, because the server replaces this process image;
            // on failure try again, and if nothing works say so rather than disappearing.
            for (int attempt = 0; attempt < SERVER_ATTEMPTS; attempt++) {
                run_server(path, main_class, process_name);
                usleep(SERVER_ATTEMPT_INTERVAL_US);
            }
            perrorf("fatal: can't start the server after %d attempts\n", SERVER_ATTEMPTS);
            exit(EXIT_FATAL_APP_PROCESS);
        }
        default: {
            close(fds[1]);
            char ready;
            read(fds[0], &ready, 1);
            close(fds[0]);

            info("info: shizuku_server pid is %d\n", pid);

            // Watch the child for a few seconds instead of assuming it survived. The manager
            // waits a minute for a binder, and "the server was forked" is not "the server is
            // running": one that dies the moment this process leaves and one that runs
            // silently without ever handing the binder over look identical from there, and
            // they are two completely different bugs. An exit code says which of our own
            // fatal paths it took, and a signal says something outside this process killed
            // it, which is what the payload stopping the app it borrowed would look like.
            int status = 0;
            pid_t ended = 0;
            int watched = 0;
            for (; watched < SERVER_WATCH_ATTEMPTS; watched++) {
                ended = waitpid(pid, &status, WNOHANG);
                if (ended == pid) break;
                usleep(SERVER_WATCH_INTERVAL_US);
            }

            int waited_ms = watched * (SERVER_WATCH_INTERVAL_US / 1000);
            if (ended == pid) {
                if (WIFSIGNALED(status)) {
                    perrorf("warn: the server was killed by signal %d, %d ms after it started\n",
                            WTERMSIG(status), waited_ms);
                } else {
                    perrorf("warn: the server exited with %d, %d ms after it started\n",
                            WEXITSTATUS(status), waited_ms);
                }
            } else {
                info("info: the server is still running %d ms after it was started\n",
                     waited_ms);
            }

            info("info: shizuku_starter exit with 0\n");
            exit(EXIT_SUCCESS);
        }
    }
}

static int check_selinux(const char *s, const char *t, const char *c, const char *p) {
    int res = se::selinux_check_access(s, t, c, p, nullptr);
#ifndef DEBUG
    if (res != 0) {
#endif
    printf("info: selinux_check_access %s %s %s %s: %d\n", s, t, c, p, res);
    fflush(stdout);
#ifndef DEBUG
    }
#endif
    return res;
}

/**
 * The cgroup this process is in, one line per hierarchy.
 *
 * A server left in the cgroup of the app it was launched from can be taken down with that
 * app, and the device exploits stop the app they borrow once the payload has run, so where
 * this process lands decides whether there is a server at the end of it. Printed either
 * side of the switch, because "can't switch cgroup" on its own does not say where it
 * stayed, and this is the only output a system start can be judged by afterwards.
 */
static void log_cgroup(const char *when) {
    FILE *file = fopen("/proc/self/cgroup", "r");
    if (file == nullptr) {
        info("info: cgroup %s: can't read /proc/self/cgroup (%d: %s)\n", when, errno,
             strerror(errno));
        return;
    }

    char line[256];
    while (fgets(line, sizeof(line), file) != nullptr) {
        size_t length = strlen(line);
        if (length > 0 && line[length - 1] == '\n') line[length - 1] = '\0';
        info("info: cgroup %s: %s\n", when, line);
    }
    fclose(file);
}

/**
 * Moves this process out of the cgroup of the app it was started from, in every hierarchy.
 *
 * Every one of them, not the first that accepts the write. They are separate hierarchies with
 * separate accounting, and the one that refuses is not the one that matters: a system start
 * cannot write /acct on every device but could write it here, so it stopped there, while the
 * unified hierarchy still had this process in uid_1000/pid_<the agent's pid>. That is the
 * group the framework tears down when a package is stopped, and the payload's last step stops
 * the agent it borrowed, which is a server that hands over the binder and is then killed.
 *
 * The uid_0 groups are the ones that look like a root process, which is what a system server
 * has to resemble to outlive the app it came from.
 */
/** One hierarchy, reported either way: silence about a refusal is what hid this before. */
static int move_into_cgroup(const char *path, int pid) {
    if (!cgroup::switch_cgroup(path, pid)) {
        info("info: switch cgroup, could not move this process into %s\n", path);
        return 0;
    }

    info("info: switch cgroup, moved this process into %s\n", path);
    return 1;
}

static int switch_cgroup(bool system_uid) {
    int pid = getpid();
    int moved = 0;

    moved += move_into_cgroup("/acct", pid);
    moved += move_into_cgroup("/sys/fs/cgroup", pid);
    moved += move_into_cgroup("/dev/cg2_bpf", pid);

    // Only for the system uid. These are the groups a root process sits in, which is what a
    // system server has to resemble to outlive the app it came from; moving an adb or root
    // server into them would change accounting it has no reason to change.
    if (system_uid) {
        moved += move_into_cgroup("/acct/uid_0", pid);
        moved += move_into_cgroup("/sys/fs/cgroup/uid_0", pid);
    }

    char buf[PROP_VALUE_MAX + 1];
    if (__system_property_get("ro.config.per_app_memcg", buf) > 0 &&
        strncmp(buf, "false", 5) != 0) {
        moved += move_into_cgroup("/dev/memcg/apps", pid);
        if (system_uid) {
            moved += move_into_cgroup("/dev/memcg/apps/uid_0", pid);
        }
    }

    if (moved == 0) {
        perrorf("warn: can't switch cgroup: every path refused\n");
        return -1;
    }

    info("info: switch cgroup, moved this process in %d hierarchies\n", moved);
    return 0;
}

static int s_killed_count = 0;

/**
 * The manager's package name, from a path inside its own directories: an APK
 * (/data/app/<id>/base.apk) or the executable this binary is
 * (/data/app/<id>/lib/arm64/libshizuku.so). The directory is
 * <package>-<hash>, and a package name cannot contain a dash, so the first one ends it.
 */
static std::string package_from_path(const char *path) {
    std::string value(path);
    size_t app = value.find("/data/app/");
    if (app == std::string::npos) return "";

    size_t start = app + strlen("/data/app/");

    // Android 11 and later give each install a directory of its own, so the application
    // directory is one level further down than it used to be:
    //   /data/app/~~<token>/<package>-<hash>/...
    // Reading only the first level produced a "package" of "~~<token>", which described a
    // directory that does not exist and cannot be created. The log then fell through to the
    // /data/local/tmp fallback, which the system uid cannot write and the app cannot read,
    // so a system start left nothing behind at all.
    if (value.compare(start, 2, "~~") == 0) {
        size_t level = value.find('/', start);
        if (level == std::string::npos) return "";
        start = level + 1;
    }

    size_t end = value.find('/', start);
    std::string dir = value.substr(start, end == std::string::npos ? std::string::npos : end - start);

    size_t dash = dir.find('-');
    return dash == std::string::npos ? dir : dir.substr(0, dash);
}

/**
 * Sends this process's output to a file the manager can read afterwards.
 *
 * The device exploits run this binary from another app and keep its output, so a start that
 * fails leaves nothing behind on the manager's side to explain it. The manager's own
 * external files directory is a place both this process (as root or the system uid, and as
 * the adb shell) and the app can write and read, and it is where a logcat reader would not
 * be needed to find out what happened. Best effort: with no writable place, logcat is still
 * told everything.
 */
/** Opens one candidate log for appending, starting it over when it has grown too large. */
static bool open_log(const char *path, bool append) {
    struct stat st;
    if (stat(path, &st) == 0 && st.st_size > MANAGER_LOG_MAX_SIZE) {
        append = false;
    }

    s_manager_log = fopen(path, append ? "a" : "w");
    return s_manager_log != nullptr;
}

static void open_manager_log(const char *manager_path) {
    std::string package = package_from_path(manager_path);
    if (package.empty()) return;

    char dir[PATH_MAX];
    snprintf(dir, sizeof(dir), "/storage/emulated/0/Android/data/%s", package.c_str());
    // Both levels, because the app only creates this directory on first use and a missing
    // one is exactly how a start ends up with nowhere to leave a log.
    mkdir(dir, 0775);
    strncat(dir, "/files", sizeof(dir) - strlen(dir) - 1);
    mkdir(dir, 0775);

    char candidates[2][PATH_MAX];
    snprintf(candidates[0], sizeof(candidates[0]), "%s/starter.log", dir);

    // The app's media directory as well, because the two fail for different reasons: the
    // files directory is the natural place to put this, but another app's Android/data is
    // exactly what the storage layer and SELinux are built to deny, and a system start
    // arrives here from another app's process. Android/media/<package> exists to be
    // reachable from outside the app, so it is the one that survives that.
    char media_dir[PATH_MAX];
    snprintf(media_dir, sizeof(media_dir), "/storage/emulated/0/Android/media/%s",
             package.c_str());
    mkdir(media_dir, 0775);
    snprintf(candidates[1], sizeof(candidates[1]), "%s/starter.log", media_dir);

    // Appended, not rewritten. A system start asks the agent to run the payload several
    // times, so truncating on every run meant the account that survived was always the last
    // one, and the first attempt which is the one that says what actually went wrong was
    // thrown away by the second. Started over when it grows past a sane size instead.
    const char *chosen = nullptr;
    for (int i = 0; i < 2 && chosen == nullptr; i++) {
        chosen = open_log(candidates[i], true) ? candidates[i] : nullptr;
    }
    if (s_manager_log == nullptr) {
        perrorf("warn: no writable place for the starter's log in %s\n", dir);
    } else {
        // Which of them it was, in the log itself: a start whose account lands somewhere the
        // manager cannot read looks the same from the outside as one that never ran.
        info("info: ---- start ----\n");
        info("info: starter log is %s\n", chosen);
    }
}

int main(int argc, char *argv[]) {
    std::string apk_path;
    for (int i = 0; i < argc; ++i) {
        if (strncmp(argv[i], "--apk=", 6) == 0) {
            apk_path = argv[i] + 6;
        }
    }

    // Before anything can fail, so that the uid guard below and the apk path above are both
    // in the file: this is the only output the manager can read back.
    {
        char self[PATH_MAX];
        ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
        if (length > 0) {
            self[length] = '\0';
            open_manager_log(self);
        }
    }

    uid_t uid = getuid();
    // 1000 as well: the system uid is what the device exploits that start this server
    // without root or adb give it, and refusing it here is what made "Start (system)"
    // report success while the server quietly exited with EXIT_FATAL_UID.
    if (uid != 0 && uid != 1000 && uid != 2000) {
        perrorf("fatal: run Shizuku from non root/system/adb user (uid=%d).\n", uid);
        exit(EXIT_FATAL_UID);
    }

    se::init();

    // The system uid gets what root gets here: a process left in an app cgroup can be
    // killed as one, and a system process that cannot see the init mount namespace cannot
    // reach what it was started to reach.
    if (uid == 0 || uid == 1000) {
        log_cgroup("before switch");
        switch_cgroup(uid == 1000);
        log_cgroup("after switch");

        if (android_get_device_api_level() >= 29) {
            printf("info: switching mount namespace to init...\n");
            switch_mnt_ns(1);
        }
    }

    if (uid == 0) {
        char *context = nullptr;
        if (se::getcon(&context) == 0) {
            int res = 0;

            res |= check_selinux("u:r:untrusted_app:s0", context, "binder", "call");
            res |= check_selinux("u:r:untrusted_app:s0", context, "binder", "transfer");

            if (res != 0) {
                perrorf("fatal: the su you are using does not allow app (u:r:untrusted_app:s0) to connect to su (%s) with binder.\n",
                        context);
                exit(EXIT_FATAL_BINDER_BLOCKED_BY_SELINUX);
            }
            se::freecon(context);
        }
    }

    info("info: starter begin\n");

    // kill old server(s)
    printf("info: checking for existing server processes...\n");
    fflush(stdout);

    s_killed_count = 0;
    foreach_proc([](pid_t pid) {
        if (pid == getpid()) return;

        if (!is_shizuku_server(pid, SERVER_NAME))
            return;

        printf("info: found active server process %d\n", pid);
        if (kill(pid, SIGKILL) == 0) {
            printf("info: killed process %d\n", pid);
            s_killed_count++;
        } else if (errno == EPERM) {
            // A server this uid cannot kill is usually one another start made, root's
            // included. Refusing to start because of it leaves the user with nothing at a
            // point where nothing has been tried yet: the old one may be on its way out,
            // and if it really is in the way the bind fails and says so. Note that this
            // is reachable only on a device where the previous server is still running:
            // "Start (system)" over a root server fails here otherwise, silently.
            perrorf("warn: can't kill %d (owned by another uid), starting anyway\n", pid);
        } else {
            printf("warn: failed to kill %d\n", pid);
        }
    });

    if (s_killed_count == 0) {
        printf("info: no existing server process running\n");
    } else {
        printf("info: cleanly terminated %d existing process(es)\n", s_killed_count);
        usleep(100000); // give the OS time to reclaim sockets & binder
    }
    fflush(stdout);

    if (access(apk_path.c_str(), R_OK) == 0) {
        printf("info: use apk path from argv\n");
        fflush(stdout);
    }

    if (apk_path.empty()) {
        // Use /proc/self/exe to get the executable path
        char buf[PATH_MAX];
        ssize_t len = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
        if (len == -1) {
            perror("readlink");
            return 1;
        }
        buf[len] = '\0';
        std::string exe_path(buf);

        // Find "/lib/" and replace from there with "/base.apk"
        size_t lib_pos = exe_path.find("/lib/");
        if (lib_pos != std::string::npos) {
            apk_path = exe_path.substr(0, lib_pos) + "/base.apk";
        }
    }

    if (apk_path.empty()) {
        perrorf("fatal: can't get path of manager\n");
        exit(EXIT_FATAL_PM_PATH);
    }

    info("info: apk path is %s\n", apk_path.c_str());
    if (access(apk_path.c_str(), R_OK) != 0) {
        perrorf("fatal: can't access manager %s\n", apk_path.c_str());
        exit(EXIT_FATAL_PM_PATH);
    }

    info("info: starting server...\n");
    LOGD("start_server");
    start_server(apk_path.c_str(), SERVER_CLASS_PATH, SERVER_NAME);
}
