use std::{
    fs::File,
    io::{self, Read, Seek, SeekFrom},
};

pub(crate) trait ReadSeek: Read + Seek {}
impl<T: Read + Seek> ReadSeek for T {}
fn invalid(message: impl ToString) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message.to_string())
}
fn position(current: u64, length: u64, from: SeekFrom) -> io::Result<u64> {
    let next = match from {
        SeekFrom::Start(n) => n as i128,
        SeekFrom::Current(n) => current as i128 + n as i128,
        SeekFrom::End(n) => length as i128 + n as i128,
    };
    if !(0..=u64::MAX as i128).contains(&next) {
        return Err(invalid("Vị trí đĩa không hợp lệ"));
    }
    Ok(next as u64)
}

struct Cso<R> {
    source: R,
    total: u64,
    block: u32,
    shift: u8,
    header: u32,
    pos: u64,
    cached: u64,
    buffer: Vec<u8>,
}
impl<R: Read + Seek> Cso<R> {
    fn open(mut source: R) -> io::Result<Self> {
        let mut header = [0; 24];
        source.seek(SeekFrom::Start(0))?;
        source.read_exact(&mut header)?;
        let total = u64::from_le_bytes(header[8..16].try_into().unwrap());
        let block = u32::from_le_bytes(header[16..20].try_into().unwrap());
        let size = u32::from_le_bytes(header[4..8].try_into().unwrap());
        if &header[..4] != b"CISO"
            || size < 24
            || total == 0
            || total > 32_000_000_000
            || !(2048..=1048576).contains(&block)
            || header[20] != 1
            || header[21] > 20
        {
            return Err(invalid("CSO không hỗ trợ hoặc header hỏng (cần CISO v1)"));
        }
        Ok(Self {
            source,
            total,
            block,
            shift: header[21],
            header: size,
            pos: 0,
            cached: u64::MAX,
            buffer: Vec::new(),
        })
    }
    fn load(&mut self, index: u64) -> io::Result<()> {
        if self.cached == index {
            return Ok(());
        }
        self.source
            .seek(SeekFrom::Start(self.header as u64 + index * 4))?;
        let mut values = [0; 8];
        self.source.read_exact(&mut values)?;
        let first = u32::from_le_bytes(values[..4].try_into().unwrap());
        let last = u32::from_le_bytes(values[4..].try_into().unwrap());
        let start = ((first & 0x7fffffff) as u64) << self.shift;
        let end = ((last & 0x7fffffff) as u64) << self.shift;
        let length = end
            .checked_sub(start)
            .ok_or_else(|| invalid("CSO chỉ mục đảo ngược"))?;
        if length == 0 || length > 8_000_000 {
            return Err(invalid("CSO khối quá lớn hoặc rỗng"));
        }
        self.source.seek(SeekFrom::Start(start))?;
        let expected = (self.total - index * self.block as u64).min(self.block as u64) as usize;
        if first & 0x80000000 != 0 && length < expected as u64 {
            return Err(invalid("CSO khối thô bị cắt"));
        }
        self.buffer.clear();
        if first & 0x80000000 != 0 {
            self.buffer.resize(expected, 0);
            self.source.read_exact(&mut self.buffer)?;
        } else {
            let compressed = self.source.by_ref().take(length);
            flate2::read::DeflateDecoder::new(compressed)
                .take(self.block as u64 + 1)
                .read_to_end(&mut self.buffer)?;
            if self.buffer.len() != expected {
                return Err(invalid("CSO giải nén sai kích thước"));
            }
        }
        self.cached = index;
        Ok(())
    }
}
impl<R: Read + Seek> Read for Cso<R> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if out.is_empty() || self.pos >= self.total {
            return Ok(0);
        }
        let index = self.pos / self.block as u64;
        self.load(index)?;
        let at = (self.pos % self.block as u64) as usize;
        let n = out.len().min(self.buffer.len() - at);
        out[..n].copy_from_slice(&self.buffer[at..at + n]);
        self.pos += n as u64;
        Ok(n)
    }
}
impl<R: Read + Seek> Seek for Cso<R> {
    fn seek(&mut self, from: SeekFrom) -> io::Result<u64> {
        self.pos = position(self.pos, self.total, from)?;
        Ok(self.pos)
    }
}

struct ChdRaw {
    image: chd::Chd<File>,
    total: u64,
    pos: u64,
    cached: u64,
    buffer: Vec<u8>,
    compressed: Vec<u8>,
    reads: u32,
}
impl Read for ChdRaw {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if out.is_empty() || self.pos >= self.total {
            return Ok(0);
        }
        let size = self.buffer.len() as u64;
        let index = self.pos / size;
        if self.cached != index {
            self.reads += 1;
            if self.reads > 256 {
                return Err(invalid("CHD vượt giới hạn đọc metadata"));
            }
            self.image
                .hunk(index as u32)
                .map_err(invalid)?
                .read_hunk_in(&mut self.compressed, &mut self.buffer)
                .map_err(invalid)?;
            self.cached = index;
        }
        let at = (self.pos % size) as usize;
        let n = out
            .len()
            .min(self.buffer.len() - at)
            .min((self.total - self.pos) as usize);
        out[..n].copy_from_slice(&self.buffer[at..at + n]);
        self.pos += n as u64;
        Ok(n)
    }
}
impl Seek for ChdRaw {
    fn seek(&mut self, from: SeekFrom) -> io::Result<u64> {
        self.pos = position(self.pos, self.total, from)?;
        Ok(self.pos)
    }
}

struct Sectors {
    source: Box<dyn ReadSeek>,
    unit: u64,
    offset: u64,
    start: u64,
    total: u64,
    pos: u64,
}
impl Read for Sectors {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if out.is_empty() || self.pos >= self.total {
            return Ok(0);
        }
        let sector = self.pos / 2048;
        let within = self.pos % 2048;
        self.source.seek(SeekFrom::Start(
            (self.start + sector) * self.unit + self.offset + within,
        ))?;
        let n = out
            .len()
            .min((2048 - within) as usize)
            .min((self.total - self.pos) as usize);
        let read = self.source.read(&mut out[..n])?;
        self.pos += read as u64;
        Ok(read)
    }
}
impl Seek for Sectors {
    fn seek(&mut self, from: SeekFrom) -> io::Result<u64> {
        self.pos = position(self.pos, self.total, from)?;
        Ok(self.pos)
    }
}

pub(crate) fn open(mut file: File) -> io::Result<Box<dyn ReadSeek>> {
    let mut magic = [0; 8];
    let n = file.read(&mut magic)?;
    file.seek(SeekFrom::Start(0))?;
    if n >= 4 && &magic[..4] == b"CISO" {
        return Ok(Box::new(Cso::open(file)?));
    }
    let (mut source, unit): (Box<dyn ReadSeek>, u64) = if &magic == b"MComprHD" {
        let mut header = [0; 64];
        file.read_exact(&mut header)?;
        if u32::from_be_bytes(header[12..16].try_into().unwrap()) == 5 {
            let hunk = u32::from_be_bytes(header[56..60].try_into().unwrap());
            let logical = u64::from_be_bytes(header[32..40].try_into().unwrap());
            if !(2048..=2097152).contains(&hunk) || logical / hunk as u64 > 2_000_000 {
                return Err(invalid("CHD vượt giới hạn bộ nhớ metadata"));
            }
            let offset = u64::from_be_bytes(header[40..48].try_into().unwrap());
            file.seek(SeekFrom::Start(offset))?;
            let mut map = [0; 4];
            file.read_exact(&mut map)?;
            if u32::from_be_bytes(map) > 32_000_000 {
                return Err(invalid("CHD bảng khối vượt giới hạn metadata"));
            }
        } else {
            return Err(invalid(
                "Quét CHD cần định dạng v5; bản cũ có thể thêm thủ công",
            ));
        }
        file.seek(SeekFrom::Start(0))?;
        let image = chd::Chd::open(file, None).map_err(invalid)?;
        let size = image.header().hunk_size();
        let total = image.header().logical_bytes();
        let unit = image.header().unit_bytes() as u64;
        if !(2048..=2097152).contains(&size) || !matches!(unit, 2048 | 2352 | 2448) {
            return Err(invalid("CHD không phải đĩa PlayStation được hỗ trợ"));
        }
        let raw = ChdRaw {
            image,
            total,
            pos: 0,
            cached: u64::MAX,
            buffer: vec![0; size as usize],
            compressed: vec![],
            reads: 0,
        };
        (Box::new(raw), unit)
    } else {
        let mut marker = [0; 6];
        let mut unit = 2048;
        for candidate in [2352u64, 2448] {
            for offset in [16, 24] {
                file.seek(SeekFrom::Start(candidate * 16 + offset))?;
                if file.read_exact(&mut marker).is_ok() && &marker[1..] == b"CD001" {
                    unit = candidate;
                }
            }
        }
        (Box::new(file), unit)
    };
    let length = source.seek(SeekFrom::End(0))?;
    if unit == 2048 {
        source.seek(SeekFrom::Start(0))?;
        return Ok(source);
    }
    for sector in 16..=166 {
        for offset in [0, 16, 24] {
            source.seek(SeekFrom::Start(sector * unit + offset))?;
            let mut marker = [0; 6];
            if source.read_exact(&mut marker).is_ok() && &marker[1..] == b"CD001" {
                let start = sector - 16;
                return Ok(Box::new(Sectors {
                    source,
                    unit,
                    offset,
                    start,
                    total: (length / unit).saturating_sub(start) * 2048,
                    pos: 0,
                }));
            }
        }
    }
    source.seek(SeekFrom::Start(0))?;
    Ok(source)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Write};
    fn iso() -> Vec<u8> {
        let mut b = vec![0; 64 * 2048];
        b[32768] = 1;
        b[32769..32774].copy_from_slice(b"CD001");
        b[32926..32930].copy_from_slice(&20u32.to_le_bytes());
        b[32934..32938].copy_from_slice(&46u32.to_le_bytes());
        let row = &mut b[40960..41006];
        row[0] = 46;
        row[2..6].copy_from_slice(&50u32.to_le_bytes());
        row[10..14].copy_from_slice(&80u32.to_le_bytes());
        row[32] = 12;
        row[33..45].copy_from_slice(b"SYSTEM.CNF;1");
        let cnf = b"BOOT2 = cdrom0:\\SLUS_123.45;1\n";
        b[102400..102400 + cnf.len()].copy_from_slice(cnf);
        b
    }
    fn probe(bytes: &[u8], name: &str) -> serde_json::Value {
        let mut file = tempfile::tempfile().unwrap();
        file.write_all(bytes).unwrap();
        file.rewind().unwrap();
        crate::station::probe_file(file, name.into()).unwrap()
    }
    #[test]
    fn chd_v5_and_cso_system_cnf_metadata() {
        let image = iso();
        let mut chd = vec![0; 2048];
        chd[..8].copy_from_slice(b"MComprHD");
        chd[8..12].copy_from_slice(&124u32.to_be_bytes());
        chd[12..16].copy_from_slice(&5u32.to_be_bytes());
        chd[32..40].copy_from_slice(&(image.len() as u64).to_be_bytes());
        chd[40..48].copy_from_slice(&124u64.to_be_bytes());
        chd[56..60].copy_from_slice(&2048u32.to_be_bytes());
        chd[60..64].copy_from_slice(&2048u32.to_be_bytes());
        for index in 0..64 {
            chd[124 + index * 4..128 + index * 4]
                .copy_from_slice(&(index as u32 + 1).to_be_bytes());
        }
        chd.extend_from_slice(&image);
        assert_eq!(probe(&chd, "original.chd")["serial"], "SLUS-12345");
        let mut cso = b"CISO".to_vec();
        cso.extend_from_slice(&24u32.to_le_bytes());
        cso.extend_from_slice(&(image.len() as u64).to_le_bytes());
        cso.extend_from_slice(&2048u32.to_le_bytes());
        cso.extend_from_slice(&[1, 0, 0, 0]);
        let mut blocks = Vec::new();
        let mut offset = 24 + 65 * 4;
        for raw in image.chunks(2048) {
            let mut encoder =
                flate2::write::DeflateEncoder::new(vec![], flate2::Compression::fast());
            encoder.write_all(raw).unwrap();
            let bytes = encoder.finish().unwrap();
            cso.extend_from_slice(&(offset as u32).to_le_bytes());
            offset += bytes.len();
            blocks.extend(bytes);
        }
        cso.extend_from_slice(&(offset as u32).to_le_bytes());
        cso.extend(blocks);
        assert_eq!(probe(&cso, "original.cso")["system"], "PS2");
        assert_eq!(probe(&image, "original.iso")["system"], "PS2");
        assert!(probe(&[], "empty.iso").is_null());
    }
    #[test]
    fn cso_raw_deflate_empty_and_invalid() {
        let payload = vec![42; 4096];
        let mut compressed =
            flate2::write::DeflateEncoder::new(vec![], flate2::Compression::fast());
        compressed.write_all(&payload[2048..]).unwrap();
        let compressed = compressed.finish().unwrap();
        let mut b = b"CISO".to_vec();
        b.extend_from_slice(&24u32.to_le_bytes());
        b.extend_from_slice(&4096u64.to_le_bytes());
        b.extend_from_slice(&2048u32.to_le_bytes());
        b.extend_from_slice(&[1, 0, 0, 0]);
        b.extend_from_slice(&(36u32 | 0x80000000).to_le_bytes());
        b.extend_from_slice(&2084u32.to_le_bytes());
        b.extend_from_slice(&(2084u32 + compressed.len() as u32).to_le_bytes());
        b.extend_from_slice(&payload[..2048]);
        b.extend(compressed);
        let mut cso = Cso::open(Cursor::new(b)).unwrap();
        let mut result = vec![];
        cso.read_to_end(&mut result).unwrap();
        assert_eq!(result, payload);
        cso.seek(SeekFrom::Start(2045)).unwrap();
        let mut crossing = [0; 10];
        cso.read_exact(&mut crossing).unwrap();
        assert_eq!(crossing, [42; 10]);
        assert!(Cso::open(Cursor::new(vec![])).is_err());
        assert!(cso.seek(SeekFrom::Current(-100000)).is_err());
    }
    #[test]
    fn chd_bad_header_is_rejected() {
        let mut file = tempfile::tempfile().unwrap();
        file.write_all(b"MComprHD").unwrap();
        file.rewind().unwrap();
        assert!(open(file).is_err());
    }
}
