use std::ffi::{c_char, c_void};

#[repr(C)]
#[derive(Clone, Copy)]
pub(crate) struct RenderCallback {
    pub context_type: u32,
    pub context_reset: Option<unsafe extern "C" fn()>,
    pub get_current_framebuffer: Option<unsafe extern "C" fn() -> usize>,
    pub get_proc_address: Option<unsafe extern "C" fn(*const c_char) -> *const c_void>,
    pub depth: bool,
    pub stencil: bool,
    pub bottom_left_origin: bool,
    pub version_major: u32,
    pub version_minor: u32,
    pub cache_context: bool,
    pub context_destroy: Option<unsafe extern "C" fn()>,
    pub debug_context: bool,
}

pub(crate) fn supported(callback: &RenderCallback) -> bool {
    callback.context_reset.is_some()
        && (matches!(callback.context_type, 2 | 4)
            || (callback.context_type == 5
                && callback.version_major == 3
                && callback.version_minor <= 2))
}

pub(crate) fn viewport(width: u32, height: u32, aspect: f32) -> [i32; 4] {
    if width == 0 || height == 0 || !aspect.is_finite() || aspect <= 0.0 {
        return [0, 0, width as i32, height as i32];
    }
    let w = width.min((height as f32 * aspect).round() as u32).max(1);
    let h = height.min((w as f32 / aspect).round() as u32).max(1);
    [
        ((width - w) / 2) as i32,
        ((height - h) / 2) as i32,
        w as i32,
        h as i32,
    ]
}

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use std::{ffi::CStr, ptr};
    extern "C" {
        fn wp_gl_create(
            window: usize,
            minor: u32,
            depth: bool,
            stencil: bool,
            width: u32,
            height: u32,
        ) -> *mut c_void;
        fn wp_gl_destroy(state: *mut c_void);
        fn wp_gl_make_current(state: *mut c_void) -> bool;
        fn wp_gl_size(state: *mut c_void, width: u32, height: u32) -> bool;
        fn wp_gl_present(
            state: *mut c_void,
            width: u32,
            height: u32,
            aspect: f32,
            bottom_left: bool,
        ) -> i32;
        fn wp_gl_error() -> *const c_char;
        pub(crate) fn wp_gl_framebuffer() -> usize;
        pub(crate) fn wp_gl_proc(name: *const c_char) -> *const c_void;
        fn ANativeWindow_release(window: *mut c_void);
    }
    fn error() -> String {
        unsafe { CStr::from_ptr(wp_gl_error()).to_string_lossy().into_owned() }
    }
    pub(crate) struct Graphics {
        callback: RenderCallback,
        state: *mut c_void,
        window: usize,
        generation: u64,
        notified: bool,
        lost_surface: Option<u64>,
    }
    unsafe impl Send for Graphics {}
    impl Graphics {
        pub fn new(callback: RenderCallback) -> Self {
            Self {
                callback,
                state: ptr::null_mut(),
                window: 0,
                generation: u64::MAX,
                notified: false,
                lost_surface: None,
            }
        }
        pub fn sync(&mut self, width: u32, height: u32) -> Result<bool, String> {
            let (window, generation) = crate::libretro::window::acquire();
            if self.lost_surface == Some(generation) {
                if window != 0 {
                    unsafe {
                        ANativeWindow_release(window as *mut c_void);
                    }
                }
                return Ok(false);
            }
            self.lost_surface = None;
            if generation != self.generation {
                self.notify_destroy();
                self.free();
                self.window = window;
                self.generation = generation;
                if window != 0 {
                    let minor = if self.callback.context_type == 5 {
                        self.callback.version_minor
                    } else {
                        0
                    };
                    self.state = unsafe {
                        wp_gl_create(
                            window,
                            minor,
                            self.callback.depth,
                            self.callback.stencil,
                            width,
                            height,
                        )
                    };
                    if self.state.is_null() {
                        return Err(error());
                    }
                    self.notified = true;
                    unsafe {
                        (self.callback.context_reset.unwrap())();
                    }
                }
            } else if window != 0 {
                unsafe {
                    ANativeWindow_release(window as *mut c_void);
                }
            }
            if self.state.is_null() {
                return Ok(false);
            }
            if !unsafe { wp_gl_size(self.state, width, height) } {
                return Err(error());
            }
            Ok(true)
        }
        pub fn present(&mut self, width: u32, height: u32, aspect: f32) -> Result<(), String> {
            let result = unsafe {
                wp_gl_present(
                    self.state,
                    width,
                    height,
                    aspect,
                    self.callback.bottom_left_origin,
                )
            };
            match result {
                1 => {}
                0 => self.lost_surface = Some(self.generation),
                2 => {
                    self.generation = u64::MAX;
                    self.notified = false;
                }
                _ => return Err(error()),
            }
            Ok(())
        }
        pub fn notify_destroy(&mut self) {
            if self.notified {
                if !unsafe { wp_gl_make_current(self.state) } {
                    return;
                }
                crate::libretro::teardown_trace(c"Teardown: context_destroy while EGL current\n");
                if let Some(destroy) = self.callback.context_destroy {
                    unsafe {
                        destroy();
                    }
                }
                self.notified = false;
            }
        }
        fn free(&mut self) {
            unsafe {
                if !self.state.is_null() {
                    wp_gl_destroy(self.state);
                    self.state = ptr::null_mut();
                }
                if self.window != 0 {
                    ANativeWindow_release(self.window as *mut c_void);
                    self.window = 0;
                }
            }
        }
    }
    impl Drop for Graphics {
        fn drop(&mut self) {
            self.free();
        }
    }
}
#[cfg(target_os = "android")]
pub(crate) use android::*;

#[no_mangle]
pub extern "C" fn wp_gl_viewport(w: u32, h: u32, aspect: f32, out: &mut [i32; 4]) {
    *out = viewport(w, h, aspect);
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn aspect_fit_and_invalid_geometry() {
        assert_eq!(viewport(1920, 1080, 4.0 / 3.0), [240, 0, 1440, 1080]);
        assert_eq!(viewport(1080, 1080, 16.0 / 9.0), [0, 236, 1080, 608]);
        assert_eq!(viewport(0, 0, 1.0), [0; 4]);
        assert_eq!(viewport(640, 480, f32::NAN), [0, 0, 640, 480]);
    }
    #[test]
    fn rejects_desktop_vulkan_and_invalid_es_requests() {
        let mut cb: RenderCallback = unsafe { std::mem::zeroed() };
        assert!(!supported(&cb));
        unsafe extern "C" fn reset() {}
        cb.context_reset = Some(reset);
        for context in [0, 1, 3, 6] {
            cb.context_type = context;
            assert!(!supported(&cb));
        }
        for context in [2, 4] {
            cb.context_type = context;
            assert!(supported(&cb));
        }
        cb.context_type = 5;
        cb.version_major = 3;
        cb.version_minor = 2;
        assert!(supported(&cb));
        cb.version_major = 4;
        assert!(!supported(&cb));
    }
}
