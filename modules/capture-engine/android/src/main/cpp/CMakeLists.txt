# N49: the JNI shim that binds our SRT socket to an Android network (srt_bind.c). C only: no STL, no other library of
# ours. libsrt's `srt_bind_acquire` is found at run time in srtdroid's own libsrtdroid.so, never linked.
cmake_minimum_required(VERSION 3.22.1)
project(seazn_srt_bind C)

add_library(seazn_srt_bind SHARED srt_bind.c)
target_compile_options(seazn_srt_bind PRIVATE -Wall -Wextra -Werror)
# android: android_setsocknetwork (API 23). dl: dlopen/dlsym.
target_link_libraries(seazn_srt_bind PRIVATE android dl)
