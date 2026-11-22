use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{fs::File, io::Read, path::Path};

pub fn manifest() -> Value {
    serde_json::from_str(include_str!("../core-manifest.json")).expect("core manifest")
}
pub fn verify(path: &Path, expected: &str) -> Result<(), String> {
    let mut input = File::open(path).map_err(|e| e.to_string())?;
    let mut hash = Sha256::new();
    let mut buffer = [0; 65536];
    loop {
        let n = input.read(&mut buffer).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        hash.update(&buffer[..n]);
    }
    if expected.len() != 64 || format!("{:x}", hash.finalize()) != expected {
        return Err(
            "Lõi lỗi hash SHA-256. Nguồn tải khác bản đã ghim; cần cập nhật ứng dụng/manifest. Lõi không được cài hoặc chạy.".into(),
        );
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn integrity_normal_empty_and_tampered() {
        let file = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(file.path(), b"abc").unwrap();
        let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assert!(verify(file.path(), hash).is_ok());
        assert!(verify(file.path(), "").is_err());
        std::fs::write(file.path(), b"abcd").unwrap();
        assert!(verify(file.path(), hash).is_err());
        assert!(verify(Path::new("/missing-core"), hash).is_err());
        std::fs::write(file.path(), b"").unwrap();
        assert!(verify(
            file.path(),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        .is_ok());
    }
}
