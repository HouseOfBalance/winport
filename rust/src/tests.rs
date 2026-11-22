use super::*;

fn fixture() -> (Game, Container, Profile, Vec<Component>) {
    let profile = Profile::presets().remove(1);
    let container = Container {
        id: "c".into(),
        name: "Desktop".into(),
        wine_component: "wine:9".into(),
        dir: "/data/prefix".into(),
    };
    let game = Game {
        id: "g".into(),
        name: "A Game".into(),
        exe_path: "drive_c/Games/A Game/game.exe".into(),
        container_id: "c".into(),
        profile_id: profile.id.clone(),
        extra_args: vec!["--test".into(), "two words".into()],
    };
    let components = [Kind::Box64, Kind::Wine, Kind::Dxvk, Kind::Rootfs]
        .into_iter()
        .map(|kind| Component {
            id: format!("{}:9", kind.directory()),
            kind,
            version: "9".into(),
            path: format!("/components/{}", kind.directory()),
        })
        .collect();
    (game, container, profile, components)
}

#[test]
fn launch_plan_preserves_arguments_and_settings() {
    let (game, container, mut profile, components) = fixture();
    profile.cpu_cores = vec![2, 3];
    let plan = launch::build(&game, &container, &profile, &components).unwrap();
    assert_eq!(
        plan.argv,
        vec![
            "/components/box64/bin/box64",
            "/components/wine/bin/wine",
            "explorer",
            "/desktop=shell,1280x720",
            "/data/prefix/drive_c/Games/A Game/game.exe",
            "--test",
            "two words"
        ]
    );
    assert_eq!(plan.env["BOX64_DYNAREC_STRONGMEM"], "1");
    assert_eq!(plan.env["WINEPREFIX"], container.dir);
    assert_eq!(plan.env["DXVK_HUD"], "");
    assert_eq!(plan.cpu_cores, vec![2, 3]);
}

#[test]
fn empty_args_and_affinity_are_valid() {
    let (mut game, container, profile, components) = fixture();
    game.extra_args.clear();
    let plan = launch::build(&game, &container, &profile, &components).unwrap();
    assert_eq!(plan.argv.len(), 5);
    assert!(plan.cpu_cores.is_empty());
}

#[test]
fn each_missing_component_has_clear_error() {
    let (game, container, profile, components) = fixture();
    for kind in [Kind::Box64, Kind::Wine, Kind::Dxvk, Kind::Rootfs] {
        let remaining = components
            .iter()
            .filter(|c| c.kind != kind)
            .cloned()
            .collect::<Vec<_>>();
        let error = launch::build(&game, &container, &profile, &remaining)
            .unwrap_err()
            .to_string();
        assert!(error.to_lowercase().contains(kind.directory()), "{error}");
        assert!(error.contains("Missing component"));
    }
}

#[test]
fn invalid_paths_and_profiles_are_rejected() {
    let (mut game, container, mut profile, components) = fixture();
    game.exe_path = "../escape.exe".into();
    assert!(launch::build(&game, &container, &profile, &components).is_err());
    game.exe_path = "game.exe".into();
    profile.cpu_cores = vec![1024];
    assert!(launch::build(&game, &container, &profile, &components).is_err());
}

#[test]
fn persistence_round_trip_and_empty_defaults() {
    let temp = tempfile::tempdir().unwrap();
    let mut core = Core::init(temp.path()).unwrap();
    assert!(core.state.games.is_empty());
    assert_eq!(core.state.profiles.len(), 3);
    let (game, container, _, components) = fixture();
    core.state.games.push(game);
    core.state.containers.push(container);
    core.state.components = components;
    storage::save(temp.path(), &core.state).unwrap();
    let loaded = Core::init(temp.path()).unwrap();
    assert_eq!(
        serde_json::to_value(core.state).unwrap(),
        serde_json::to_value(loaded.state).unwrap()
    );
}

#[test]
fn corrupt_storage_is_not_overwritten() {
    let temp = tempfile::tempdir().unwrap();
    fs::write(temp.path().join("state.json"), b"broken").unwrap();
    assert!(Core::init(temp.path()).is_err());
    assert_eq!(fs::read(temp.path().join("state.json")).unwrap(), b"broken");
}

#[test]
fn import_zip_and_container_crud() {
    use std::io::Write;
    let temp = tempfile::tempdir().unwrap();
    let archive = temp.path().join("wine-9.zip");
    let mut zip = zip::ZipWriter::new(fs::File::create(&archive).unwrap());
    zip.start_file(
        "bin/wine",
        zip::write::SimpleFileOptions::default().unix_permissions(0o755),
    )
    .unwrap();
    zip.write_all(b"test").unwrap();
    zip.finish().unwrap();
    let mut core = Core::init(temp.path().join("data")).unwrap();
    let component = core.install_component(&archive, Kind::Wine).unwrap();
    assert!(Path::new(&component.path).join("bin/wine").exists());
    assert!(core.install_component(&archive, Kind::Wine).is_err());
    core.dispatch(
        "addContainer",
        json!({"name":"Test", "wine_component":component.id}),
    )
    .unwrap();
    let container = core.state.containers[0].clone();
    assert!(Path::new(&container.dir).is_dir());
    core.dispatch("addGame", json!({"id":"", "name":"Test game", "exe_path":"drive_c/test.exe", "container_id":container.id, "profile_id":"balanced", "extra_args":[]})).unwrap();
    assert!(core
        .dispatch("removeContainer", json!({"id":container.id}))
        .is_err());
    let id = core.state.games[0].id.clone();
    core.dispatch("removeGame", json!({"id":id})).unwrap();
    core.dispatch("removeContainer", json!({"id":container.id}))
        .unwrap();
    assert!(!Path::new(&container.dir).exists());
    assert!(core.stop(12345).is_err());
}

#[test]
fn unsafe_zip_leaves_no_component() {
    let temp = tempfile::tempdir().unwrap();
    let archive = temp.path().join("evil.zip");
    let mut zip = zip::ZipWriter::new(fs::File::create(&archive).unwrap());
    zip.start_file("../escape", zip::write::SimpleFileOptions::default())
        .unwrap();
    zip.finish().unwrap();
    let mut core = Core::init(temp.path().join("data")).unwrap();
    assert!(core.install_component(&archive, Kind::Rootfs).is_err());
    assert!(core.state.components.is_empty());
    assert!(!temp.path().join("escape").exists());
}
