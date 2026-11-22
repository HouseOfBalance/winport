use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{collections::BTreeMap, fs, path::Path};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub enum System {
    PS1,
    PSP,
    PS2,
    PS3,
    PS4,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Game {
    #[serde(default)]
    pub document_key: String,
    pub uri: String,
    pub title: String,
    pub system: System,
    #[serde(default)]
    pub serial: String,
    #[serde(default)]
    pub art: String,
    #[serde(default)]
    pub folder: String,
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub resources: Vec<Resource>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Resource {
    pub uri: String,
    pub path: String,
}
#[derive(Default, Serialize, Deserialize)]
pub struct Library {
    #[serde(default)]
    pub games: Vec<Game>,
    #[serde(default)]
    pub folders: Vec<String>,
    #[serde(default)]
    pub prefs: Value,
}

fn u32le(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_le_bytes(
        b.get(at..at.checked_add(4)?)?.try_into().ok()?,
    ))
}
fn text(b: &[u8]) -> String {
    String::from_utf8_lossy(b)
        .trim_matches('\0')
        .trim()
        .to_owned()
}

pub fn sfo(b: &[u8]) -> Result<BTreeMap<String, String>, String> {
    if b.get(..4) != Some(b"\0PSF") {
        return Err("PARAM.SFO không hợp lệ".into());
    }
    let keys = u32le(b, 8).ok_or("SFO thiếu bảng khóa")? as usize;
    let data = u32le(b, 12).ok_or("SFO thiếu dữ liệu")? as usize;
    let count = u32le(b, 16).ok_or("SFO thiếu mục")? as usize;
    if count > 1024 {
        return Err("SFO quá nhiều mục".into());
    }
    let mut result = BTreeMap::new();
    for i in 0..count {
        let at = 20 + i * 16;
        let entry = b.get(at..at + 16).ok_or("SFO bị cắt")?;
        let key_at = keys
            .checked_add(u16::from_le_bytes([entry[0], entry[1]]) as usize)
            .ok_or("SFO tràn")?;
        let key_bytes = b.get(key_at..).ok_or("SFO khóa ngoài tệp")?;
        let end = key_bytes
            .iter()
            .position(|v| *v == 0)
            .ok_or("SFO khóa không kết thúc")?;
        let len = u32le(entry, 4).unwrap() as usize;
        let value_at = data
            .checked_add(u32le(entry, 12).unwrap() as usize)
            .ok_or("SFO tràn")?;
        let value = b
            .get(value_at..value_at.checked_add(len).ok_or("SFO tràn")?)
            .ok_or("SFO dữ liệu ngoài tệp")?;
        if entry[2] == 4 || entry[2] == 0 {
            result.insert(text(&key_bytes[..end]), text(value));
        }
    }
    Ok(result)
}

pub fn cnf(b: &[u8]) -> Option<(System, String)> {
    let value = String::from_utf8_lossy(b).to_uppercase();
    for line in value.lines() {
        let Some((key, path)) = line.split_once('=') else {
            continue;
        };
        let key = key.trim();
        if key != "BOOT" && key != "BOOT2" {
            continue;
        }
        let serial = path
            .trim()
            .rsplit(['\\', '/'])
            .next()?
            .split(';')
            .next()?
            .replace('_', "-")
            .replace('.', "");
        return Some((
            if key == "BOOT2" {
                System::PS2
            } else {
                System::PS1
            },
            serial,
        ));
    }
    None
}

#[derive(Deserialize)]
struct Chunk {
    offset: u64,
    bytes: Vec<u8>,
}
#[derive(Deserialize)]
struct Probe {
    name: String,
    #[serde(default)]
    bytes: Vec<u8>,
    #[serde(default)]
    chunks: Vec<Chunk>,
}
fn need(offset: u64, length: usize) -> Value {
    json!({"need":{"offset":offset,"length":length.min(65536)}})
}
pub(crate) fn probe_file(file: std::fs::File, name: String) -> Result<Value, String> {
    use std::io::{Read, Seek, SeekFrom};
    let mut source = crate::disc::open(file).map_err(|e| e.to_string())?;
    let mut head = Vec::new();
    source
        .by_ref()
        .take(131072)
        .read_to_end(&mut head)
        .map_err(|e| e.to_string())?;
    let mut chunks = Vec::new();
    for _ in 0..8 {
        let result = probe(Probe {
            name: name.clone(),
            bytes: head.clone(),
            chunks: chunks
                .iter()
                .map(|c: &Chunk| Chunk {
                    offset: c.offset,
                    bytes: c.bytes.clone(),
                })
                .collect(),
        })?;
        let Some(request) = result.get("need") else {
            return Ok(result);
        };
        let offset = request["offset"].as_u64().ok_or("Thiếu vị trí metadata")?;
        let length = request["length"]
            .as_u64()
            .ok_or("Thiếu độ dài metadata")?
            .min(65536);
        source
            .seek(SeekFrom::Start(offset))
            .map_err(|e| e.to_string())?;
        let mut bytes = Vec::new();
        source
            .by_ref()
            .take(length)
            .read_to_end(&mut bytes)
            .map_err(|e| e.to_string())?;
        chunks.push(Chunk { offset, bytes });
    }
    Err("Metadata đĩa vượt giới hạn đọc".into())
}
fn detection(system: System, title: &str, serial: &str) -> Value {
    json!({"system":system,"title":title,"serial":serial})
}
fn iso_entry(bytes: &[u8], wanted: &str) -> Result<Option<(u64, usize)>, String> {
    let mut pos = 0;
    while pos < bytes.len() {
        let n = bytes[pos] as usize;
        if n == 0 {
            pos = (pos / 2048 + 1) * 2048;
            continue;
        }
        let row = bytes.get(pos..pos + n).ok_or("ISO mục thư mục bị cắt")?;
        let len = *row.get(32).ok_or("ISO mục quá ngắn")? as usize;
        let name = text(row.get(33..33 + len).ok_or("ISO tên bị cắt")?).to_uppercase();
        if name.split(';').next() == Some(wanted) {
            return Ok(Some((
                u32le(row, 2).ok_or("ISO thiếu vị trí")? as u64 * 2048,
                u32le(row, 10).ok_or("ISO thiếu độ dài")? as usize,
            )));
        }
        pos += n;
    }
    Ok(None)
}

fn probe(p: Probe) -> Result<Value, String> {
    let b = &p.bytes;
    if b.is_empty() {
        return Ok(Value::Null);
    }
    let lower = p.name.to_lowercase();
    let title = p
        .name
        .rsplit('/')
        .next()
        .unwrap_or(&p.name)
        .rsplit_once('.')
        .map(|v| v.0)
        .unwrap_or(&p.name)
        .replace('_', " ");
    let ext = lower.rsplit('.').next().unwrap_or("");
    if lower.ends_with("param.sfo") {
        let fields = sfo(b)?;
        let serial = fields
            .get("TITLE_ID")
            .or(fields.get("DISC_ID"))
            .map(String::as_str)
            .unwrap_or("");
        let category = fields.get("CATEGORY").map(String::as_str).unwrap_or("");
        let system = if lower.contains("sce_sys/")
            || serial.starts_with("CUSA")
            || matches!(category, "gd" | "gp" | "ac")
        {
            System::PS4
        } else if lower.contains("ps3_game/") || matches!(category, "DG" | "HG" | "GD") {
            System::PS3
        } else if category == "ME" {
            System::PS1
        } else {
            System::PSP
        };
        return Ok(detection(
            system,
            fields.get("TITLE").map(String::as_str).unwrap_or(&title),
            serial,
        ));
    }
    if b.starts_with(b"\x7fCNT") {
        return Ok(detection(System::PS4, &title, ""));
    }
    if b.starts_with(b"\x7fPKG") {
        return Ok(detection(System::PS3, &title, ""));
    }
    if b.starts_with(b"PS-X EXE") {
        return Ok(detection(System::PS1, &title, ""));
    }
    if b.starts_with(b"\0PBP") {
        if b.len() < 40 {
            return Err("PBP thiếu header".into());
        }
        let start = u32le(b, 8).unwrap_or(0) as usize;
        let end = u32le(b, 12).unwrap_or(0) as usize;
        let fields = b
            .get(start..end)
            .and_then(|s| sfo(s).ok())
            .unwrap_or_default();
        let system = if fields.get("CATEGORY").map(String::as_str) == Some("ME")
            || b.windows(8).any(|s| s == b"PSISOIMG")
        {
            System::PS1
        } else {
            System::PSP
        };
        let mut result = detection(
            system,
            fields.get("TITLE").map(String::as_str).unwrap_or(&title),
            fields.get("DISC_ID").map(String::as_str).unwrap_or(""),
        );
        let icon_end = u32le(b, 16).unwrap_or(0) as usize;
        if end >= 40 && icon_end > end && icon_end - end <= 262144 {
            result["icon"] = json!({"offset":end,"length":icon_end-end});
        }
        return Ok(result);
    }
    if b.starts_with(b"\x7fELF") {
        let flags = u32le(b, 36).unwrap_or(0) & 0x00ff0000;
        if flags == 0x00a20000
            || b.windows(10).any(|s| s == b"sceDisplay")
            || b.windows(13).any(|s| s == b"sceModuleInfo")
        {
            return Ok(detection(System::PSP, &title, ""));
        }
        if flags == 0x00920000 {
            return Ok(detection(System::PS2, &title, ""));
        }
        return Ok(json!({"ambiguous":"ELF MIPS chưa rõ hệ máy; chọn thủ công"}));
    }
    if ext == "cue" || ext == "m3u" {
        return Ok(detection(System::PS1, &title, ""));
    }
    if b.get(32769..32774) == Some(b"CD001") {
        let root = u32le(b, 32926).unwrap_or(0) as u64 * 2048;
        let length = u32le(b, 32934).unwrap_or(0) as usize;
        if root == 0 || length == 0 {
            return Err("ISO có thư mục gốc không hợp lệ".into());
        }
        let Some(chunk) = p.chunks.iter().find(|c| c.offset == root) else {
            return Ok(need(root, length));
        };
        let mut pos = 0;
        while pos < chunk.bytes.len() {
            let n = chunk.bytes[pos] as usize;
            if n == 0 {
                pos = (pos / 2048 + 1) * 2048;
                continue;
            }
            let row = chunk
                .bytes
                .get(pos..pos + n)
                .ok_or("ISO bị cắt mục thư mục")?;
            let name_len = *row.get(32).ok_or("ISO mục quá ngắn")? as usize;
            let name = text(row.get(33..33 + name_len).ok_or("ISO tên bị cắt")?).to_uppercase();
            if name == "PSP_GAME" || name == "PS3_GAME" {
                let system = if name == "PSP_GAME" {
                    System::PSP
                } else {
                    System::PS3
                };
                let offset = u32le(row, 2).ok_or("ISO thiếu vị trí thư mục")? as u64 * 2048;
                let len = u32le(row, 10).ok_or("ISO thiếu độ dài thư mục")? as usize;
                let Some(folder) = p.chunks.iter().find(|c| c.offset == offset) else {
                    return Ok(need(offset, len));
                };
                if let Some((offset, len)) = iso_entry(&folder.bytes, "PARAM.SFO")? {
                    let Some(meta) = p.chunks.iter().find(|c| c.offset == offset) else {
                        return Ok(need(offset, len));
                    };
                    let fields = sfo(&meta.bytes)?;
                    return Ok(detection(
                        system,
                        fields.get("TITLE").unwrap_or(&title),
                        fields
                            .get("DISC_ID")
                            .or_else(|| fields.get("TITLE_ID"))
                            .map(String::as_str)
                            .unwrap_or(""),
                    ));
                }
                return Ok(detection(system, &title, ""));
            }
            if name.starts_with("SYSTEM.CNF") {
                let offset = u32le(row, 2).ok_or("ISO thiếu vị trí CNF")? as u64 * 2048;
                let len = u32le(row, 10).ok_or("ISO thiếu độ dài CNF")? as usize;
                let Some(cnf_bytes) = p.chunks.iter().find(|c| c.offset == offset) else {
                    return Ok(need(offset, len));
                };
                if let Some((sys, serial)) = cnf(&cnf_bytes.bytes) {
                    return Ok(detection(sys, &title, &serial));
                }
            }
            pos += n;
        }
    }
    if b.windows(8).any(|s| s == b"BOOT2 = ") || lower.ends_with("system.cnf") {
        if let Some((sys, serial)) = cnf(b) {
            return Ok(detection(sys, &title, &serial));
        }
    }
    if ext == "bin" {
        return Ok(detection(System::PS1, &title, ""));
    }
    if matches!(ext, "chd" | "cso" | "iso" | "pkg") {
        return Ok(
            json!({"ambiguous":"Ảnh đĩa/nén chưa rõ hệ máy; thêm thủ công để chọn PS1, PSP hoặc PS2"}),
        );
    }
    Ok(Value::Null)
}

fn normalized(s: &str) -> String {
    s.chars()
        .flat_map(char::to_lowercase)
        .filter(|c| c.is_alphanumeric())
        .collect()
}
fn distance(a: &str, b: &str) -> usize {
    let b: Vec<_> = b.chars().collect();
    let mut row: Vec<usize> = (0..=b.len()).collect();
    for (i, x) in a.chars().enumerate() {
        let mut prev = row[0];
        row[0] = i + 1;
        for (j, y) in b.iter().enumerate() {
            let old = row[j + 1];
            row[j + 1] = (row[j] + 1).min(old + 1).min(prev + usize::from(x != *y));
            prev = old;
        }
    }
    row[b.len()]
}

pub fn command(root: &Path, action: &str, payload: Value) -> Result<Value, String> {
    if action == "pspFiles" {
        let entry = payload["path"].as_str().ok_or("Thiếu đường dẫn PBP")?;
        let files = payload["files"].as_array().ok_or("Thiếu danh sách tệp")?;
        let safe = |path: &str| {
            !path.is_empty()
                && !path.contains(['\\', ':', '\0'])
                && path.split('/').all(|p| !matches!(p, "" | "." | ".."))
        };
        if !safe(entry) || !entry.to_lowercase().ends_with(".pbp") || files.len() > 20000 {
            return Err("Đường dẫn PBP hoặc danh sách tệp không hợp lệ".into());
        }
        let parent = entry
            .rsplit_once('/')
            .map(|(p, _)| format!("{p}/"))
            .unwrap_or_default();
        let mut required = std::collections::BTreeSet::new();
        for file in files {
            let path = file
                .as_str()
                .filter(|p| safe(p))
                .ok_or("Đường dẫn tệp đi kèm không an toàn")?;
            if path.starts_with(&parent) {
                required.insert(path);
            }
        }
        if !required.contains(entry) {
            return Err("Không tìm thấy PBP trong thư mục; hãy quét lại".into());
        }
        return Ok(json!(required));
    }
    if action == "documentKey" {
        let authority = payload["authority"]
            .as_str()
            .filter(|v| !v.is_empty())
            .ok_or("Thiếu nhà cung cấp tài liệu")?;
        let id = payload["id"]
            .as_str()
            .filter(|v| !v.is_empty())
            .ok_or("Thiếu mã tài liệu")?;
        let size = payload["size"]
            .as_i64()
            .filter(|v| *v >= -1)
            .ok_or("Kích thước tài liệu không hợp lệ")?;
        return Ok(Value::String(json!([authority, id, size]).to_string()));
    }
    if action == "coreManifest" {
        return Ok(crate::core_integrity::manifest());
    }
    if action == "verifyCore" {
        let abi = payload["abi"].as_str().ok_or("Thiếu ABI")?;
        let name = payload["name"].as_str().ok_or("Thiếu tên lõi")?;
        let manifest = crate::core_integrity::manifest();
        let pin = manifest
            .as_array()
            .unwrap()
            .iter()
            .find(|v| v["abi"] == abi && v["name"] == name)
            .ok_or("Lõi chưa được ghim cho thiết bị này")?;
        crate::core_integrity::verify(
            Path::new(payload["path"].as_str().ok_or("Thiếu đường dẫn lõi")?),
            pin["sha256"].as_str().unwrap(),
        )?;
        return Ok(json!({"verified":true}));
    }
    if action == "dependencies" {
        let content = payload["text"].as_str().ok_or("Thiếu nội dung danh sách")?;
        let cue = payload["name"]
            .as_str()
            .unwrap_or("")
            .to_lowercase()
            .ends_with(".cue");
        let mut paths = Vec::new();
        for line in content
            .lines()
            .map(str::trim)
            .filter(|s| !s.is_empty() && !s.starts_with('#'))
        {
            let path = if cue {
                if !line.to_uppercase().starts_with("FILE ") {
                    continue;
                }
                let rest = line[5..].trim();
                if let Some(rest) = rest.strip_prefix('"') {
                    rest.split('"').next().unwrap_or("")
                } else {
                    rest.split_whitespace().next().unwrap_or("")
                }
            } else {
                line
            };
            if path.is_empty()
                || path.contains(':')
                || path.starts_with('/')
                || path.starts_with('\\')
            {
                return Err("Đường dẫn đĩa phải tương đối".into());
            }
            paths.push(path.replace('\\', "/"));
        }
        return Ok(json!(paths));
    }
    if action == "probe" {
        return probe(serde_json::from_value(payload).map_err(|e| e.to_string())?);
    }
    if action == "matchArt" {
        let title = normalized(
            &payload["title"]
                .as_str()
                .unwrap_or("")
                .chars()
                .take(256)
                .collect::<String>(),
        );
        if title.is_empty() {
            return Ok(Value::Null);
        }
        let candidates = payload["names"].as_array().ok_or("Thiếu danh sách ảnh")?;
        let best = candidates
            .iter()
            .filter_map(Value::as_str)
            .filter(|s| s.len() < 512)
            .map(|s| {
                let base = s
                    .strip_suffix(".png")
                    .unwrap_or(s)
                    .split(" (")
                    .next()
                    .unwrap_or(s);
                (distance(&title, &normalized(base)), s)
            })
            .min_by_key(|v| v.0);
        return Ok(best
            .filter(|v| v.0 <= title.len().max(4) / 3)
            .map(|v| json!(v.1))
            .unwrap_or(Value::Null));
    }
    let path = root.join("station.json");
    let mut lib: Library = if path.exists() {
        serde_json::from_slice(&fs::read(&path).map_err(|e| e.to_string())?)
            .map_err(|e| format!("Thư viện lỗi: {e}"))?
    } else {
        Library::default()
    };
    lib.games.retain(|g| g.system != System::PS4);
    match action {
        "load" => return serde_json::to_value(lib).map_err(|e| e.to_string()),
        "folder" => {
            let uri = payload["uri"].as_str().ok_or("Thiếu thư mục")?.to_owned();
            if !lib.folders.contains(&uri) {
                lib.folders.push(uri);
            }
        }
        "forgetFolder" => lib
            .folders
            .retain(|s| Some(s.as_str()) != payload["uri"].as_str()),
        "upsert" | "batch" => {
            let games: Vec<Game> = if action == "batch" {
                serde_json::from_value(payload).map_err(|e| e.to_string())?
            } else {
                vec![serde_json::from_value(payload).map_err(|e| e.to_string())?]
            };
            for game in games {
                if game.title.trim().is_empty()
                    || game.title.chars().count() > 512
                    || !game.uri.starts_with("content://")
                {
                    return Err("Tên hoặc URI trò chơi không hợp lệ".into());
                }
                if game.system == System::PS4 {
                    continue;
                }
                if let Some(old) = lib.games.iter_mut().find(|v| {
                    v.uri == game.uri
                        || (!game.document_key.is_empty() && v.document_key == game.document_key)
                }) {
                    let art = old.art.clone();
                    *old = game;
                    if old.art.is_empty() {
                        old.art = art;
                    }
                } else {
                    lib.games.push(game);
                }
            }
            let mut keys = std::collections::BTreeSet::new();
            let mut uris = std::collections::BTreeSet::new();
            lib.games.retain(|g| {
                let key = if g.document_key.is_empty() {
                    format!("uri:{}", g.uri)
                } else {
                    g.document_key.clone()
                };
                uris.insert(g.uri.clone()) && keys.insert(key)
            });
        }
        "remove" => lib
            .games
            .retain(|s| Some(s.uri.as_str()) != payload["uri"].as_str()),
        "prefs" => {
            if !payload.is_object() {
                return Err("Thiết lập phải là đối tượng".into());
            }
            lib.prefs = payload;
        }
        "bios" => {
            let name = payload["name"].as_str().unwrap_or("").to_lowercase();
            let md5 = payload["md5"].as_str().unwrap_or("").to_lowercase();
            let expected = match name.as_str() {
                "scph5500.bin" => "8dd7d5296a650fac7319bce665a6a53c",
                "scph5501.bin" => "490f666e1afb15b7362b406ed1cea246",
                "scph5502.bin" => "32736f17079d0b2b7024407c39bd3050",
                _ => "",
            };
            return Ok(
                json!({"known":!expected.is_empty(),"valid":!expected.is_empty() && expected==md5,"expected":expected}),
            );
        }
        _ => return Err("Lệnh thư viện không hợp lệ".into()),
    }
    fs::create_dir_all(root).map_err(|e| e.to_string())?;
    let temp = root.join("station.json.part");
    fs::write(
        &temp,
        serde_json::to_vec_pretty(&lib).map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;
    fs::rename(temp, path).map_err(|e| e.to_string())?;
    serde_json::to_value(lib).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn psp_folder_files_keep_layout_and_exclude_neighbor_games() {
        let dir = tempfile::tempdir().unwrap();
        let files = json!([
            "PSP/GAME/Demo/EBOOT.PBP",
            "PSP/GAME/Demo/ID1/PAK0.PAK",
            "PSP/GAME/Demo/Kurok/PAK1.PAK",
            "PSP/GAME/Demo/MP3/01.mp3",
            "PSP/GAME/Demo/quake.cmdline",
            "PSP/GAME/Demo-backup/EBOOT.PBP"
        ]);
        let selected = command(
            dir.path(),
            "pspFiles",
            json!({"path":"PSP/GAME/Demo/EBOOT.PBP","files":files}),
        )
        .unwrap();
        assert_eq!(selected.as_array().unwrap().len(), 5);
        assert!(selected
            .as_array()
            .unwrap()
            .contains(&json!("PSP/GAME/Demo/ID1/PAK0.PAK")));
        assert!(!selected
            .as_array()
            .unwrap()
            .contains(&json!("PSP/GAME/Demo-backup/EBOOT.PBP")));
        assert_eq!(
            command(
                dir.path(),
                "pspFiles",
                json!({"path":"EBOOT.PBP","files":["EBOOT.PBP","data/a.bin"]})
            )
            .unwrap(),
            json!(["EBOOT.PBP", "data/a.bin"])
        );
    }
    #[test]
    fn psp_folder_files_reject_empty_missing_and_unsafe_paths() {
        let dir = tempfile::tempdir().unwrap();
        for payload in [
            json!({}),
            json!({"path":"EBOOT.PBP","files":[]}),
            json!({"path":"EBOOT.PBP","files":["other.PBP"]}),
            json!({"path":"../EBOOT.PBP","files":["../EBOOT.PBP"]}),
            json!({"path":"/EBOOT.PBP","files":[]}),
            json!({"path":"EBOOT.PBP","files":["data/../secret"]}),
            json!({"path":"EBOOT.PBP","files":["C:/secret"]}),
            json!({"path":"EBOOT.PBP","files":["data\\secret"]}),
            json!({"path":"EBOOT.PBP","files":[null]}),
        ] {
            assert!(command(dir.path(), "pspFiles", payload).is_err());
        }
    }
    #[test]
    fn pbp_title_and_bounded_embedded_icon() {
        let mut metadata = b"\0PSF".to_vec();
        for value in [0x101u32, 36, 42, 1] {
            metadata.extend(value.to_le_bytes());
        }
        metadata.extend(0u16.to_le_bytes());
        metadata.extend(0x0204u16.to_le_bytes());
        for value in [13u32, 13, 0] {
            metadata.extend(value.to_le_bytes());
        }
        metadata.extend(b"TITLE\0Original PSP\0");
        let icon_offset = 40 + metadata.len() as u32;
        let mut pbp = b"\0PBP".to_vec();
        for value in [
            0x10000u32,
            40,
            icon_offset,
            icon_offset + 8,
            icon_offset + 8,
            icon_offset + 8,
            icon_offset + 8,
            icon_offset + 8,
            icon_offset + 8,
        ] {
            pbp.extend(value.to_le_bytes());
        }
        pbp.extend(metadata);
        pbp.extend(b"\x89PNG\r\n\x1a\n");
        let check = |bytes| {
            probe(Probe {
                name: "EBOOT.PBP".into(),
                bytes,
                chunks: vec![],
            })
            .unwrap()
        };
        let result = check(pbp.clone());
        assert_eq!(result["title"], "Original PSP");
        assert_eq!(result["system"], "PSP");
        assert_eq!(result["icon"], json!({"offset":icon_offset,"length":8}));
        for invalid_end in [0, icon_offset, icon_offset - 1, icon_offset + 262145] {
            pbp[16..20].copy_from_slice(&invalid_end.to_le_bytes());
            assert!(check(pbp.clone()).get("icon").is_none());
        }
        assert!(probe(Probe {
            name: "EBOOT.PBP".into(),
            bytes: b"\0PBP".to_vec(),
            chunks: vec![]
        })
        .is_err());
    }
    #[test]
    fn sfo_title_serial_and_bounds() {
        let fields = [
            ("TITLE", "Original PS4"),
            ("TITLE_ID", "CUSA00000"),
            ("CATEGORY", "gd"),
        ];
        let mut keys = Vec::new();
        let mut data = Vec::new();
        let mut rows = Vec::new();
        for (key, value) in fields {
            let ko = keys.len() as u16;
            keys.extend_from_slice(key.as_bytes());
            keys.push(0);
            rows.extend_from_slice(&ko.to_le_bytes());
            rows.extend_from_slice(&0x0204u16.to_le_bytes());
            rows.extend_from_slice(&((value.len() + 1) as u32).to_le_bytes());
            rows.extend_from_slice(&((value.len() + 1) as u32).to_le_bytes());
            rows.extend_from_slice(&(data.len() as u32).to_le_bytes());
            data.extend_from_slice(value.as_bytes());
            data.push(0);
        }
        let mut b = b"\0PSF".to_vec();
        b.extend_from_slice(&0x101u32.to_le_bytes());
        b.extend_from_slice(&68u32.to_le_bytes());
        b.extend_from_slice(&(68u32 + keys.len() as u32).to_le_bytes());
        b.extend_from_slice(&3u32.to_le_bytes());
        b.extend(rows);
        b.extend(keys);
        b.extend(data);
        let parsed = sfo(&b).unwrap();
        assert_eq!(parsed["TITLE"], "Original PS4");
        let result = probe(Probe {
            name: "Demo/sce_sys/param.sfo".into(),
            bytes: b.clone(),
            chunks: vec![],
        })
        .unwrap();
        assert_eq!(result["system"], "PS4");
        assert_eq!(result["serial"], "CUSA00000");
        b[32..36].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(sfo(&b).is_err());
    }
    #[test]
    fn iso_boot2_requests_and_malformed_records() {
        let mut b = vec![0; 33000];
        b[32769..32774].copy_from_slice(b"CD001");
        b[32926..32930].copy_from_slice(&20u32.to_le_bytes());
        b[32934..32938].copy_from_slice(&46u32.to_le_bytes());
        let mut root = vec![0; 46];
        root[0] = 46;
        root[2..6].copy_from_slice(&50u32.to_le_bytes());
        root[10..14].copy_from_slice(&80u32.to_le_bytes());
        root[32] = 12;
        root[33..45].copy_from_slice(b"SYSTEM.CNF;1");
        let make = |chunks| Probe {
            name: "game.iso".into(),
            bytes: b.clone(),
            chunks,
        };
        assert_eq!(probe(make(vec![])).unwrap()["need"]["offset"], 40960);
        let mut chunks = vec![Chunk {
            offset: 40960,
            bytes: root,
        }];
        assert_eq!(
            probe(make(vec![Chunk {
                offset: 40960,
                bytes: chunks[0].bytes.clone()
            }]))
            .unwrap()["need"]["offset"],
            102400
        );
        chunks.push(Chunk {
            offset: 102400,
            bytes: b"# generated\nBOOT2 = cdrom0:\\SLUS_123.45;1".to_vec(),
        });
        let result = probe(make(chunks)).unwrap();
        assert_eq!(result["system"], "PS2");
        assert_eq!(result["serial"], "SLUS-12345");
        assert!(probe(make(vec![Chunk {
            offset: 40960,
            bytes: vec![2, 0]
        }]))
        .is_err());
    }
    #[test]
    fn signatures_empty_and_malformed() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(
            command(
                dir.path(),
                "probe",
                json!({"name":"test.exe","bytes":b"PS-X EXE"})
            )
            .unwrap()["system"],
            "PS1"
        );
        for (flags, sys) in [(0x10a23001u32, "PSP"), (0x20924001, "PS2")] {
            let mut b = vec![0; 40];
            b[..4].copy_from_slice(b"\x7fELF");
            b[36..40].copy_from_slice(&flags.to_le_bytes());
            assert_eq!(
                command(dir.path(), "probe", json!({"name":"x.elf","bytes":b})).unwrap()["system"],
                sys
            );
        }
        assert!(
            command(dir.path(), "probe", json!({"name":"empty","bytes":[]}))
                .unwrap()
                .is_null()
        );
        assert!(sfo(&[]).is_err());
        assert!(sfo(b"\0PSF").is_err());
        assert_eq!(
            cnf(b"BOOT2 = cdrom0:\\SLUS_123.45;1").unwrap(),
            (System::PS2, "SLUS-12345".into())
        );
        assert!(cnf(b"bad").is_none());
    }
    #[test]
    fn iso_psp_sfo_title_and_invalid_directory() {
        use std::io::{Seek, Write};
        let mut image = vec![0; 131072];
        image[32769..32774].copy_from_slice(b"CD001");
        image[32926..32930].copy_from_slice(&20u32.to_le_bytes());
        image[32934..32938].copy_from_slice(&46u32.to_le_bytes());
        let root = &mut image[40960..41006];
        root[0] = 46;
        root[2..6].copy_from_slice(&30u32.to_le_bytes());
        root[10..14].copy_from_slice(&46u32.to_le_bytes());
        root[32] = 8;
        root[33..41].copy_from_slice(b"PSP_GAME");
        let folder = &mut image[61440..61486];
        folder[0] = 46;
        folder[2..6].copy_from_slice(&40u32.to_le_bytes());
        folder[10..14].copy_from_slice(&51u32.to_le_bytes());
        folder[32] = 11;
        folder[33..44].copy_from_slice(b"PARAM.SFO;1");
        let mut meta = b"\0PSF".to_vec();
        for value in [0x101u32, 36, 42, 1] {
            meta.extend_from_slice(&value.to_le_bytes());
        }
        meta.extend_from_slice(&0u16.to_le_bytes());
        meta.extend_from_slice(&0x204u16.to_le_bytes());
        for value in [9u32, 9, 0] {
            meta.extend_from_slice(&value.to_le_bytes());
        }
        meta.extend_from_slice(b"TITLE\0Original\0");
        image[81920..81920 + meta.len()].copy_from_slice(&meta);
        let mut file = tempfile::tempfile().unwrap();
        file.write_all(&image).unwrap();
        file.rewind().unwrap();
        let result = probe_file(file, "test.iso".into()).unwrap();
        assert_eq!(result["system"], "PSP");
        assert_eq!(result["title"], "Original");
        assert!(iso_entry(&[2, 0], "PARAM.SFO").is_err());
        assert_eq!(iso_entry(&[], "PARAM.SFO").unwrap(), None);
    }
    #[test]
    fn persistence_empty_error_and_fuzzy() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(
            command(dir.path(), "load", json!({})).unwrap()["games"],
            json!([])
        );
        let game = json!({"title":"Original","uri":"content://test/1","system":"PS3"});
        command(dir.path(), "upsert", game.clone()).unwrap();
        command(dir.path(), "upsert", game).unwrap();
        assert_eq!(
            command(dir.path(), "load", json!({})).unwrap()["games"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        assert!(command(dir.path(), "upsert", json!({})).is_err());
        command(dir.path(), "batch", json!([])).unwrap();
        assert!(command(
            dir.path(),
            "batch",
            json!([{"title":"","uri":"content://bad","system":"PS1"}])
        )
        .is_err());
        command(
            dir.path(),
            "batch",
            json!([{"title":"PSP","uri":"content://psp/1","system":"PSP"}]),
        )
        .unwrap();
        assert_eq!(
            command(dir.path(), "load", json!({})).unwrap()["games"]
                .as_array()
                .unwrap()
                .len(),
            2
        );
        assert_eq!(command(dir.path(),"matchArt",json!({"title":"Crash Bandicot","names":["Crash Bandicoot (USA).png","Unrelated.png"]})).unwrap(),"Crash Bandicoot (USA).png");
        assert!(
            command(dir.path(), "matchArt", json!({"title":"","names":[]}))
                .unwrap()
                .is_null()
        );
        assert_eq!(
            command(
                dir.path(),
                "bios",
                json!({"name":"scph5501.bin","md5":"wrong"})
            )
            .unwrap()["valid"],
            false
        );
    }
    #[test]
    fn overlapping_document_identity_and_hidden_system() {
        let dir = tempfile::tempdir().unwrap();
        let key = command(
            dir.path(),
            "documentKey",
            json!({"authority":"provider","id":"primary:Games/a.elf","size":42}),
        )
        .unwrap();
        let game = |uri: &str, size_key: &Value| json!({"uri":uri,"title":"Demo","system":"PSP","document_key":size_key});
        command(
            dir.path(),
            "upsert",
            game("content://provider/tree/a/document/game", &key),
        )
        .unwrap();
        command(
            dir.path(),
            "upsert",
            game("content://provider/tree/b/document/game", &key),
        )
        .unwrap();
        assert_eq!(
            command(dir.path(), "load", json!({})).unwrap()["games"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        let changed = command(
            dir.path(),
            "documentKey",
            json!({"authority":"provider","id":"primary:Games/a.elf","size":43}),
        )
        .unwrap();
        assert_ne!(key, changed);
        command(
            dir.path(),
            "upsert",
            game("content://provider/tree/c/document/game", &changed),
        )
        .unwrap();
        assert_eq!(
            command(dir.path(), "load", json!({})).unwrap()["games"]
                .as_array()
                .unwrap()
                .len(),
            2
        );
        assert!(command(
            dir.path(),
            "documentKey",
            json!({"authority":"","id":"","size":-2})
        )
        .is_err());
        command(dir.path(), "batch", json!([])).unwrap();
        command(
            dir.path(),
            "upsert",
            json!({"uri":"content://hidden","title":"Hidden","system":"PS4"}),
        )
        .unwrap();
        assert_eq!(
            command(dir.path(), "load", json!({})).unwrap()["games"]
                .as_array()
                .unwrap()
                .len(),
            2
        );
    }
}
