#include <stdarg.h>
#include <stdio.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

void winport_retro_log(int level, const char *fmt, ...) {
    char line[2048];
    va_list args;
    va_start(args, fmt);
    vsnprintf(line, sizeof(line), fmt, args);
    va_end(args);
#ifdef __ANDROID__
    __android_log_write(level >= 3 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "WinportRetro", line);
#else
    (void)level;
    fputs(line, stderr);
#endif
}
