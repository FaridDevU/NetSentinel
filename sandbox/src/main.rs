mod executor;
mod models;
mod validator;

use axum::{
    extract::Json,
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Router,
};
use models::{ExecuteRequest, ExecuteResponse, HealthResponse};
use tracing::info;

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

    let app = Router::new()
        .route("/health", get(health_handler))
        .route("/execute", post(execute_handler));

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

    let response = executor::execute(req).await;
    (StatusCode::OK, Json(response))
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
