use std::time::{Duration, Instant};

use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;
use tokio::time::{sleep, timeout};
use tracing::{error, info, warn};
use uuid::Uuid;

use crate::models::{ExecuteRequest, ExecuteResponse};
use crate::AppState;

const DEFAULT_TIMEOUT_SECS: u64 = 600;
const OUTPUT_DRAIN_TIMEOUT_SECS: u64 = 2;

pub async fn execute(req: ExecuteRequest, state: AppState) -> ExecuteResponse {
    let exec_id = Uuid::new_v4();
    let start = Instant::now();
    let timeout_secs = req.timeout_secs.unwrap_or(DEFAULT_TIMEOUT_SECS);
    let execution_id = req.execution_id.clone();

    info!(
        exec_id = %exec_id,
        tool = %req.tool,
        target = %req.target,
        args = ?req.args,
        "Starting execution"
    );

    let mut command = build_command(&req.tool, &req.args, &req.target);
    command.stdout(std::process::Stdio::piped());
    command.stderr(std::process::Stdio::piped());
    command.kill_on_drop(true);

    let mut child = match command.spawn() {
        Ok(c) => c,
        Err(e) => {
            error!(exec_id = %exec_id, "Failed to spawn process: {}", e);
            return ExecuteResponse {
                success: false,
                stdout: None,
                stderr: None,
                exit_code: None,
                duration_ms: elapsed_ms(&start),
                error: Some(format!("Failed to start '{}': {}", req.tool, e)),
            };
        }
    };

    if let (Some(id), Some(pid)) = (&execution_id, child.id()) {
        state.running.lock().await.insert(id.clone(), pid);
    }

    let stdout = child.stdout.take().expect("stdout was piped");
    let stderr = child.stderr.take().expect("stderr was piped");
    let stdout_task = tokio::spawn(collect_output(stdout));
    let stderr_task = tokio::spawn(collect_output(stderr));

    let mut timed_out = false;
    let status_result = tokio::select! {
        result = child.wait() => result,
        _ = sleep(Duration::from_secs(timeout_secs)) => {
            timed_out = true;
            let _ = child.start_kill();
            child.wait().await
        }
    };

    if let Some(id) = &execution_id {
        state.running.lock().await.remove(id);
    }

    let stdout_output = drain_output(stdout_task).await;
    let stderr_output = drain_output(stderr_task).await;
    let duration_ms = elapsed_ms(&start);

    if timed_out {
        warn!(exec_id = %exec_id, timeout_secs, "Execution timed out");
        return ExecuteResponse {
            success: false,
            stdout: nonempty(stdout_output),
            stderr: nonempty(stderr_output),
            exit_code: None,
            duration_ms,
            error: Some(format!("Timed out after {} seconds", timeout_secs)),
        };
    }

    match status_result {
        Ok(status) => {
            let success = status.success();
            info!(
                exec_id = %exec_id,
                success,
                exit_code = status.code(),
                duration_ms,
                "Execution complete"
            );
            ExecuteResponse {
                success,
                stdout: nonempty(stdout_output),
                stderr: nonempty(stderr_output),
                exit_code: status.code(),
                duration_ms,
                error: None,
            }
        }
        Err(e) => {
            error!(exec_id = %exec_id, "Process wait error: {}", e);
            ExecuteResponse {
                success: false,
                stdout: nonempty(stdout_output),
                stderr: nonempty(stderr_output),
                exit_code: None,
                duration_ms,
                error: Some(format!("Process error: {}", e)),
            }
        }
    }
}

async fn drain_output(task: tokio::task::JoinHandle<std::io::Result<String>>) -> String {
    timeout(Duration::from_secs(OUTPUT_DRAIN_TIMEOUT_SECS), task)
        .await
        .ok()
        .and_then(|r| r.ok())
        .and_then(|r| r.ok())
        .unwrap_or_default()
}

async fn collect_output<R>(reader: R) -> std::io::Result<String>
where
    R: tokio::io::AsyncRead + Unpin,
{
    let mut lines = BufReader::new(reader).lines();
    let mut buf = String::new();
    while let Some(line) = lines.next_line().await? {
        buf.push_str(&line);
        buf.push('\n');
    }
    Ok(buf)
}

fn build_command(tool: &str, args: &[String], target: &str) -> Command {
    let mut cmd = Command::new(tool);
    for arg in args {
        cmd.arg(arg);
    }
    cmd.arg(target);
    cmd
}

fn elapsed_ms(start: &Instant) -> u64 {
    start.elapsed().as_millis() as u64
}

fn nonempty(s: String) -> Option<String> {
    if s.trim().is_empty() {
        None
    } else {
        Some(s)
    }
}
