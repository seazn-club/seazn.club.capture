/*
 * N49: binds our SRT socket to an Android network before its connect, so its packets, its shutdown included, leave over
 * that network. srtdroid 1.10.1 wraps no `srt_bind_acquire` and exposes no file descriptor, but its libsrt 1.5.7 exports
 * the C symbol (`nm -D libsrtdroid.so`), so this finds it in the library srtdroid has already loaded, makes the UDP
 * socket itself, binds it to the network with the NDK's `android_setsocknetwork` (API 23; the handle is
 * `Network.getNetworkHandle()`, the one NetworkWatch reports), and hands it to libsrt.
 *
 * The answer, read by `SrtBind` (srt/connect/SrtBind.kt, which core's tests run): 0 when bound, else the step that
 * failed in the high 16 bits and its errno, or libsrt's error code for the acquire, in the low 16.
 */
#include <android/multinetwork.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <netinet/in.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

enum { STEP_LIBRARY = 1, STEP_SYMBOL = 2, STEP_SOCKET = 3, STEP_NETWORK = 4, STEP_BIND = 5, STEP_ACQUIRE = 6 };

typedef int (*srt_bind_acquire_fn)(int srt_socket, int udp_socket);
typedef int (*srt_getlasterror_fn)(int *errno_loc);

static jint answer(int step, int detail) { return (jint)((step << 16) | (detail & 0xFFFF)); }

/* A wildcard address of the family asked, port 0: the kernel picks the port, the network decides the route. */
static socklen_t wildcard(struct sockaddr_storage *any, jboolean ipv6) {
  memset(any, 0, sizeof *any);
  if (ipv6) {
    struct sockaddr_in6 *six = (struct sockaddr_in6 *)any;
    six->sin6_family = AF_INET6;
    six->sin6_addr = in6addr_any;
    return sizeof *six;
  }
  struct sockaddr_in *four = (struct sockaddr_in *)any;
  four->sin_family = AF_INET;
  four->sin_addr.s_addr = htonl(INADDR_ANY);
  return sizeof *four;
}

JNIEXPORT jint JNICALL Java_com_seazn_capture_engine_srt_SrtNetworkBinder_nativeBind(JNIEnv *env, jclass type, jint srt_socket, jlong network, jboolean ipv6) {
  (void)env;
  (void)type;
  /* RTLD_NOLOAD: only the copy srtdroid loaded, never a second libsrt with its own sockets. Never dlclosed: srtdroid holds it for the process. */
  void *srt = dlopen("libsrtdroid.so", RTLD_NOW | RTLD_NOLOAD);
  if (srt == NULL) return answer(STEP_LIBRARY, 0);
  srt_bind_acquire_fn acquire = (srt_bind_acquire_fn)dlsym(srt, "srt_bind_acquire");
  srt_getlasterror_fn last_error = (srt_getlasterror_fn)dlsym(srt, "srt_getlasterror");
  if (acquire == NULL) return answer(STEP_SYMBOL, 0);

  int fd = socket(ipv6 ? AF_INET6 : AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
  if (fd < 0) return answer(STEP_SOCKET, errno);
  if (android_setsocknetwork((net_handle_t)network, fd) != 0) {
    int failed = errno;
    close(fd);
    return answer(STEP_NETWORK, failed);
  }
  struct sockaddr_storage any;
  socklen_t length = wildcard(&any, ipv6);
  if (bind(fd, (struct sockaddr *)&any, length) != 0) {
    int failed = errno;
    close(fd);
    return answer(STEP_BIND, failed);
  }
  /*
   * From here libsrt owns the descriptor: its multiplexer closes it with the SRT socket. On a failed acquire libsrt may
   * already have closed it, and another thread may hold the number again, so it is left open (SrtBind then stops binding
   * for the process: at most one is left).
   */
  if (acquire(srt_socket, fd) != 0) return answer(STEP_ACQUIRE, last_error == NULL ? 0 : last_error(NULL));
  return 0;
}
