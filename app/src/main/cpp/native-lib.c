#include <string.h>

#include <jni.h>
#include <getopt.h>
#include <signal.h>
#include <setjmp.h>
#include <stdlib.h>
#include <pthread.h>
#include <time.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#include "byedpi/error.h"
#include "byedpi/conev.h"
#include "main.h"

extern int server_fd;
extern int g_dns_redirect_port;
extern struct poolhd *g_active_pool;

static int g_proxy_running = 0;
static pthread_mutex_t g_proxy_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_proxy_cond = PTHREAD_COND_INITIALIZER;

struct params default_params = {
        .await_int = 10,
        .ipv6 = 1,
        .resolve = 1,
        .udp = 1,
        .max_open = 512,
        .bfsize = 16384,
        .baddr = {
            .in6 = { .sin6_family = AF_INET6 }
        },
        .laddr = {
            .in = { .sin_family = AF_INET }
        },
        .debug = 0
};

void reset_params(void) {
    clear_params(NULL, NULL);
    params = default_params;
}

JNIEXPORT jint JNICALL
Java_io_github_romanvht_byedpi_core_ByeDpiProxy_jniStartProxy(JNIEnv *env, __attribute__((unused)) jobject thiz, jobjectArray args) {
    pthread_mutex_lock(&g_proxy_lock);
    if (g_proxy_running) {
        LOG(LOG_S, "proxy already running");
        pthread_mutex_unlock(&g_proxy_lock);
        return -1;
    }

    g_proxy_running = 1;
    reset_params();
    optind = 1;
    pthread_mutex_unlock(&g_proxy_lock);

    int in_argc = (*env)->GetArrayLength(env, args);
    int need_prog_name = 1;
    if (in_argc > 0) {
        jstring first_arg = (jstring) (*env)->GetObjectArrayElement(env, args, 0);
        if (first_arg) {
            const char *first_str = (*env)->GetStringUTFChars(env, first_arg, 0);
            if (first_str && first_str[0] != '-') {
                need_prog_name = 0;
            }
            if (first_str) (*env)->ReleaseStringUTFChars(env, first_arg, first_str);
            (*env)->DeleteLocalRef(env, first_arg);
        }
    }

    int argc = in_argc + (need_prog_name ? 1 : 0);
    char **argv = calloc(argc, sizeof(char *));

    if (!argv) {
        LOG(LOG_S, "failed to allocate memory for argv");
        pthread_mutex_lock(&g_proxy_lock);
        g_proxy_running = 0;
        pthread_cond_broadcast(&g_proxy_cond);
        pthread_mutex_unlock(&g_proxy_lock);
        return -1;
    }

    int offset = 0;
    if (need_prog_name) {
        argv[0] = strdup("ciadpi");
        offset = 1;
    }

    for (int i = 0; i < in_argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);

        if (!arg) {
            argv[i + offset] = NULL;
            continue;
        }

        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        argv[i + offset] = arg_str ? strdup(arg_str) : NULL;

        if (arg_str) (*env)->ReleaseStringUTFChars(env, arg, arg_str);

        (*env)->DeleteLocalRef(env, arg);
    }
    
    LOG(LOG_S, "starting proxy with %d args", argc);

    int result = main(argc, argv);

    LOG(LOG_S, "proxy return code %d", result);

    pthread_mutex_lock(&g_proxy_lock);
    g_proxy_running = 0;
    pthread_cond_broadcast(&g_proxy_cond);
    pthread_mutex_unlock(&g_proxy_lock);

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);

    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_romanvht_byedpi_core_ByeDpiProxy_jniStopProxy(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    LOG(LOG_S, "send shutdown to proxy");

    pthread_mutex_lock(&g_proxy_lock);
    if (!g_proxy_running) {
        LOG(LOG_S, "proxy is not running");
        pthread_mutex_unlock(&g_proxy_lock);
        return 0;
    }

    if (g_active_pool) {
        g_active_pool->brk = 1;
    }

    if (server_fd > 0) {
        shutdown(server_fd, SHUT_RDWR);
    }

    int port = ntohs(params.laddr.in.sin_port);
    if (port > 0) {
        int wake_sock = socket(AF_INET, SOCK_STREAM, 0);
        if (wake_sock >= 0) {
            struct sockaddr_in addr;
            memset(&addr, 0, sizeof(addr));
            addr.sin_family = AF_INET;
            addr.sin_port = htons(port);
            addr.sin_addr.s_addr = inet_addr("127.0.0.1");
            connect(wake_sock, (struct sockaddr *)&addr, sizeof(addr));
            close(wake_sock);
        }
    }

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += 1;

    while (g_proxy_running) {
        if (pthread_cond_timedwait(&g_proxy_cond, &g_proxy_lock, &ts) != 0) {
            LOG(LOG_S, "timeout waiting for proxy to stop");
            break;
        }
    }

    pthread_mutex_unlock(&g_proxy_lock);
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_romanvht_byedpi_core_ByeDpiProxy_jniForceClose(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    LOG(LOG_S, "closing server socket (fd: %d)", server_fd);

    pthread_mutex_lock(&g_proxy_lock);
    if (g_active_pool) {
        g_active_pool->brk = 1;
    }
    if (server_fd > 0) {
        close(server_fd);
        server_fd = -1;
    }
    g_proxy_running = 0;
    pthread_cond_broadcast(&g_proxy_cond);
    pthread_mutex_unlock(&g_proxy_lock);

    return 0;
}

JNIEXPORT void JNICALL
Java_io_github_romanvht_byedpi_core_ByeDpiProxy_setDnsRedirectPort(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz, jint port) {
    g_dns_redirect_port = port;
    LOG(LOG_S, "DNS redirect port set to %d", port);
}
