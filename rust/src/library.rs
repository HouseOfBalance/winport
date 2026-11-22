use serde::{Deserialize, Serialize};

#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct Game {
    pub id: String,
    pub name: String,
    pub exe_path: String,
    pub container_id: String,
    pub profile_id: String,
    pub extra_args: Vec<String>,
}
