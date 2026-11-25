#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/fs.h>
#include <stdbool.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

enum publication_result {
    DELIVERED = 0,
    STAGED = 1,
    NO_SPACE = 2,
    COLLISION = 3,
    UNAVAILABLE = 4,
};

static const int directory_flags = O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;

static bool read_string(JNIEnv *env, jbyteArray bytes, char *text, size_t capacity) {
    const jsize length = (*env)->GetArrayLength(env, bytes);
    if (length <= 0 || (size_t) length >= capacity) return false;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *) text);
    if ((*env)->ExceptionCheck(env) || memchr(text, '\0', (size_t) length) != NULL) return false;
    text[length] = '\0';
    return true;
}

static bool is_component(const char *name) {
    return name[0] != '\0' && strcmp(name, ".") != 0 && strcmp(name, "..") != 0 &&
           strchr(name, '/') == NULL;
}

static int open_target_directory(const char *anchor, char *relative) {
    int directory = open(anchor, directory_flags);
    char *component = relative;
    while (directory >= 0) {
        char *separator = strchr(component, '/');
        if (separator != NULL) *separator = '\0';
        int child = -1;
        if (is_component(component)) child = openat(directory, component, directory_flags);
        else errno = EINVAL;
        const int error = errno;
        close(directory);
        if (child < 0) {
            errno = error;
            return -1;
        }
        directory = child;
        if (separator == NULL) break;
        component = separator + 1;
    }
    return directory;
}

static jint publish_file(int source, const char *source_name, int target, const char *target_name) {
    struct stat info;
    if (fstatat(source, source_name, &info, AT_SYMLINK_NOFOLLOW) != 0 ||
        !S_ISREG(info.st_mode) || info.st_nlink != 1) {
        return UNAVAILABLE;
    }

    // Android 8 的应用过滤器已放行此系统调用，libc 到 API 30 才提供封装。
    if (syscall(SYS_renameat2, source, source_name, target, target_name, RENAME_NOREPLACE) == 0) {
        return DELIVERED;
    }
    if (errno == EEXIST) return COLLISION;
    if (errno == ENOSPC || errno == EDQUOT) return NO_SPACE;
    return UNAVAILABLE;
}

JNIEXPORT jint JNICALL
Java_io_deepseekharness_mobile_runtime_RuntimeSharedFilePublisher_publishNative(
        JNIEnv *env,
        jobject instance,
        jbyteArray source_directory_bytes,
        jbyteArray source_name_bytes,
        jbyteArray runtime_anchor_bytes,
        jbyteArray target_directories_bytes,
        jbyteArray target_name_bytes) {
    (void) instance;
    char source_directory[PATH_MAX];
    char runtime_anchor[PATH_MAX];
    char target_directories[PATH_MAX];
    char source_name[NAME_MAX + 1];
    char target_name[NAME_MAX + 1];
    if (!read_string(env, source_directory_bytes, source_directory, sizeof(source_directory)) ||
        !read_string(env, runtime_anchor_bytes, runtime_anchor, sizeof(runtime_anchor)) ||
        !read_string(env, target_directories_bytes, target_directories, sizeof(target_directories)) ||
        !read_string(env, source_name_bytes, source_name, sizeof(source_name)) ||
        !read_string(env, target_name_bytes, target_name, sizeof(target_name)) ||
        !is_component(source_name) || !is_component(target_name)) {
        return UNAVAILABLE;
    }

    const int target = open_target_directory(runtime_anchor, target_directories);
    if (target < 0) return errno == ENOENT ? STAGED : UNAVAILABLE;

    const int source = open(source_directory, directory_flags);
    jint result = UNAVAILABLE;
    if (source >= 0) {
        result = publish_file(source, source_name, target, target_name);
        close(source);
    }
    close(target);
    return result;
}
