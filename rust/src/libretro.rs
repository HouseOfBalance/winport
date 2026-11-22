//! Single-session libretro frontend. Core calls are serialized by the JNI session lock.
use crate::retro_hw::RenderCallback;
use libloading::Library;
use std::{
    collections::{BTreeMap, VecDeque},
    ffi::{c_char, c_void, CStr, CString},
    fs,
    path::{Path, PathBuf},
    ptr, slice,
    sync::Mutex,
    time::{Duration, Instant},
};

type Env = unsafe extern "C" fn(u32, *mut c_void) -> bool;
type Video = unsafe extern "C" fn(*const c_void, u32, u32, usize);
type Audio = unsafe extern "C" fn(i16, i16);
type Batch = unsafe extern "C" fn(*const i16, usize) -> usize;
type Poll = unsafe extern "C" fn();
type Input = unsafe extern "C" fn(u32, u32, u32, u32) -> i16;

#[repr(C)]
struct Variable {
    key: *const c_char,
    value: *const c_char,
}
#[repr(C)]
struct OptionValue {
    value: *const c_char,
    label: *const c_char,
}
#[repr(C)]
struct OptionV1 {
    key: *const c_char,
    desc: *const c_char,
    info: *const c_char,
    values: [OptionValue; 128],
    default_value: *const c_char,
}
#[repr(C)]
struct OptionV2 {
    key: *const c_char,
    desc: *const c_char,
    desc_categorized: *const c_char,
    info: *const c_char,
    info_categorized: *const c_char,
    category_key: *const c_char,
    values: [OptionValue; 128],
    default_value: *const c_char,
}
#[repr(C)]
struct OptionsV2 {
    categories: *const c_void,
    definitions: *const OptionV2,
}
#[repr(C)]
struct ControllerDescription {
    description: *const c_char,
    id: u32,
}
#[repr(C)]
struct ControllerInfo {
    types: *const ControllerDescription,
    count: u32,
}
#[repr(C)]
struct InputDescriptor {
    port: u32,
    device: u32,
    index: u32,
    id: u32,
    description: *const c_char,
}
#[repr(C)]
struct Intl<T> {
    us: *const T,
    local: *const T,
}
#[repr(C)]
struct GameInfo {
    path: *const c_char,
    data: *const c_void,
    size: usize,
    meta: *const c_char,
}
#[repr(C)]
#[derive(Default)]
struct SystemInfo {
    name: *const c_char,
    version: *const c_char,
    extensions: *const c_char,
    fullpath: bool,
    block_extract: bool,
}
#[repr(C)]
#[derive(Default, Clone, Copy)]
struct Geometry {
    base_width: u32,
    base_height: u32,
    max_width: u32,
    max_height: u32,
    aspect: f32,
}
#[repr(C)]
#[derive(Default, Clone, Copy)]
struct Timing {
    fps: f64,
    sample_rate: f64,
}
#[repr(C)]
#[derive(Default, Clone, Copy)]
struct AvInfo {
    geometry: Geometry,
    timing: Timing,
}

struct Callbacks {
    system: CString,
    saves: CString,
    format: u32,
    options: BTreeMap<String, CString>,
    frame: Vec<u32>,
    width: u32,
    height: u32,
    frames: u64,
    audio: VecDeque<i16>,
    audio_dropped: u64,
    shutdown: bool,
    av: AvInfo,
    controller: u32,
    descriptors: Vec<String>,
    hw: Option<RenderCallback>,
    hw_frame: bool,
}
impl Callbacks {
    fn new(system: &Path, saves: &Path) -> Result<Self, String> {
        Ok(Self {
            system: cpath(system)?,
            saves: cpath(saves)?,
            format: 0,
            options: BTreeMap::new(),
            frame: Vec::new(),
            width: 0,
            height: 0,
            frames: 0,
            audio: VecDeque::new(),
            audio_dropped: 0,
            shutdown: false,
            av: AvInfo::default(),
            controller: 1,
            descriptors: Vec::new(),
            hw: None,
            hw_frame: false,
        })
    }
    unsafe fn option(&mut self, key: *const c_char, default: *const c_char) {
        if key.is_null() || default.is_null() {
            return;
        }
        let key = CStr::from_ptr(key).to_string_lossy().into_owned();
        let mut value = CStr::from_ptr(default).to_owned();
        if key == "swanstation_GPU_Renderer" {
            value = CString::new("Software").unwrap();
        }
        self.options.insert(key, value);
    }
    unsafe fn v1(&mut self, mut option: *const OptionV1) {
        if option.is_null() {
            return;
        }
        for _ in 0..4096 {
            let o = &*option;
            if o.key.is_null() {
                break;
            }
            self.option(
                o.key,
                if o.default_value.is_null() {
                    o.values[0].value
                } else {
                    o.default_value
                },
            );
            option = option.add(1);
        }
    }
    unsafe fn v2(&mut self, options: *const OptionsV2) {
        if options.is_null() {
            return;
        }
        let mut option = (*options).definitions;
        if option.is_null() {
            return;
        }
        for _ in 0..4096 {
            let o = &*option;
            if o.key.is_null() {
                break;
            }
            self.option(
                o.key,
                if o.default_value.is_null() {
                    o.values[0].value
                } else {
                    o.default_value
                },
            );
            option = option.add(1);
        }
    }
    unsafe fn environment(&mut self, command: u32, data: *mut c_void) -> bool {
        if command == 7 {
            self.shutdown = true;
            return true;
        }
        if data.is_null() {
            return false;
        }
        match command & 0xffff {
            2 => {
                *(data as *mut bool) = false;
                true
            }
            3 => {
                *(data as *mut bool) = true;
                true
            }
            9 => {
                *(data as *mut *const c_char) = self.system.as_ptr();
                true
            }
            10 => {
                let format = *(data as *const u32);
                if format <= 2 {
                    self.format = format;
                    true
                } else {
                    false
                }
            }
            11 => {
                self.descriptors.clear();
                let mut descriptor = data as *const InputDescriptor;
                for _ in 0..512 {
                    if (*descriptor).description.is_null() {
                        break;
                    }
                    self.descriptors.push(
                        CStr::from_ptr((*descriptor).description)
                            .to_string_lossy()
                            .into_owned(),
                    );
                    descriptor = descriptor.add(1);
                }
                true
            }
            14 => {
                let callback = &mut *(data as *mut RenderCallback);
                if !crate::retro_hw::supported(callback) {
                    return false;
                }
                #[cfg(target_os = "android")]
                {
                    callback.get_current_framebuffer = Some(crate::retro_hw::wp_gl_framebuffer);
                    callback.get_proc_address = Some(crate::retro_hw::wp_gl_proc);
                    self.hw = Some(*callback);
                    return true;
                }
                #[cfg(not(target_os = "android"))]
                false
            }
            35 => {
                let info = &*(data as *const ControllerInfo);
                if !info.types.is_null() && info.count <= 128 {
                    for description in slice::from_raw_parts(info.types, info.count as usize) {
                        if !description.description.is_null()
                            && CStr::from_ptr(description.description)
                                .to_string_lossy()
                                .to_lowercase()
                                .contains("dualshock")
                        {
                            self.controller = description.id;
                            break;
                        }
                    }
                }
                true
            }
            18 | 55 => true,
            15 => {
                let var = &mut *(data as *mut Variable);
                if var.key.is_null() {
                    return false;
                }
                var.value = self
                    .options
                    .get(CStr::from_ptr(var.key).to_string_lossy().as_ref())
                    .map_or(ptr::null(), |v| v.as_ptr());
                !var.value.is_null()
            }
            16 => {
                let mut var = data as *const Variable;
                for _ in 0..4096 {
                    if (*var).key.is_null() {
                        break;
                    }
                    if !(*var).value.is_null() {
                        let text = CStr::from_ptr((*var).value).to_string_lossy();
                        if let Some((_, values)) = text.split_once(';') {
                            if let Ok(default) =
                                CString::new(values.trim_start().split('|').next().unwrap_or(""))
                            {
                                self.option((*var).key, default.as_ptr());
                            }
                        }
                    }
                    var = var.add(1);
                }
                true
            }
            17 => {
                *(data as *mut bool) = false;
                true
            }
            27 => {
                *(data as *mut *const c_void) = winport_retro_log as *const c_void;
                true
            }
            31 => {
                *(data as *mut *const c_char) = self.saves.as_ptr();
                true
            }
            32 => {
                self.av = *(data as *const AvInfo);
                true
            }
            37 => {
                self.av.geometry = *(data as *const Geometry);
                true
            }
            39 => {
                *(data as *mut u32) = 0;
                true
            }
            47 => {
                *(data as *mut i32) = 3;
                true
            }
            51 => true,
            52 => {
                *(data as *mut u32) = 2;
                true
            }
            53 => {
                self.v1(data as *const OptionV1);
                true
            }
            54 => {
                self.v1((*(data as *const Intl<OptionV1>)).us);
                true
            }
            56 => {
                *(data as *mut u32) = if cfg!(target_os = "android") { 4 } else { 0 };
                true
            }
            57 => {
                *(data as *mut u32) = 0;
                true
            }
            67 => {
                self.v2(data as *const OptionsV2);
                true
            }
            68 => {
                self.v2((*(data as *const Intl<OptionsV2>)).us);
                true
            }
            _ => false,
        }
    }
    fn push_audio(&mut self, samples: &[i16]) {
        const LIMIT: usize = 192_000;
        let samples = &samples[samples.len().saturating_sub(LIMIT)..];
        let drop = (self.audio.len() + samples.len()).saturating_sub(LIMIT);
        self.audio.drain(..drop);
        self.audio_dropped += drop as u64;
        self.audio.extend(samples);
    }
}
static CALLBACKS: Mutex<Option<Callbacks>> = Mutex::new(None);
static INPUT: Mutex<[[i16; 20]; 8]> = Mutex::new([[0; 20]; 8]);
extern "C" {
    fn winport_retro_log(level: i32, format: *const c_char, ...);
}

unsafe extern "C" fn environment(command: u32, data: *mut c_void) -> bool {
    CALLBACKS
        .lock()
        .ok()
        .and_then(|mut c| c.as_mut().map(|c| c.environment(command, data)))
        .unwrap_or(false)
}
pub fn convert_pixels(
    data: &[u8],
    width: usize,
    height: usize,
    pitch: usize,
    format: u32,
) -> Result<Vec<u32>, String> {
    if width == 0 || height == 0 || width > 4096 || height > 4096 || format > 2 {
        return Err("Invalid video dimensions or format".into());
    }
    let bpp = if format == 1 { 4 } else { 2 };
    let size = pitch.checked_mul(height).ok_or("Video size overflow")?;
    if pitch < width * bpp || data.len() < size {
        return Err("Truncated video frame".into());
    }
    let mut pixels = Vec::with_capacity(width * height);
    for row in data[..size].chunks_exact(pitch) {
        for p in row[..width * bpp].chunks_exact(bpp) {
            let (r, g, b) = if format == 1 {
                let n = u32::from_ne_bytes(p.try_into().unwrap());
                ((n >> 16) as u8, (n >> 8) as u8, n as u8)
            } else {
                let n = u16::from_ne_bytes(p.try_into().unwrap());
                let green_bits = if format == 2 { 6 } else { 5 };
                let r = ((n >> (green_bits + 5)) & 31) as u8;
                let g = ((n >> 5) & ((1 << green_bits) - 1)) as u8;
                let b = (n & 31) as u8;
                (
                    (r << 3) | (r >> 2),
                    if green_bits == 6 {
                        (g << 2) | (g >> 4)
                    } else {
                        (g << 3) | (g >> 2)
                    },
                    (b << 3) | (b >> 2),
                )
            };
            pixels.push(u32::from_ne_bytes([r, g, b, 255]));
        }
    }
    Ok(pixels)
}
unsafe extern "C" fn video(data: *const c_void, w: u32, h: u32, pitch: usize) {
    if data as usize == usize::MAX {
        if let Ok(mut callbacks) = CALLBACKS.lock() {
            if let Some(c) = callbacks.as_mut() {
                if c.hw.is_some() && w > 0 && h > 0 && w <= 8192 && h <= 8192 {
                    c.width = w;
                    c.height = h;
                    c.frames += 1;
                    c.hw_frame = true;
                }
            }
        }
        return;
    }
    if data.is_null()
        || data as usize == usize::MAX
        || w == 0
        || h == 0
        || w > 4096
        || h > 4096
        || pitch > 65536
    {
        return;
    }
    if let Ok(mut guard) = CALLBACKS.lock() {
        if let Some(c) = guard.as_mut() {
            if let Ok(frame) = convert_pixels(
                slice::from_raw_parts(data as *const u8, pitch * h as usize),
                w as usize,
                h as usize,
                pitch,
                c.format,
            ) {
                c.frame = frame;
                c.width = w;
                c.height = h;
                c.frames += 1;
            }
        }
    }
}
unsafe extern "C" fn audio(left: i16, right: i16) {
    if let Ok(mut c) = CALLBACKS.lock() {
        if let Some(c) = c.as_mut() {
            c.push_audio(&[left, right]);
        }
    }
}
unsafe extern "C" fn audio_batch(data: *const i16, frames: usize) -> usize {
    if !data.is_null() && frames <= 192_000 {
        if let Ok(mut c) = CALLBACKS.lock() {
            if let Some(c) = c.as_mut() {
                c.push_audio(slice::from_raw_parts(data, frames * 2));
            }
        }
    }
    frames
}
unsafe extern "C" fn poll() {}
unsafe extern "C" fn input(port: u32, device: u32, index: u32, id: u32) -> i16 {
    let Ok(input) = INPUT.lock() else {
        return 0;
    };
    let Some(p) = input.get(port as usize) else {
        return 0;
    };
    match (device & 255, index, id) {
        (1, _, 256) => p[..16]
            .iter()
            .enumerate()
            .fold(0u16, |bits, (i, v)| bits | (u16::from(*v != 0) << i))
            as i16,
        (1, _, 0..=15) => p[id as usize],
        (5, 0..=1, 0..=1) => p[16 + index as usize * 2 + id as usize],
        _ => 0,
    }
}
pub fn set_input(port: usize, id: usize, value: i16) {
    if let Ok(mut input) = INPUT.lock() {
        if port < 8 && id < 20 {
            input[port][id] = value;
        }
    }
}
pub fn clear_input() {
    if let Ok(mut input) = INPUT.lock() {
        *input = [[0; 20]; 8];
    }
}
pub fn drain_audio(max_samples: usize) -> Vec<i16> {
    let Ok(mut c) = CALLBACKS.lock() else {
        return Vec::new();
    };
    let Some(c) = c.as_mut() else {
        return Vec::new();
    };
    let count = c.audio.len().min(max_samples) & !1;
    c.audio.drain(..count).collect()
}
fn cpath(path: &Path) -> Result<CString, String> {
    CString::new(path.to_string_lossy().as_bytes()).map_err(|e| e.to_string())
}
fn atomic_write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let staging = path.with_extension("tmp");
    fs::write(&staging, bytes).map_err(|e| e.to_string())?;
    fs::rename(staging, path).map_err(|e| e.to_string())
}

pub struct Session {
    _library: Library,
    _path: CString,
    _content: Vec<u8>,
    run: unsafe extern "C" fn(),
    deinit: unsafe extern "C" fn(),
    unload: unsafe extern "C" fn(),
    serialize_size: unsafe extern "C" fn() -> usize,
    serialize: unsafe extern "C" fn(*mut c_void, usize) -> bool,
    unserialize: unsafe extern "C" fn(*const c_void, usize) -> bool,
    memory_data: unsafe extern "C" fn(u32) -> *mut c_void,
    memory_size: unsafe extern "C" fn(u32) -> usize,
    save_path: PathBuf,
    sram_path: PathBuf,
    fps: f64,
    sample_rate: u32,
    deadline: Instant,
    paused: bool,
    loaded: bool,
    initialized: bool,
    name: String,
    rom: String,
    #[cfg(target_os = "android")]
    graphics: Option<crate::retro_hw::Graphics>,
}
unsafe impl Send for Session {}
impl Session {
    /// Only load trusted cores: native libraries execute with the app's privileges.
    pub unsafe fn load(
        core: &Path,
        rom: &Path,
        system: &Path,
        saves: &Path,
        _java_vm: *mut c_void,
    ) -> Result<Self, String> {
        fs::create_dir_all(system).map_err(|e| e.to_string())?;
        fs::create_dir_all(saves).map_err(|e| e.to_string())?;
        #[cfg(target_os = "android")]
        if core.file_name().and_then(|n| n.to_str()) == Some("play_libretro_android.so") {
            std::env::set_var("EXTERNAL_STORAGE", saves);
        }
        let label = CString::new(core.to_string_lossy().as_bytes()).map_err(|e| e.to_string())?;
        winport_retro_log(1, b"Loading core %s\n\0".as_ptr().cast(), label.as_ptr());
        let library = Library::new(core).map_err(|e| format!("dlopen {}: {e}", core.display()))?;
        #[cfg(target_os = "android")]
        if core.file_name().and_then(|n| n.to_str()) == Some("play_libretro_android.so")
            && !_java_vm.is_null()
        {
            if let Ok(set_vm) = library.get::<unsafe extern "C" fn(*mut c_void)>(
                b"_ZN9Framework7CJavaVM9SetJavaVMEP7_JavaVM\0",
            ) {
                set_vm(_java_vm);
            }
        }
        macro_rules! sym {
            ($name:literal, $ty:ty) => {
                *library
                    .get::<$ty>(concat!($name, "\0").as_bytes())
                    .map_err(|e| e.to_string())?
            };
        }
        let api = sym!("retro_api_version", unsafe extern "C" fn() -> u32);
        if api() != 1 {
            return Err("Unsupported libretro API".into());
        }
        let set_env = sym!("retro_set_environment", unsafe extern "C" fn(Env));
        let set_video = sym!("retro_set_video_refresh", unsafe extern "C" fn(Video));
        let set_audio = sym!("retro_set_audio_sample", unsafe extern "C" fn(Audio));
        let set_batch = sym!("retro_set_audio_sample_batch", unsafe extern "C" fn(Batch));
        let set_poll = sym!("retro_set_input_poll", unsafe extern "C" fn(Poll));
        let set_input = sym!("retro_set_input_state", unsafe extern "C" fn(Input));
        let init = sym!("retro_init", unsafe extern "C" fn());
        let get_info = sym!(
            "retro_get_system_info",
            unsafe extern "C" fn(*mut SystemInfo)
        );
        let get_av = sym!(
            "retro_get_system_av_info",
            unsafe extern "C" fn(*mut AvInfo)
        );
        let load = sym!(
            "retro_load_game",
            unsafe extern "C" fn(*const GameInfo) -> bool
        );
        let set_controller = sym!(
            "retro_set_controller_port_device",
            unsafe extern "C" fn(u32, u32)
        );
        let path = cpath(rom)?;
        let mut info = SystemInfo::default();
        get_info(&mut info);
        let content = if info.fullpath {
            Vec::new()
        } else {
            fs::read(rom).map_err(|e| e.to_string())?
        };
        if !rom.is_file() {
            return Err("ROM does not exist".into());
        }
        let name = if info.name.is_null() {
            "Libretro".into()
        } else {
            CStr::from_ptr(info.name).to_string_lossy().into_owned()
        };
        let mut session = Self {
            run: sym!("retro_run", unsafe extern "C" fn()),
            deinit: sym!("retro_deinit", unsafe extern "C" fn()),
            unload: sym!("retro_unload_game", unsafe extern "C" fn()),
            serialize_size: sym!("retro_serialize_size", unsafe extern "C" fn() -> usize),
            serialize: sym!(
                "retro_serialize",
                unsafe extern "C" fn(*mut c_void, usize) -> bool
            ),
            unserialize: sym!(
                "retro_unserialize",
                unsafe extern "C" fn(*const c_void, usize) -> bool
            ),
            memory_data: sym!(
                "retro_get_memory_data",
                unsafe extern "C" fn(u32) -> *mut c_void
            ),
            memory_size: sym!("retro_get_memory_size", unsafe extern "C" fn(u32) -> usize),
            _library: library,
            _path: path,
            _content: content,
            save_path: saves.join("state.bin"),
            sram_path: saves.join("sram.bin"),
            fps: 60.0,
            sample_rate: 44100,
            deadline: Instant::now(),
            paused: false,
            loaded: false,
            initialized: false,
            name,
            rom: rom
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned(),
            #[cfg(target_os = "android")]
            graphics: None,
        };
        *CALLBACKS.lock().map_err(|e| e.to_string())? = Some(Callbacks::new(system, saves)?);
        clear_input();
        set_env(environment);
        set_video(video);
        set_audio(audio);
        set_batch(audio_batch);
        set_poll(poll);
        set_input(input);
        winport_retro_log(1, b"Initializing core\n\0".as_ptr().cast());
        init();
        winport_retro_log(1, b"Core initialized\n\0".as_ptr().cast());
        session.initialized = true;
        let game = GameInfo {
            path: session._path.as_ptr(),
            data: if session._content.is_empty() {
                ptr::null()
            } else {
                session._content.as_ptr().cast()
            },
            size: session._content.len(),
            meta: ptr::null(),
        };
        if !load(&game) {
            return Err("Core rejected the ROM. Check its format and any BIOS requirements; PCSX ReARMed supports HLE without Sony BIOS.".into());
        }
        session.loaded = true;
        let controller = CALLBACKS.lock().unwrap().as_ref().unwrap().controller;
        set_controller(0, controller);
        let mut av = AvInfo::default();
        get_av(&mut av);
        if !av.timing.fps.is_finite()
            || !(1.0..=300.0).contains(&av.timing.fps)
            || !av.timing.sample_rate.is_finite()
            || !(8000.0..=192000.0).contains(&av.timing.sample_rate)
        {
            return Err("Core returned invalid AV timing".into());
        }
        session.fps = av.timing.fps;
        session.sample_rate = av.timing.sample_rate as u32;
        if let Some(c) = CALLBACKS.lock().unwrap().as_mut() {
            c.av = av;
        }
        #[cfg(target_os = "android")]
        {
            let hw = CALLBACKS.lock().unwrap().as_ref().and_then(|c| c.hw);
            session.graphics = hw.map(crate::retro_hw::Graphics::new);
            session.sync_surface()?;
        }
        session.load_sram()?;
        Ok(session)
    }
    pub fn sync_surface(&mut self) -> Result<bool, String> {
        #[cfg(target_os = "android")]
        if let Some(graphics) = self.graphics.as_mut() {
            let geometry = CALLBACKS.lock().unwrap().as_ref().unwrap().av.geometry;
            return graphics.sync(
                geometry.max_width.max(geometry.base_width),
                geometry.max_height.max(geometry.base_height),
            );
        }
        Ok(true)
    }
    pub fn run_frame(&mut self) -> Result<(), String> {
        if !self.sync_surface()? {
            std::thread::sleep(Duration::from_millis(10));
            return Ok(());
        }
        if self.paused {
            return Ok(());
        }
        unsafe {
            (self.run)();
        }
        if let Some(c) = CALLBACKS.lock().unwrap().as_ref() {
            if c.shutdown {
                return Err("Core requested shutdown".into());
            }
            if c.av.timing.fps.is_finite() && (1.0..=300.0).contains(&c.av.timing.fps) {
                self.fps = c.av.timing.fps;
            }
            #[cfg(target_os = "android")]
            if let Some(graphics) = self.graphics.as_mut() {
                if c.hw_frame {
                    let aspect = if c.av.geometry.aspect > 0.0 {
                        c.av.geometry.aspect
                    } else {
                        c.width as f32 / c.height as f32
                    };
                    graphics.present(c.width, c.height, aspect)?;
                }
            } else {
                window::render(&c.frame, c.width, c.height);
            }
        }
        self.deadline += Duration::from_secs_f64(1.0 / self.fps);
        let now = Instant::now();
        if self.deadline > now {
            std::thread::sleep(self.deadline - now);
        } else if now.duration_since(self.deadline) > Duration::from_millis(100) {
            self.deadline = now;
        }
        Ok(())
    }
    pub fn pause(&mut self, paused: bool) {
        self.paused = paused;
        self.deadline = Instant::now();
        clear_input();
        if let Some(c) = CALLBACKS.lock().unwrap().as_mut() {
            c.audio.clear();
        }
    }
    pub fn info(&self) -> serde_json::Value {
        let c = CALLBACKS.lock().unwrap();
        let c = c.as_ref().unwrap();
        serde_json::json!({"name":self.name,"rom":self.rom,"fps":self.fps,"sampleRate":self.sample_rate,"width":c.width,"height":c.height,"frames":c.frames,"aspect":c.av.geometry.aspect,"audioDropped":c.audio_dropped,"statePath":self.save_path,"sramPath":self.sram_path})
    }
    pub fn save_state(&self) -> Result<usize, String> {
        unsafe {
            let size = (self.serialize_size)();
            if size == 0 || size > 128 * 1024 * 1024 {
                return Err("Core does not support a valid save-state size".into());
            }
            let mut bytes = vec![0; size];
            if !(self.serialize)(bytes.as_mut_ptr().cast(), size) {
                return Err("Core failed to serialize".into());
            }
            atomic_write(&self.save_path, &bytes)?;
            Ok(size)
        }
    }
    pub fn load_state(&self) -> Result<(), String> {
        let bytes =
            fs::read(&self.save_path).map_err(|e| format!("No readable save state: {e}"))?;
        unsafe {
            if bytes.is_empty() || bytes.len() != (self.serialize_size)() {
                return Err("Save-state size does not match this core".into());
            }
            if !(self.unserialize)(bytes.as_ptr().cast(), bytes.len()) {
                return Err("Core rejected save state".into());
            }
        }
        if let Some(c) = CALLBACKS.lock().unwrap().as_mut() {
            c.audio.clear();
        }
        Ok(())
    }
    pub fn verify_state(&self) -> Result<serde_json::Value, String> {
        let size = self.save_state()?;
        let frame = || -> Result<Vec<u32>, String> {
            let callbacks = CALLBACKS.lock().map_err(|e| e.to_string())?;
            let pixels = &callbacks.as_ref().ok_or("No active core")?.frame;
            if pixels.is_empty() {
                return Err("No software video frame to verify".into());
            }
            Ok(pixels.clone())
        };
        let initial = frame()?;
        unsafe {
            for _ in 0..5 {
                (self.run)();
            }
        }
        let first = frame()?;
        self.load_state()?;
        unsafe {
            for _ in 0..5 {
                (self.run)();
            }
        }
        let replay = frame()?;
        self.load_state()?;
        Ok(
            serde_json::json!({"bytes":size,"restored":true,"frameAdvanced":initial != first,"replayMatched":first == replay}),
        )
    }
    fn load_sram(&mut self) -> Result<(), String> {
        if !self.sram_path.exists() {
            return Ok(());
        }
        let bytes = fs::read(&self.sram_path).map_err(|e| e.to_string())?;
        unsafe {
            let size = (self.memory_size)(0);
            let data = (self.memory_data)(0);
            if size != bytes.len() || data.is_null() {
                return Err("SRAM size does not match core".into());
            }
            ptr::copy_nonoverlapping(bytes.as_ptr(), data.cast(), size);
        }
        Ok(())
    }
    pub fn save_sram(&self) -> Result<(), String> {
        unsafe {
            let size = (self.memory_size)(0);
            let data = (self.memory_data)(0);
            if size == 0 {
                return Ok(());
            }
            if data.is_null() || size > 32 * 1024 * 1024 {
                return Err("Invalid SRAM memory".into());
            }
            atomic_write(&self.sram_path, slice::from_raw_parts(data.cast(), size))
        }
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        #[cfg(target_os = "android")]
        if let Some(graphics) = self.graphics.as_mut() {
            graphics.notify_destroy();
        }
        unsafe {
            if self.loaded {
                (self.unload)();
            }
            if self.initialized {
                (self.deinit)();
            }
        }
        #[cfg(target_os = "android")]
        self.graphics.take();
        *CALLBACKS.lock().unwrap() = None;
    }
}

#[cfg(target_os = "android")]
pub mod window {
    use super::*;
    #[repr(C)]
    struct Buffer {
        width: i32,
        height: i32,
        stride: i32,
        format: i32,
        bits: *mut c_void,
        reserved: [u32; 6],
    }
    extern "C" {
        fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut c_void;
        fn ANativeWindow_release(window: *mut c_void);
        fn ANativeWindow_acquire(window: *mut c_void);
        fn ANativeWindow_setBuffersGeometry(
            window: *mut c_void,
            w: i32,
            h: i32,
            format: i32,
        ) -> i32;
        fn ANativeWindow_lock(window: *mut c_void, buffer: *mut Buffer, dirty: *mut c_void) -> i32;
        fn ANativeWindow_unlockAndPost(window: *mut c_void) -> i32;
    }
    static WINDOW: Mutex<(usize, u32, u32, u64)> = Mutex::new((0, 0, 0, 0));
    pub fn acquire() -> (usize, u64) {
        let w = WINDOW.lock().unwrap();
        if w.0 != 0 {
            unsafe {
                ANativeWindow_acquire(w.0 as *mut c_void);
            }
        }
        (w.0, w.3)
    }
    pub unsafe fn set(env: *mut c_void, surface: *mut c_void) {
        let mut w = WINDOW.lock().unwrap();
        if w.0 != 0 {
            ANativeWindow_release(w.0 as *mut c_void);
        }
        let generation = w.3.wrapping_add(1);
        *w = (
            if surface.is_null() {
                0
            } else {
                ANativeWindow_fromSurface(env, surface) as usize
            },
            0,
            0,
            generation,
        );
    }
    pub fn render(frame: &[u32], width: u32, height: u32) {
        let mut window = WINDOW.lock().unwrap();
        if window.0 == 0 || frame.len() != (width * height) as usize || frame.is_empty() {
            return;
        }
        unsafe {
            let w = window.0 as *mut c_void;
            if window.1 != width || window.2 != height {
                if ANativeWindow_setBuffersGeometry(w, width as i32, height as i32, 1) != 0 {
                    return;
                }
                window.1 = width;
                window.2 = height;
            }
            let mut buffer: Buffer = std::mem::zeroed();
            if ANativeWindow_lock(w, &mut buffer, ptr::null_mut()) != 0 {
                return;
            }
            if !buffer.bits.is_null()
                && buffer.stride >= width as i32
                && buffer.height >= height as i32
            {
                for y in 0..height as usize {
                    ptr::copy_nonoverlapping(
                        frame.as_ptr().add(y * width as usize),
                        (buffer.bits as *mut u32).add(y * buffer.stride as usize),
                        width as usize,
                    );
                }
            }
            ANativeWindow_unlockAndPost(w);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn formats_and_padded_pitch() {
        for (format, pixel) in [(0, 0x7c00u16), (2, 0xf800)] {
            let mut bytes = pixel.to_ne_bytes().to_vec();
            bytes.extend([42, 42]);
            assert_eq!(
                convert_pixels(&bytes, 1, 1, 4, format).unwrap()[0].to_ne_bytes(),
                [255, 0, 0, 255]
            );
        }
        assert_eq!(
            convert_pixels(&0x00123456u32.to_ne_bytes(), 1, 1, 4, 1).unwrap()[0].to_ne_bytes(),
            [0x12, 0x34, 0x56, 255]
        );
        assert_eq!(
            convert_pixels(&0x07e0u16.to_ne_bytes(), 1, 1, 2, 2).unwrap()[0].to_ne_bytes(),
            [0, 255, 0, 255]
        );
    }
    #[test]
    fn rejects_invalid_frames() {
        for (w, h, p, f) in [
            (0, 1, 2, 0),
            (1, 0, 2, 0),
            (4097, 1, 8194, 0),
            (1, 1, 1, 0),
            (1, 1, 2, 3),
            (1, 2, 2, 0),
        ] {
            assert!(convert_pixels(&[0; 2], w, h, p, f).is_err());
        }
    }
    #[test]
    fn joypad_bitmask_analog_and_invalid_input() {
        clear_input();
        unsafe {
            assert_eq!(input(0, 1, 0, 8), 0);
            set_input(0, 8, 1);
            set_input(0, 16, -32767);
            set_input(0, 19, 16384);
            assert_eq!(input(0, 1, 0, 8), 1);
            assert_eq!(input(0, 1, 0, 256) as u16, 1 << 8);
            assert_eq!(input(0, 5, 0, 0), -32767);
            assert_eq!(input(0, 5, 1, 1), 16384);
            assert_eq!(input(8, 1, 0, 8), 0);
            assert_eq!(input(0, 5, 2, 0), 0);
            assert_eq!(input(0, 99, 0, 0), 0);
        }
        clear_input();
    }
    #[test]
    fn environment_directories_formats_and_legacy_defaults() {
        let mut c = Callbacks::new(Path::new("/system"), Path::new("/saves")).unwrap();
        unsafe {
            let mut format = 2u32;
            assert!(c.environment(10, (&mut format as *mut u32).cast()));
            assert_eq!(c.format, 2);
            format = 7;
            assert!(!c.environment(10, (&mut format as *mut u32).cast()));
            assert_eq!(c.format, 2);
            let mut path: *const c_char = ptr::null();
            assert!(c.environment(9, (&mut path as *mut *const c_char).cast()));
            assert_eq!(CStr::from_ptr(path).to_str().unwrap(), "/system");
            assert!(c.environment(31, (&mut path as *mut *const c_char).cast()));
            assert_eq!(CStr::from_ptr(path).to_str().unwrap(), "/saves");
            let key = CString::new("test").unwrap();
            let values = CString::new("Mode; default|other").unwrap();
            let mut vars = [
                Variable {
                    key: key.as_ptr(),
                    value: values.as_ptr(),
                },
                Variable {
                    key: ptr::null(),
                    value: ptr::null(),
                },
            ];
            assert!(c.environment(16, vars.as_mut_ptr().cast()));
            let mut query = Variable {
                key: key.as_ptr(),
                value: ptr::null(),
            };
            assert!(c.environment(15, (&mut query as *mut Variable).cast()));
            assert_eq!(CStr::from_ptr(query.value).to_str().unwrap(), "default");
            let unknown = CString::new("missing").unwrap();
            query.key = unknown.as_ptr();
            assert!(!c.environment(15, (&mut query as *mut Variable).cast()));
            assert!(query.value.is_null());
            let mut hw: RenderCallback = std::mem::zeroed();
            assert!(!c.environment(14, (&mut hw as *mut RenderCallback).cast()));
            assert!(!c.environment(9999, vars.as_mut_ptr().cast()));
            assert!(!c.environment(9, ptr::null_mut()));
        }
    }
    #[test]
    fn modern_defaults_and_bounded_audio() {
        let mut c = Callbacks::new(Path::new("."), Path::new(".")).unwrap();
        let key = CString::new("option").unwrap();
        let default = CString::new("enabled").unwrap();
        unsafe {
            let mut definitions: [OptionV2; 2] = std::mem::zeroed();
            definitions[0].key = key.as_ptr();
            definitions[0].default_value = default.as_ptr();
            let mut options = OptionsV2 {
                categories: ptr::null(),
                definitions: definitions.as_ptr(),
            };
            assert!(c.environment(67, (&mut options as *mut OptionsV2).cast()));
            assert_eq!(c.options["option"].to_str().unwrap(), "enabled");
        }
        c.push_audio(&vec![1; 192_000]);
        c.push_audio(&[2, 3]);
        assert_eq!(c.audio.len(), 192_000);
        assert_eq!(c.audio_dropped, 2);
        assert_eq!(c.audio.back(), Some(&3));
    }
    #[test]
    fn av_changes_and_hardware_preference() {
        let mut c = Callbacks::new(Path::new("."), Path::new(".")).unwrap();
        let mut av = AvInfo {
            geometry: Geometry {
                base_width: 480,
                base_height: 272,
                max_width: 960,
                max_height: 544,
                aspect: 16.0 / 9.0,
            },
            timing: Timing {
                fps: 59.94,
                sample_rate: 44100.0,
            },
        };
        unsafe {
            assert!(c.environment(32, (&mut av as *mut AvInfo).cast()));
            assert_eq!(c.av.timing.fps, 59.94);
            av.geometry.base_width = 960;
            assert!(c.environment(37, (&mut av.geometry as *mut Geometry).cast()));
            assert_eq!(c.av.geometry.base_width, 960);
            let mut preferred = 99u32;
            assert!(c.environment(56, (&mut preferred as *mut u32).cast()));
            assert_eq!(preferred, if cfg!(target_os = "android") { 4 } else { 0 });
            assert!(!c.environment(14, ptr::null_mut()));
        }
    }
}
