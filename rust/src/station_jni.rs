use ::jni::{
    objects::{JObject, JString},
    sys::jstring,
    JNIEnv,
};
#[cfg(unix)]
#[no_mangle]
pub extern "system" fn Java_io_winport_app_StationNative_probeFd(
    mut env: JNIEnv,
    _: JObject,
    fd: ::jni::sys::jint,
    name: JString,
) -> jstring {
    use std::{fs::File, os::fd::FromRawFd};
    let result = (|| -> Result<String, String> {
        let name: String = env.get_string(&name).map_err(|e| e.to_string())?.into();
        let duplicate = unsafe { libc::dup(fd) };
        if duplicate < 0 {
            return Err(std::io::Error::last_os_error().to_string());
        }
        let file = unsafe { File::from_raw_fd(duplicate) };
        Ok(crate::station::probe_file(file, name)?.to_string())
    })();
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
pub extern "system" fn Java_io_winport_app_StationNative_call(
    mut env: JNIEnv,
    _: JObject,
    root: JString,
    action: JString,
    payload: JString,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let root: String = env.get_string(&root).map_err(|e| e.to_string())?.into();
        let action: String = env.get_string(&action).map_err(|e| e.to_string())?.into();
        let payload: String = env.get_string(&payload).map_err(|e| e.to_string())?.into();
        let value = crate::station::command(
            std::path::Path::new(&root),
            &action,
            serde_json::from_str(&payload).map_err(|e| e.to_string())?,
        )?;
        Ok(value.to_string())
    })();
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
