fn main() {
    cc::Build::new()
        .file("src/retro_log.c")
        .compile("retro_log");
    println!("cargo:rerun-if-changed=src/retro_log.c");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-lib=log");
        println!("cargo:rustc-link-lib=android");
    }
}
