use crate::{Error, Result};
use serde::{Deserialize, Serialize};

#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct Profile {
    pub id: String,
    pub name: String,
    pub bigblock: u8,
    pub strongmem: u8,
    pub safeflags: u8,
    pub dxvk_hud: bool,
    pub fps_limit: u16,
    pub width: u16,
    pub height: u16,
    pub cpu_cores: Vec<usize>,
}

impl Profile {
    pub fn presets() -> Vec<Self> {
        [
            ("performance", "Performance", 3, 0, 0),
            ("balanced", "Balanced", 2, 1, 1),
            ("compatibility", "Compatibility", 0, 2, 2),
        ]
        .into_iter()
        .map(|(id, name, bigblock, strongmem, safeflags)| Self {
            id: id.into(),
            name: name.into(),
            bigblock,
            strongmem,
            safeflags,
            dxvk_hud: false,
            fps_limit: 60,
            width: 1280,
            height: 720,
            cpu_cores: vec![],
        })
        .collect()
    }
    pub fn validate(&self) -> Result<()> {
        if self.name.trim().is_empty()
            || self.bigblock > 3
            || self.strongmem > 2
            || self.safeflags > 2
            || self.width < 320
            || self.height < 200
            || self.fps_limit > 360
            || self.cpu_cores.iter().any(|c| *c >= 1024)
        {
            return Err(Error::Invalid("Invalid profile settings".into()));
        }
        Ok(())
    }
}
