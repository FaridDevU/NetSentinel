mod executor;
mod models;
mod validator;

use axum::{
    extract::{Json, Path, State},
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Router,
};
use models::{ExecuteRequest, ExecuteResponse, HealthResponse};
use std::{collections::HashMap, sync::Arc};
use tokio::sync::Mutex;
use tracing::info;

#[derive(Clone)]
pub struct AppState {
    pub running: Arc<Mutex<HashMap<String, u32>>>,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            std::env::var("RUST_LOG")
                .unwrap_or_else(|_| "sandbox=info".to_string()),
        )
        .init();

    let port: u16 = std::env::var("SANDBOX_PORT")
        .unwrap_or_else(|_| "7878".to_string())
        .parse()
        .expect("SANDBOX_PORT must be a valid port number");

    let state = AppState {
        running: Arc::new(Mutex::new(HashMap::new())),
    };

    let app = Router::new()
        .route("/health", get(health_handler))
        .route("/execute", post(execute_handler))
        .route("/cancel/:id", post(cancel_handler))
        .with_state(state);

    let addr = format!("0.0.0.0:{}", port);
    info!("NetSentinel Sandbox starting on {}", addr);

    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|e| panic!("Failed to bind to {}: {}", addr, e));

    axum::serve(listener, app)
        .await
        .expect("Server error");
}

async fn health_handler() -> impl IntoResponse {
    Json(HealthResponse {
        status: "ok",
        version: env!("CARGO_PKG_VERSION"),
    })
}

async fn execute_handler(
    State(state): State<AppState>,
    Json(req): Json<ExecuteRequest>,
) -> (StatusCode, Json<ExecuteResponse>) {
    if let Err(e) = validator::validate_tool(&req.tool) {
        return bad_request(e);
    }

    if let Err(e) = validator::validate_target(&req.target) {
        return bad_request(e);
    }

    if let Err(e) = validator::validate_args(&req.args) {
        return bad_request(e);
    }

    let response = executor::execute(req, state).await;
    (StatusCode::OK, Json(response))
}

async fn cancel_handler(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> (StatusCode, Json<ExecuteResponse>) {
    let pid = state.running.lock().await.remove(&id);
    if let Some(pid) = pid {
        let _ = tokio::process::Command::new("pkill")
            .args(["-TERM", "-P", &pid.to_string()])
            .status()
            .await;
        let _ = tokio::process::Command::new("kill")
            .args(["-TERM", &pid.to_string()])
            .status()
            .await;
    }
    (
        StatusCode::OK,
        Json(ExecuteResponse {
            success: true,
            stdout: None,
            stderr: None,
            exit_code: None,
            duration_ms: 0,
            error: None,
        }),
    )
}

fn bad_request(error: String) -> (StatusCode, Json<ExecuteResponse>) {
    (
        StatusCode::BAD_REQUEST,
        Json(ExecuteResponse {
            success: false,
            stdout: None,
            stderr: None,
            exit_code: None,
            duration_ms: 0,
            error: Some(error),
        }),
    )
}
