use crate::Core;
use ::jni::{
    objects::{JObject, JString},
    sys::jstring,
    JNIEnv,
};
use std::sync::Mutex;

static CORE: Mutex<Option<Core>> = Mutex::new(None);

#[no_mangle]
pub extern "system" fn Java_io_winport_app_NativeCore_call(
    mut env: JNIEnv,
    _object: JObject,
    method: JString,
    payload: JString,
) -> jstring {
    let result = (|| -> crate::Result<String> {
        let method: String = env
            .get_string(&method)
            .map_err(|e| crate::Error::Invalid(e.to_string()))?
            .into();
        let payload: String = env
            .get_string(&payload)
            .map_err(|e| crate::Error::Invalid(e.to_string()))?
            .into();
        let value: serde_json::Value = serde_json::from_str(&payload)?;
        let mut guard = CORE
            .lock()
            .map_err(|_| crate::Error::Invalid("Core lock poisoned".into()))?;
        if method == "init" {
            if guard.is_some() {
                return Ok("{}".into());
            }
            let path = value["data_dir"]
                .as_str()
                .ok_or_else(|| crate::Error::Invalid("data_dir required".into()))?;
            *guard = Some(Core::init(path)?);
            return Ok("{}".into());
        }
        let core = guard
            .as_mut()
            .ok_or_else(|| crate::Error::Invalid("Call init first".into()))?;
        Ok(core.dispatch(&method, value)?.to_string())
    })();
    match result {
        Ok(value) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            std::ptr::null_mut()
        }
    }
}
