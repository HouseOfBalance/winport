use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::{
    fs, io,
    path::{Component as PathPart, Path},
};

#[derive(Clone, Copy, Serialize, Deserialize, Debug, PartialEq, Eq)]
pub enum Kind {
    Box64,
    Wine,
    Dxvk,
    Rootfs,
}

impl Kind {
    pub fn directory(self) -> &'static str {
        match self {
            Self::Box64 => "box64",
            Self::Wine => "wine",
            Self::Dxvk => "dxvk",
            Self::Rootfs => "rootfs",
        }
    }
}

#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct Component {
    pub id: String,
    pub kind: Kind,
    pub version: String,
    pub path: String,
}

fn safe(path: &Path) -> Result<()> {
    if path.is_absolute()
        || path
            .components()
            .any(|p| !matches!(p, PathPart::Normal(_) | PathPart::CurDir))
    {
        return Err(Error::Invalid("Archive contains an unsafe path".into()));
    }
    Ok(())
}

fn untar(reader: impl io::Read, dest: &Path) -> Result<()> {
    for item in tar::Archive::new(reader).entries()? {
        let mut entry = item?;
        safe(&entry.path()?)?;
        let kind = entry.header().entry_type();
        if !kind.is_file() && !kind.is_dir() {
            return Err(Error::Invalid(
                "Archive links and special files are not supported; use a flattened archive".into(),
            ));
        }
        entry.unpack_in(dest)?;
    }
    Ok(())
}

pub fn install(archive: &Path, kind: Kind, data: &Path) -> Result<Component> {
    let filename = archive
        .file_name()
        .and_then(|s| s.to_str())
        .ok_or_else(|| Error::Invalid("Invalid archive filename".into()))?;
    let suffix = [".tar.xz", ".tar.gz", ".zip"]
        .into_iter()
        .find(|s| filename.ends_with(s))
        .ok_or_else(|| Error::Invalid("Expected .tar.xz, .tar.gz or .zip".into()))?;
    let version = filename.trim_end_matches(suffix);
    if version.is_empty()
        || !version
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "-_.".contains(c))
        || version == "."
        || version == ".."
    {
        return Err(Error::Invalid(
            "Archive basename must contain letters, numbers, dots, dashes or underscores".into(),
        ));
    }
    let base = data.join("components").join(kind.directory());
    fs::create_dir_all(&base)?;
    let dest = base.join(version);
    if dest.exists() {
        return Err(Error::Invalid(
            "Component version is already installed".into(),
        ));
    }
    let staging = base.join(format!(".import-{}", uuid::Uuid::new_v4()));
    fs::create_dir(&staging)?;
    let extract = (|| -> Result<()> {
        let file = fs::File::open(archive)?;
        match suffix {
            ".tar.xz" => untar(xz2::read::XzDecoder::new(file), &staging)?,
            ".tar.gz" => untar(flate2::read::GzDecoder::new(file), &staging)?,
            _ => {
                let mut zip = zip::ZipArchive::new(file)?;
                for i in 0..zip.len() {
                    let mut entry = zip.by_index(i)?;
                    let path = entry
                        .enclosed_name()
                        .ok_or_else(|| Error::Invalid("Unsafe ZIP path".into()))?;
                    safe(&path)?;
                    if entry
                        .unix_mode()
                        .is_some_and(|mode| mode & 0o170000 == 0o120000)
                    {
                        return Err(Error::Invalid("ZIP links are unsupported".into()));
                    }
                    let target = staging.join(path);
                    if entry.is_dir() {
                        fs::create_dir_all(target)?;
                    } else {
                        fs::create_dir_all(target.parent().unwrap())?;
                        io::copy(&mut entry, &mut fs::File::create(&target)?)?;
                        #[cfg(unix)]
                        {
                            use std::os::unix::fs::PermissionsExt;
                            fs::set_permissions(
                                target,
                                fs::Permissions::from_mode(
                                    entry.unix_mode().unwrap_or(0o644) & 0o777,
                                ),
                            )?;
                        }
                    }
                }
            }
        }
        fs::rename(&staging, &dest)?;
        Ok(())
    })();
    if extract.is_err() {
        let _ = fs::remove_dir_all(&staging);
    }
    extract?;
    Ok(Component {
        id: format!("{}:{version}", kind.directory()),
        kind,
        version: version.into(),
        path: dest.to_string_lossy().into(),
    })
}
