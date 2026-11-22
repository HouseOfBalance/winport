use crate::{
    components::Component, containers::Container, library::Game, profiles::Profile, Result,
};
use serde::{Deserialize, Serialize};
use std::{fs, path::Path};

#[derive(Clone, Serialize, Deserialize)]
pub struct State {
    pub components: Vec<Component>,
    pub containers: Vec<Container>,
    pub profiles: Vec<Profile>,
    pub games: Vec<Game>,
}

impl Default for State {
    fn default() -> Self {
        Self {
            components: vec![],
            containers: vec![],
            profiles: Profile::presets(),
            games: vec![],
        }
    }
}

pub fn load(dir: &Path) -> Result<State> {
    fs::create_dir_all(dir)?;
    let path = dir.join("state.json");
    if path.exists() {
        Ok(serde_json::from_slice(&fs::read(path)?)?)
    } else {
        Ok(State::default())
    }
}

pub fn save(dir: &Path, state: &State) -> Result<()> {
    let temporary = dir.join("state.json.tmp");
    let file = fs::File::create(&temporary)?;
    serde_json::to_writer_pretty(&file, state)?;
    file.sync_all()?;
    fs::rename(temporary, dir.join("state.json"))?;
    Ok(())
}
