fn main() {
    cc::Build::new()
        .file("src/retro_log.c")
        .compile("retro_log");
    println!("cargo:rerun-if-changed=src/retro_log.c");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        cc::Build::new().file("src/retro_gl.c").compile("retro_gl");
        println!("cargo:rerun-if-changed=src/retro_gl.c");
        println!("cargo:rustc-link-lib=log");
        println!("cargo:rustc-link-lib=android");
        println!("cargo:rustc-link-lib=EGL");
        println!("cargo:rustc-link-lib=GLESv3");
    }
}
