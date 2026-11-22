#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <android/log.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>

typedef struct {
    EGLDisplay display;
    EGLContext context;
    EGLSurface surface;
    GLuint framebuffer, color, depth;
    unsigned width, height;
    bool want_depth, want_stencil;
    unsigned frames;
} Graphics;
static Graphics *active;
static _Thread_local char error[256];
extern void wp_gl_viewport(unsigned, unsigned, float, int *);

const char *wp_gl_error(void) { return error; }
static bool fail(const char *operation) {
    snprintf(error, sizeof(error), "%s: EGL 0x%x GL 0x%x", operation, eglGetError(), glGetError());
    __android_log_write(ANDROID_LOG_ERROR, "WinportGL", error);
    return false;
}
uintptr_t wp_gl_framebuffer(void) { return active ? active->framebuffer : 0; }
void *wp_gl_proc(const char *name) {
    void *symbol = (void *)eglGetProcAddress(name);
    if (!symbol) symbol = dlsym(RTLD_DEFAULT, name);
    return symbol;
}
bool wp_gl_size(Graphics *g, unsigned width, unsigned height) {
    if (!width || !height || width > 8192 || height > 8192) return fail("Invalid hardware framebuffer dimensions");
    if (g->width == width && g->height == height) return true;
    GLint max_size, draw, read, texture, renderbuffer, unpack;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_size);
    if (width > (unsigned)max_size || height > (unsigned)max_size) return fail("Framebuffer exceeds GLES texture limit");
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture);
    glGetIntegerv(GL_RENDERBUFFER_BINDING, &renderbuffer);
    glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpack);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    if (!g->framebuffer) glGenFramebuffers(1, &g->framebuffer);
    if (!g->color) glGenTextures(1, &g->color);
    glBindFramebuffer(GL_FRAMEBUFFER, g->framebuffer);
    glBindTexture(GL_TEXTURE_2D, g->color);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g->color, 0);
    if (g->want_depth) {
        if (!g->depth) glGenRenderbuffers(1, &g->depth);
        glBindRenderbuffer(GL_RENDERBUFFER, g->depth);
        glRenderbufferStorage(GL_RENDERBUFFER, g->want_stencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, g->want_stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, g->depth);
    }
    bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindTexture(GL_TEXTURE_2D, texture);
    glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
    if (!complete) return fail("Incomplete hardware framebuffer");
    g->width = width; g->height = height;
    __android_log_print(ANDROID_LOG_INFO, "WinportGL", "FBO %u: %ux%u depth=%d stencil=%d", g->framebuffer, width, height, g->want_depth, g->want_stencil);
    return true;
}
void wp_gl_destroy(Graphics *g) {
    if (!g) return;
    if (g->display != EGL_NO_DISPLAY) {
        if (g->context != EGL_NO_CONTEXT && g->surface != EGL_NO_SURFACE) {
            eglMakeCurrent(g->display, g->surface, g->surface, g->context);
            glDeleteFramebuffers(1, &g->framebuffer);
            glDeleteTextures(1, &g->color);
            glDeleteRenderbuffers(1, &g->depth);
        }
        eglMakeCurrent(g->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g->surface != EGL_NO_SURFACE) eglDestroySurface(g->display, g->surface);
        if (g->context != EGL_NO_CONTEXT) eglDestroyContext(g->display, g->context);
        eglTerminate(g->display);
    }
    if (active == g) active = NULL;
    free(g);
}
Graphics *wp_gl_create(uintptr_t window, unsigned minor, bool depth, bool stencil, unsigned width, unsigned height) {
    Graphics *g = calloc(1, sizeof(*g));
    if (!g) { fail("Allocate EGL state"); return NULL; }
    EGLConfig config;
    EGLint count, visual;
    EGLint attributes[] = { EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
    EGLint context[] = { EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, (EGLint)minor, EGL_NONE };
    g->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g->display == EGL_NO_DISPLAY || !eglInitialize(g->display, NULL, NULL) || !eglBindAPI(EGL_OPENGL_ES_API)) goto failed;
    if (!eglChooseConfig(g->display, attributes, &config, 1, &count) || count != 1) goto failed;
    if (!eglGetConfigAttrib(g->display, config, EGL_NATIVE_VISUAL_ID, &visual)) goto failed;
    ANativeWindow_setBuffersGeometry((ANativeWindow *)window, 0, 0, visual);
    g->context = eglCreateContext(g->display, config, EGL_NO_CONTEXT, context);
    if (g->context == EGL_NO_CONTEXT) goto failed;
    g->surface = eglCreateWindowSurface(g->display, config, (ANativeWindow *)window, NULL);
    if (g->surface == EGL_NO_SURFACE || !eglMakeCurrent(g->display, g->surface, g->surface, g->context)) goto failed;
    eglSwapInterval(g->display, 0);
    g->want_depth = depth || stencil; g->want_stencil = stencil;
    if (!wp_gl_size(g, width, height)) { wp_gl_destroy(g); return NULL; }
    active = g;
    glBindFramebuffer(GL_FRAMEBUFFER, g->framebuffer);
    __android_log_print(ANDROID_LOG_INFO, "WinportGL", "EGL ready: %s / %s", glGetString(GL_VERSION), glGetString(GL_RENDERER));
    return g;
failed:
    fail("Create EGL context/window surface"); wp_gl_destroy(g); return NULL;
}
static int surface_failure(const char *operation) {
    EGLint code = eglGetError();
    snprintf(error, sizeof(error), "%s: EGL 0x%x", operation, code);
    __android_log_write(ANDROID_LOG_ERROR, "WinportGL", error);
    if (code == EGL_CONTEXT_LOST) return 2;
    if (code == EGL_BAD_SURFACE || code == EGL_BAD_NATIVE_WINDOW) return 0;
    return -1;
}
int wp_gl_present(Graphics *g, unsigned width, unsigned height, float aspect, bool bottom_left) {
    if (!g || !width || !height || width > g->width || height > g->height) { fail("Hardware frame outside FBO"); return -1; }
    EGLint window_width, window_height;
    if (!eglQuerySurface(g->display, g->surface, EGL_WIDTH, &window_width) ||
        !eglQuerySurface(g->display, g->surface, EGL_HEIGHT, &window_height)) return surface_failure("Query EGL surface");
    int rect[4]; wp_gl_viewport(window_width, window_height, aspect, rect);
    GLint draw, read; GLfloat clear[4]; GLboolean mask[4];
    GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, clear); glGetBooleanv(GL_COLOR_WRITEMASK, mask);
    glDisable(GL_SCISSOR_TEST); glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, g->framebuffer);
    glBlitFramebuffer(0, bottom_left ? 0 : height, width, bottom_left ? height : 0,
        rect[0], rect[1], rect[0]+rect[2], rect[1]+rect[3], GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw); glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
    glClearColor(clear[0], clear[1], clear[2], clear[3]); glColorMask(mask[0], mask[1], mask[2], mask[3]);
    if (scissor) glEnable(GL_SCISSOR_TEST);
    if (!eglSwapBuffers(g->display, g->surface)) return surface_failure("EGL swap buffers");
    if (++g->frames == 1) __android_log_print(ANDROID_LOG_INFO, "WinportGL", "Presented hardware frame %ux%u bottom_left=%d viewport=%d,%d %dx%d", width,height,bottom_left,rect[0],rect[1],rect[2],rect[3]);
    return true;
}
