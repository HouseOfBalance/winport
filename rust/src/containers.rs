use serde::{Deserialize, Serialize};

#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct Container {
    pub id: String,
    pub name: String,
    pub wine_component: String,
    pub dir: String,
}
