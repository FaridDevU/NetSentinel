use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize)]
pub struct ExecuteRequest {
    pub tool: String,
    pub args: Vec<String>,
    pub target: String,
    pub timeout_secs: Option<u64>,
}

#[derive(Debug, Serialize)]
pub struct ExecuteResponse {
    pub success: bool,
    pub stdout: Option<String>,
    pub stderr: Option<String>,
    pub exit_code: Option<i32>,
    pub duration_ms: u64,
    pub error: Option<String>,
}

#[derive(Serialize)]
pub struct HealthResponse {
    pub status: &'static str,
    pub version: &'static str,
}
