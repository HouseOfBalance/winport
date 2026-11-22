pub mod components;
pub mod containers;
mod core_integrity;
mod disc;
pub mod jni;
pub mod launch;
pub mod library;
pub mod libretro;
pub mod profiles;
mod retro_hw;
mod retro_jni;
pub mod station;
mod station_jni;
pub mod storage;

use components::{Component, Kind};
use containers::Container;
use library::Game;
use profiles::Profile;
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    process::Child,
};

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Invalid(String),
    #[error("Missing component: {0}")]
    Missing(String),
    #[error("{0}")]
    Io(#[from] std::io::Error),
    #[error("{0}")]
    Json(#[from] serde_json::Error),
    #[error("{0}")]
    Zip(#[from] zip::result::ZipError),
}
pub type Result<T> = std::result::Result<T, Error>;

pub struct Core {
    pub data_dir: PathBuf,
    pub state: storage::State,
    children: HashMap<u32, Child>,
}

impl Core {
    pub fn init(dir: impl AsRef<Path>) -> Result<Self> {
        let data_dir = dir.as_ref().to_path_buf();
        let state = storage::load(&data_dir)?;
        storage::save(&data_dir, &state)?;
        Ok(Self {
            data_dir,
            state,
            children: HashMap::new(),
        })
    }
    fn persist(&mut self, state: storage::State) -> Result<()> {
        storage::save(&self.data_dir, &state)?;
        self.state = state;
        Ok(())
    }
    pub fn install_component(&mut self, path: &Path, kind: Kind) -> Result<Component> {
        let component = components::install(path, kind, &self.data_dir)?;
        let mut state = self.state.clone();
        state.components.push(component.clone());
        if let Err(e) = self.persist(state) {
            let _ = fs::remove_dir_all(&component.path);
            return Err(e);
        }
        Ok(component)
    }
    pub fn build_launch_plan(&self, id: &str) -> Result<launch::LaunchPlan> {
        let game = self
            .state
            .games
            .iter()
            .find(|g| g.id == id)
            .ok_or_else(|| Error::Invalid("Game not found".into()))?;
        let container = self
            .state
            .containers
            .iter()
            .find(|c| c.id == game.container_id)
            .ok_or_else(|| Error::Invalid("Container not found".into()))?;
        let profile = self
            .state
            .profiles
            .iter()
            .find(|p| p.id == game.profile_id)
            .ok_or_else(|| Error::Invalid("Profile not found".into()))?;
        launch::build(game, container, profile, &self.state.components)
    }
    pub fn launch(&mut self, id: &str) -> Result<u32> {
        self.children
            .retain(|_, child| matches!(child.try_wait(), Ok(None)));
        let child = launch::spawn(&self.build_launch_plan(id)?)?;
        let pid = child.id();
        self.children.insert(pid, child);
        Ok(pid)
    }
    pub fn stop(&mut self, pid: u32) -> Result<()> {
        let child = self.children.get_mut(&pid).ok_or_else(|| {
            Error::Invalid("PID is not a process launched by this session".into())
        })?;
        if child.try_wait()?.is_none() {
            if unsafe { libc::kill(-(pid as i32), libc::SIGKILL) } != 0 {
                return Err(std::io::Error::last_os_error().into());
            }
            child.wait()?;
        }
        self.children.remove(&pid);
        Ok(())
    }
    pub fn dispatch(&mut self, method: &str, value: Value) -> Result<Value> {
        let field = |key: &str| {
            value
                .get(key)
                .and_then(Value::as_str)
                .map(String::from)
                .ok_or_else(|| Error::Invalid(format!("Missing {key}")))
        };
        let mut state = self.state.clone();
        match method {
            "snapshot" => return Ok(serde_json::to_value(&self.state)?),
            "listGames" => return Ok(json!(self.state.games)),
            "listProfiles" => return Ok(json!(self.state.profiles)),
            "listContainers" => return Ok(json!(self.state.containers)),
            "listComponents" => return Ok(json!(self.state.components)),
            "installComponent" => {
                return Ok(json!(self.install_component(
                    Path::new(&field("archive_path")?),
                    serde_json::from_value(value["kind"].clone())?
                )?))
            }
            "buildLaunchPlan" => return Ok(json!(self.build_launch_plan(&field("id")?)?)),
            "launch" => return Ok(json!({"pid": self.launch(&field("id")?)?})),
            "stop" => {
                self.stop(
                    value["pid"]
                        .as_u64()
                        .filter(|n| *n <= u32::MAX as u64)
                        .ok_or_else(|| Error::Invalid("Invalid PID".into()))?
                        as u32,
                )?;
                return Ok(json!({}));
            }
            "addGame" | "updateGame" => {
                let mut game: Game = serde_json::from_value(value)?;
                if game.name.trim().is_empty() {
                    return Err(Error::Invalid("Game name is required".into()));
                }
                let container = state
                    .containers
                    .iter()
                    .find(|c| c.id == game.container_id)
                    .ok_or_else(|| Error::Invalid("Select an existing container".into()))?;
                if !state.profiles.iter().any(|p| p.id == game.profile_id) {
                    return Err(Error::Invalid("Select an existing profile".into()));
                }
                let path = Path::new(&game.exe_path);
                if path.is_absolute()
                    || path.components().any(|c| {
                        !matches!(
                            c,
                            std::path::Component::Normal(_) | std::path::Component::CurDir
                        )
                    })
                    || !game.exe_path.to_lowercase().ends_with(".exe")
                {
                    return Err(Error::Invalid(
                        "Use a relative .exe path inside the container".into(),
                    ));
                }
                let _ = container;
                if method == "addGame" {
                    game.id = uuid::Uuid::new_v4().to_string();
                } else if !state.games.iter().any(|g| g.id == game.id) {
                    return Err(Error::Invalid("Game not found".into()));
                }
                state.games.retain(|g| g.id != game.id);
                state.games.push(game);
            }
            "removeGame" => {
                let id = field("id")?;
                state.games.retain(|g| g.id != id);
            }
            "addProfile" | "updateProfile" => {
                let mut profile: Profile = serde_json::from_value(value)?;
                profile.validate()?;
                if method == "addProfile" {
                    profile.id = uuid::Uuid::new_v4().to_string();
                } else if !state.profiles.iter().any(|p| p.id == profile.id) {
                    return Err(Error::Invalid("Profile not found".into()));
                }
                state.profiles.retain(|p| p.id != profile.id);
                state.profiles.push(profile);
            }
            "removeProfile" => {
                let id = field("id")?;
                if state.games.iter().any(|g| g.profile_id == id) {
                    return Err(Error::Invalid("Profile is in use".into()));
                }
                state.profiles.retain(|p| p.id != id);
            }
            "addContainer" | "updateContainer" => {
                let name = field("name")?;
                let wine = field("wine_component")?;
                if name.trim().is_empty() {
                    return Err(Error::Invalid("Container name is required".into()));
                }
                if !state
                    .components
                    .iter()
                    .any(|c| c.id == wine && c.kind == Kind::Wine)
                {
                    return Err(Error::Missing("Select an installed Wine component".into()));
                }
                if method == "addContainer" {
                    let id = uuid::Uuid::new_v4().to_string();
                    let dir = self.data_dir.join("containers").join(&id);
                    fs::create_dir_all(&dir)?;
                    state.containers.push(Container {
                        id,
                        name,
                        wine_component: wine,
                        dir: dir.to_string_lossy().into(),
                    });
                } else {
                    let id = field("id")?;
                    let c = state
                        .containers
                        .iter_mut()
                        .find(|c| c.id == id)
                        .ok_or_else(|| Error::Invalid("Container not found".into()))?;
                    c.name = name;
                    c.wine_component = wine;
                }
            }
            "removeContainer" => {
                let id = field("id")?;
                if state.games.iter().any(|g| g.container_id == id) {
                    return Err(Error::Invalid(
                        "Container is in use; remove its games first".into(),
                    ));
                }
                let dir = state
                    .containers
                    .iter()
                    .find(|c| c.id == id)
                    .map(|c| c.dir.clone());
                state.containers.retain(|c| c.id != id);
                self.persist(state)?;
                if let Some(dir) = dir {
                    fs::remove_dir_all(dir)?;
                }
                return Ok(json!({}));
            }
            "updateComponent" => {
                let component: Component = serde_json::from_value(value)?;
                if component.version.trim().is_empty() {
                    return Err(Error::Invalid("Version is required".into()));
                }
                let c = state
                    .components
                    .iter_mut()
                    .find(|c| c.id == component.id)
                    .ok_or_else(|| Error::Invalid("Component not found".into()))?;
                c.version = component.version;
            }
            "removeComponent" => {
                let id = field("id")?;
                if state.containers.iter().any(|c| c.wine_component == id) {
                    return Err(Error::Invalid("Wine component is in use".into()));
                }
                let path = state
                    .components
                    .iter()
                    .find(|c| c.id == id)
                    .map(|c| c.path.clone());
                state.components.retain(|c| c.id != id);
                self.persist(state)?;
                if let Some(path) = path {
                    fs::remove_dir_all(path)?;
                }
                return Ok(json!({}));
            }
            _ => return Err(Error::Invalid(format!("Unknown method {method}"))),
        }
        self.persist(state)?;
        Ok(json!({}))
    }
}

#[cfg(test)]
mod tests;
