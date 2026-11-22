use crate::{
    components::{Component, Kind},
    containers::Container,
    library::Game,
    profiles::Profile,
    Error, Result,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    path::{Component as PathPart, Path},
    process::{Child, Command},
};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LaunchPlan {
    pub env: BTreeMap<String, String>,
    pub argv: Vec<String>,
    pub cwd: String,
    pub cpu_cores: Vec<usize>,
}

pub fn build(
    game: &Game,
    container: &Container,
    profile: &Profile,
    components: &[Component],
) -> Result<LaunchPlan> {
    profile.validate()?;
    let needed = |kind: Kind| {
        components.iter().find(|c| c.kind == kind).ok_or_else(|| {
            Error::Missing(format!("{} component is not installed", kind.directory()))
        })
    };
    let box64 = needed(Kind::Box64)?;
    let wine = components
        .iter()
        .find(|c| c.id == container.wine_component && c.kind == Kind::Wine)
        .ok_or_else(|| Error::Missing("Container's Wine component is not installed".into()))?;
    let dxvk = needed(Kind::Dxvk)?;
    let rootfs = needed(Kind::Rootfs)?;
    if game.container_id != container.id || game.profile_id != profile.id {
        return Err(Error::Invalid(
            "Game references a different container or profile".into(),
        ));
    }
    let relative = Path::new(&game.exe_path);
    if relative.is_absolute()
        || relative
            .components()
            .any(|c| !matches!(c, PathPart::Normal(_) | PathPart::CurDir))
        || !game.exe_path.to_ascii_lowercase().ends_with(".exe")
    {
        return Err(Error::Invalid(
            "Executable must be a relative .exe path inside the container".into(),
        ));
    }
    let exe = Path::new(&container.dir)
        .join(relative)
        .to_string_lossy()
        .into_owned();
    let env = BTreeMap::from([
        ("WINEPREFIX".into(), container.dir.clone()),
        (
            "BOX64_DYNAREC_BIGBLOCK".into(),
            profile.bigblock.to_string(),
        ),
        (
            "BOX64_DYNAREC_STRONGMEM".into(),
            profile.strongmem.to_string(),
        ),
        (
            "BOX64_DYNAREC_SAFEFLAGS".into(),
            profile.safeflags.to_string(),
        ),
        (
            "DXVK_HUD".into(),
            if profile.dxvk_hud { "fps,devinfo" } else { "" }.into(),
        ),
        ("DXVK_FRAME_RATE".into(), profile.fps_limit.to_string()),
        ("WINEDLLOVERRIDES".into(), "dxgi,d3d11,d3d10core=n,b".into()),
        ("WINEDLLPATH".into(), format!("{}/x64", dxvk.path)),
        (
            "BOX64_LD_LIBRARY_PATH".into(),
            format!(
                "{}/lib/x86_64-linux-gnu:{}/usr/lib/x86_64-linux-gnu",
                rootfs.path, rootfs.path
            ),
        ),
        ("WINPORT_ROOTFS".into(), rootfs.path.clone()),
    ]);
    let mut argv = vec![
        format!("{}/bin/box64", box64.path),
        format!("{}/bin/wine", wine.path),
        "explorer".into(),
        format!("/desktop=shell,{}x{}", profile.width, profile.height),
        exe,
    ];
    argv.extend(game.extra_args.clone());
    Ok(LaunchPlan {
        env,
        argv,
        cwd: container.dir.clone(),
        cpu_cores: profile.cpu_cores.clone(),
    })
}

pub fn spawn(plan: &LaunchPlan) -> Result<Child> {
    let executable = plan
        .argv
        .first()
        .ok_or_else(|| Error::Invalid("Empty launch plan".into()))?;
    let mut command = Command::new(executable);
    command
        .args(&plan.argv[1..])
        .envs(&plan.env)
        .current_dir(&plan.cwd);
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        let cores = plan.cpu_cores.clone();
        unsafe {
            command.pre_exec(move || {
                if libc::setpgid(0, 0) != 0 {
                    return Err(std::io::Error::last_os_error());
                }
                if !cores.is_empty() {
                    let mut set: libc::cpu_set_t = std::mem::zeroed();
                    libc::CPU_ZERO(&mut set);
                    for core in &cores {
                        libc::CPU_SET(*core, &mut set);
                    }
                    if libc::sched_setaffinity(0, std::mem::size_of_val(&set), &set) != 0 {
                        return Err(std::io::Error::last_os_error());
                    }
                }
                Ok(())
            });
        }
    }
    command.spawn().map_err(|e| Error::Invalid(format!("Cannot launch {executable}: {e}. Android 10+ blocks app-data execution; a proot/loader backend is required.")))
}
