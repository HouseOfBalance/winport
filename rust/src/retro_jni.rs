use crate::libretro::{self, Session};
use ::jni::{
    objects::{JObject, JString},
    sys::{jint, jshort, jshortArray, jstring},
    JNIEnv,
};
use std::{path::Path, sync::Mutex};

static SESSION: Mutex<Option<Session>> = Mutex::new(None);

fn reply(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    match result {
        Ok(value) => env
            .new_string(value)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_winport_app_RetroNative_command(
    mut env: JNIEnv,
    _: JObject,
    action: JString,
    payload: JString,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let action: String = env.get_string(&action).map_err(|e| e.to_string())?.into();
        let payload: String = env.get_string(&payload).map_err(|e| e.to_string())?.into();
        let mut session = SESSION.lock().map_err(|e| e.to_string())?;
        if action == "start" {
            if session.is_some() {
                return Err("An emulation session is already running".into());
            }
            let p: serde_json::Value = serde_json::from_str(&payload).map_err(|e| e.to_string())?;
            let field = |key: &str| p[key].as_str().ok_or_else(|| format!("Missing {key}"));
            *session = Some(unsafe {
                Session::load(
                    Path::new(field("core")?),
                    Path::new(field("rom")?),
                    Path::new(field("system")?),
                    Path::new(field("saves")?),
                )
            }?);
            return Ok(session.as_ref().unwrap().info().to_string());
        }
        if action == "close" {
            let result = session.as_ref().map_or(Ok(()), |s| s.save_sram());
            session.take();
            libretro::clear_input();
            result?;
            return Ok("{}".into());
        }
        let s = session.as_mut().ok_or("No emulation session")?;
        match action.as_str() {
            "run" => {
                s.run_frame()?;
                Ok(String::new())
            }
            "pause" => {
                s.pause(payload == "true");
                Ok("{}".into())
            }
            "info" => Ok(s.info().to_string()),
            "save" => Ok(serde_json::json!({"savedBytes":s.save_state()?}).to_string()),
            "load" => {
                s.load_state()?;
                Ok("{\"loaded\":true}".into())
            }
            "verify" => Ok(s.verify_state()?.to_string()),
            _ => Err("Unknown emulation command".into()),
        }
    })();
    reply(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_winport_app_RetroNative_input(
    _: JNIEnv,
    _: JObject,
    port: jint,
    id: jint,
    value: jshort,
) {
    if port >= 0 && id >= 0 {
        libretro::set_input(port as usize, id as usize, value);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_winport_app_RetroNative_audio(
    env: JNIEnv,
    _: JObject,
) -> jshortArray {
    let audio = libretro::drain_audio(4096);
    let Ok(array) = env.new_short_array(audio.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_short_array_region(&array, 0, &audio).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_winport_app_RetroNative_surface(
    env: JNIEnv,
    _: JObject,
    surface: JObject,
) {
    unsafe {
        libretro::window::set(env.get_raw().cast(), surface.as_raw().cast());
    }
}
