#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pty.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define MAX_ARGUMENTS 64
#define MAX_ENVIRONMENT 32
#define MAX_STRING_BYTES 4096
#define MAX_IO_BYTES (32 * 1024)

static void throw_exception(JNIEnv *env, const char *class_name, const char *message) {
    jclass exception_class = (*env)->FindClass(env, class_name);
    if (exception_class != NULL) {
        (void)(*env)->ThrowNew(env, exception_class, message);
        (*env)->DeleteLocalRef(env, exception_class);
    }
}

static void free_vector(char **values, jsize count) {
    if (values == NULL) return;
    for (jsize index = 0; index < count; index++) free(values[index]);
    free(values);
}

static char **copy_string_array(JNIEnv *env, jobjectArray source, jsize maximum, jsize *count_out) {
    if (source == NULL || count_out == NULL) {
        throw_exception(env, "java/lang/IllegalArgumentException", "String array is required");
        return NULL;
    }
    const jsize count = (*env)->GetArrayLength(env, source);
    if (count <= 0 || count > maximum) {
        throw_exception(env, "java/lang/IllegalArgumentException", "String array length is invalid");
        return NULL;
    }
    char **result = calloc((size_t)count + 1U, sizeof(char *));
    if (result == NULL) {
        throw_exception(env, "java/lang/OutOfMemoryError", "Unable to allocate process arguments");
        return NULL;
    }
    for (jsize index = 0; index < count; index++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, source, index);
        if (item == NULL) {
            free_vector(result, count);
            throw_exception(env, "java/lang/IllegalArgumentException", "Null process argument");
            return NULL;
        }
        const jsize length = (*env)->GetStringUTFLength(env, item);
        if (length <= 0 || length > MAX_STRING_BYTES) {
            (*env)->DeleteLocalRef(env, item);
            free_vector(result, count);
            throw_exception(env, "java/lang/IllegalArgumentException", "Process argument length is invalid");
            return NULL;
        }
        const char *characters = (*env)->GetStringUTFChars(env, item, NULL);
        if (characters == NULL) {
            (*env)->DeleteLocalRef(env, item);
            free_vector(result, count);
            return NULL;
        }
        result[index] = malloc((size_t)length + 1U);
        if (result[index] != NULL) {
            memcpy(result[index], characters, (size_t)length);
            result[index][(size_t)length] = '\0';
        }
        (*env)->ReleaseStringUTFChars(env, item, characters);
        (*env)->DeleteLocalRef(env, item);
        if (result[index] == NULL) {
            free_vector(result, count);
            throw_exception(env, "java/lang/OutOfMemoryError", "Unable to copy process argument");
            return NULL;
        }
    }
    *count_out = count;
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_io_deepseekharness_mobile_runtime_NativePty_spawn(
        JNIEnv *env,
        jobject instance,
        jobjectArray argv_source,
        jobjectArray environment_source,
        jint columns,
        jint rows) {
    (void)instance;
    if (columns < 20 || columns > 300 || rows < 4 || rows > 150) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Terminal dimensions are invalid");
        return NULL;
    }

    jsize argument_count = 0;
    jsize environment_count = 0;
    char **argv = copy_string_array(env, argv_source, MAX_ARGUMENTS, &argument_count);
    if (argv == NULL) return NULL;
    char **environment = copy_string_array(env, environment_source, MAX_ENVIRONMENT, &environment_count);
    if (environment == NULL) {
        free_vector(argv, argument_count);
        return NULL;
    }

    struct winsize dimensions;
    memset(&dimensions, 0, sizeof(dimensions));
    dimensions.ws_col = (unsigned short)columns;
    dimensions.ws_row = (unsigned short)rows;

    int master_descriptor = -1;
    const pid_t child = forkpty(&master_descriptor, NULL, NULL, &dimensions);
    if (child == 0) {
        execve(argv[0], argv, environment);
        static const char failure[] = "\r\nUnable to start the verified runtime process.\r\n";
        (void)write(STDERR_FILENO, failure, sizeof(failure) - 1U);
        _exit(127);
    }

    free_vector(argv, argument_count);
    free_vector(environment, environment_count);
    if (child < 0 || master_descriptor < 0) {
        if (master_descriptor >= 0) (void)close(master_descriptor);
        throw_exception(env, "java/lang/IllegalStateException", "Unable to create terminal process");
        return NULL;
    }
    if (fcntl(master_descriptor, F_SETFD, FD_CLOEXEC) < 0) {
        if (kill(-child, SIGKILL) < 0 && errno == ESRCH) (void)kill(child, SIGKILL);
        (void)close(master_descriptor);
        (void)waitpid(child, NULL, 0);
        throw_exception(env, "java/lang/IllegalStateException", "Unable to secure terminal descriptor");
        return NULL;
    }

    jlong values[2] = {(jlong)child, (jlong)master_descriptor};
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result == NULL) {
        if (kill(-child, SIGKILL) < 0 && errno == ESRCH) (void)kill(child, SIGKILL);
        (void)close(master_descriptor);
        (void)waitpid(child, NULL, 0);
        return NULL;
    }
    (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_deepseekharness_mobile_runtime_NativePty_read(
        JNIEnv *env, jobject instance, jint descriptor, jbyteArray destination) {
    (void)instance;
    if (descriptor < 0 || destination == NULL) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Invalid terminal read arguments");
        return -1;
    }
    const jsize length = (*env)->GetArrayLength(env, destination);
    if (length <= 0 || length > MAX_IO_BYTES) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Terminal read size is invalid");
        return -1;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, destination, NULL);
    if (bytes == NULL) return -1;
    ssize_t result;
    do {
        result = read(descriptor, bytes, (size_t)length);
    } while (result < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, destination, bytes, result > 0 ? 0 : JNI_ABORT);
    if (result > INT_MAX) return -1;
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_io_deepseekharness_mobile_runtime_NativePty_write(
        JNIEnv *env, jobject instance, jint descriptor, jbyteArray source) {
    (void)instance;
    if (descriptor < 0 || source == NULL) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Invalid terminal write arguments");
        return -1;
    }
    const jsize length = (*env)->GetArrayLength(env, source);
    if (length <= 0 || length > MAX_IO_BYTES) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Terminal write size is invalid");
        return -1;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, source, NULL);
    if (bytes == NULL) return -1;
    ssize_t result;
    do {
        result = write(descriptor, bytes, (size_t)length);
    } while (result < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, source, bytes, JNI_ABORT);
    if (result > INT_MAX) return -1;
    return (jint)result;
}

JNIEXPORT void JNICALL
Java_io_deepseekharness_mobile_runtime_NativePty_resize(
        JNIEnv *env, jobject instance, jint descriptor, jint columns, jint rows) {
    (void)instance;
    if (descriptor < 0 || columns < 20 || columns > 300 || rows < 4 || rows > 150) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Terminal resize arguments are invalid");
        return;
    }
    struct winsize dimensions;
    memset(&dimensions, 0, sizeof(dimensions));
    dimensions.ws_col = (unsigned short)columns;
    dimensions.ws_row = (unsigned short)rows;
    if (ioctl(descriptor, TIOCSWINSZ, &dimensions) < 0) {
        throw_exception(env, "java/io/IOException", "Unable to resize terminal");
    }
}

JNIEXPORT void JNICALL
Java_io_deepseekharness_mobile_runtime_NativePty_signal(
        JNIEnv *env, jobject instance, jint process_id, jint signal_number) {
    (void)instance;
    if (process_id <= 1 || (signal_number != SIGTERM && signal_number != SIGKILL)) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Process signal arguments are invalid");
        return;
    }
    if (kill(-process_id, signal_number) < 0) {
        if (errno != ESRCH || (kill(process_id, signal_number) < 0 && errno != ESRCH)) {
            throw_exception(env, "java/lang/IllegalStateException", "Unable to signal terminal process");
        }
    }
}

JNIEXPORT jint JNICALL
Java_io_deepseekharness_mobile_runtime_NativePty_waitFor(
        JNIEnv *env, jobject instance, jint process_id) {
    (void)instance;
    if (process_id <= 1) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Process identifier is invalid");
        return 255;
    }
    int status = 0;
    pid_t result;
    do {
        result = waitpid(process_id, &status, 0);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return 255;
    if (WIFEXITED(status)) return (jint)WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return (jint)(128 + WTERMSIG(status));
    return 255;
}

JNIEXPORT void JNICALL
Java_io_deepseekharness_mobile_runtime_NativePty_close(
        JNIEnv *env, jobject instance, jint descriptor) {
    (void)instance;
    if (descriptor < 0) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Terminal descriptor is invalid");
        return;
    }
    (void)close(descriptor);
}
