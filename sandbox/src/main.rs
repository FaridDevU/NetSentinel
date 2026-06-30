mod executor;
mod models;
mod validator;

use axum::{
    extract::{Json, Path, State},
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    routing::{get, post},
    Router,
};
use models::{ExecuteRequest, ExecuteResponse, HealthResponse};
use std::{collections::HashMap, sync::Arc};
use tokio::sync::Mutex;
use tracing::{info, warn};

const AUTH_HEADER: &str = "x-sandbox-auth";

#[derive(Clone)]
pub struct AppState {
    pub running: Arc<Mutex<HashMap<String, u32>>>,
    pub auth_token: Arc<String>,
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

    let bind_host = std::env::var("SANDBOX_BIND_HOST")
        .unwrap_or_else(|_| "127.0.0.1".to_string());

    let auth_token = match std::env::var("SANDBOX_AUTH_TOKEN") {
        Ok(t) if !t.trim().is_empty() => t,
        _ => {
            warn!("SANDBOX_AUTH_TOKEN no configurado, usando token de desarrollo. NO usar en produccion.");
            "dev-token-changeme".to_string()
        }
    };

    let state = AppState {
        running: Arc::new(Mutex::new(HashMap::new())),
        auth_token: Arc::new(auth_token),
    };

    let app = Router::new()
        .route("/health", get(health_handler))
        .route("/execute", post(execute_handler))
        .route("/cancel/:id", post(cancel_handler))
        .with_state(state);

    let addr = format!("{}:{}", bind_host, port);
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
    headers: HeaderMap,
    Json(req): Json<ExecuteRequest>,
) -> (StatusCode, Json<ExecuteResponse>) {
    if let Err(err) = check_auth(&state, &headers) {
        return err;
    }

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
    headers: HeaderMap,
    Path(id): Path<String>,
) -> (StatusCode, Json<ExecuteResponse>) {
    if let Err(err) = check_auth(&state, &headers) {
        return err;
    }

    let pid = state.running.lock().await.remove(&id);
    if let Some(pid) = pid {
        executor::kill_process_tree(pid).await;
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

fn check_auth(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<(), (StatusCode, Json<ExecuteResponse>)> {
    let provided = headers
        .get(AUTH_HEADER)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if provided == state.auth_token.as_str() {
        Ok(())
    } else {
        warn!("Sandbox auth rejected: invalid or missing header");
        Err((
            StatusCode::UNAUTHORIZED,
            Json(ExecuteResponse {
                success: false,
                stdout: None,
                stderr: None,
                exit_code: None,
                duration_ms: 0,
                error: Some("Sandbox auth invalida".to_string()),
            }),
        ))
    }
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
