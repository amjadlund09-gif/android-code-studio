#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <vector>
#include <string>

// Real pseudo-terminal: posix_openpt + fork/exec. The Kotlin side wraps the master fd
// in a ParcelFileDescriptor and reads/writes it like a normal stream.

static std::vector<std::string> toVec(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; i++) {
        auto s = (jstring) env->GetObjectArrayElement(arr, i);
        const char* c = env->GetStringUTFChars(s, nullptr);
        out.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    return out;
}

// returns [masterFd, pid] or null on failure
extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_mynative_androidide_PtyNative_nativeStart(
        JNIEnv* env, jobject /*thiz*/, jstring jcmd, jobjectArray jargs, jobjectArray jenv,
        jstring jcwd, jint rows, jint cols) {

    const char* cmdC = env->GetStringUTFChars(jcmd, nullptr);
    const char* cwdC = env->GetStringUTFChars(jcwd, nullptr);
    std::string cmd(cmdC), cwd(cwdC);
    env->ReleaseStringUTFChars(jcmd, cmdC);
    env->ReleaseStringUTFChars(jcwd, cwdC);
    std::vector<std::string> args = toVec(env, jargs);
    std::vector<std::string> envs = toVec(env, jenv);

    // build argv/envp BEFORE fork (no allocation in the child)
    std::vector<char*> argv, envp;
    for (auto& a : args) argv.push_back(const_cast<char*>(a.c_str()));
    argv.push_back(nullptr);
    for (auto& e : envs) envp.push_back(const_cast<char*>(e.c_str()));
    envp.push_back(nullptr);

    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) return nullptr;
    if (grantpt(master) != 0 || unlockpt(master) != 0) { close(master); return nullptr; }
    char slaveName[128];
    if (ptsname_r(master, slaveName, sizeof(slaveName)) != 0) { close(master); return nullptr; }

    // sane defaults: UTF-8, echo, canonical, signals
    struct termios tio;
    if (tcgetattr(master, &tio) == 0) {
        tio.c_iflag |= IUTF8 | ICRNL;
        tio.c_oflag |= OPOST | ONLCR;
        tio.c_lflag |= ISIG | ICANON | ECHO | ECHOE | ECHOK;
        tcsetattr(master, TCSANOW, &tio);
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) { close(master); return nullptr; }
    if (pid == 0) {
        // child
        setsid();
        int slave = open(slaveName, O_RDWR);
        if (slave < 0) _exit(126);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0); dup2(slave, 1); dup2(slave, 2);
        if (slave > 2) close(slave);
        if (chdir(cwd.c_str()) != 0) { /* keep going in current dir */ }
        // reset signal handlers inherited from the VM
        signal(SIGINT, SIG_DFL); signal(SIGQUIT, SIG_DFL); signal(SIGHUP, SIG_DFL);
        signal(SIGPIPE, SIG_DFL); signal(SIGTERM, SIG_DFL);
        execve(cmd.c_str(), argv.data(), envp.data());
        _exit(127);
    }

    jintArray result = env->NewIntArray(2);
    jint tmp[2] = { master, (jint) pid };
    env->SetIntArrayRegion(result, 0, 2, tmp);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mynative_androidide_PtyNative_nativeResize(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

// blocks until the child exits; returns exit code (or 128+signal)
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mynative_androidide_PtyNative_nativeWaitFor(
        JNIEnv* /*env*/, jobject /*thiz*/, jint pid) {
    int status = 0;
    while (true) {
        pid_t r = waitpid((pid_t) pid, &status, 0);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        break;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mynative_androidide_PtyNative_nativeKill(
        JNIEnv* /*env*/, jobject /*thiz*/, jint pid, jint sig) {
    if (pid > 0) {
        kill(-(pid_t) pid, sig);   // whole process group (session leader == pgid)
        kill((pid_t) pid, sig);
    }
}
