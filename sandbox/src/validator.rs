const ALLOWED_TOOLS: &[&str] = &["nmap", "gobuster", "nikto"];
const MAX_ARGS: usize = 30;
const MAX_TARGET_LEN: usize = 253;

const FORBIDDEN_CHARS: &[char] = &['&', '|', ';', '$', '`', '(', ')', '{', '}', '<', '>', '\n', '\r', '!', '*', '?', '\\'];

pub fn validate_tool(tool: &str) -> Result<(), String> {
    if ALLOWED_TOOLS.contains(&tool) {
        Ok(())
    } else {
        Err(format!(
            "Tool '{}' not allowed. Allowed tools: {}",
            tool,
            ALLOWED_TOOLS.join(", ")
        ))
    }
}

pub fn validate_target(target: &str) -> Result<(), String> {
    if target.is_empty() {
        return Err("Target cannot be empty".to_string());
    }

    if target.len() > MAX_TARGET_LEN {
        return Err(format!("Target too long (max {} chars)", MAX_TARGET_LEN));
    }

    let valid = target.chars().all(|c| {
        c.is_alphanumeric() || matches!(c, '.' | '-' | '_' | '/' | ':' | '[' | ']')
    });

    if !valid {
        return Err(format!("Target '{}' contains invalid characters", target));
    }

    Ok(())
}

pub fn validate_args(args: &[String]) -> Result<(), String> {
    if args.len() > MAX_ARGS {
        return Err(format!(
            "Too many arguments: {} (max {})",
            args.len(),
            MAX_ARGS
        ));
    }

    for arg in args {
        if arg.chars().any(|c| FORBIDDEN_CHARS.contains(&c)) {
            return Err(format!("Argument '{}' contains forbidden character", arg));
        }
        if arg.len() > 512 {
            return Err(format!("Argument too long: '{}'", &arg[..32]));
        }
    }

    Ok(())
}
