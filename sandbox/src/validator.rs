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
            let preview: String = arg.chars().take(32).collect();
            return Err(format!("Argument too long: '{}'", preview));
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allowed_tools_pass() {
        assert!(validate_tool("nmap").is_ok());
        assert!(validate_tool("gobuster").is_ok());
        assert!(validate_tool("nikto").is_ok());
    }

    #[test]
    fn disallowed_tools_are_rejected() {
        assert!(validate_tool("sqlmap").is_err());
        assert!(validate_tool("bash").is_err());
        assert!(validate_tool("").is_err());
    }

    #[test]
    fn valid_target_accepts_ip_and_hostname() {
        assert!(validate_target("192.168.1.1").is_ok());
        assert!(validate_target("10.0.0.0/24").is_ok());
        assert!(validate_target("example.com").is_ok());
        assert!(validate_target("[::1]").is_ok());
        assert!(validate_target("http://localhost:8080").is_ok());
    }

    #[test]
    fn target_with_invalid_chars_is_rejected() {
        assert!(validate_target("192.168.1.1; rm -rf /").is_err());
        assert!(validate_target("$(whoami)").is_err());
        assert!(validate_target("a | b").is_err());
        assert!(validate_target("").is_err());
    }

    #[test]
    fn safe_args_pass() {
        let args = vec!["-sV".to_string(), "-T4".to_string()];
        assert!(validate_args(&args).is_ok());
    }

    #[test]
    fn args_with_forbidden_char_are_rejected() {
        let args = vec!["$(id)".to_string()];
        assert!(validate_args(&args).is_err());

        let args = vec!["a;b".to_string()];
        assert!(validate_args(&args).is_err());

        let args = vec!["a|b".to_string()];
        assert!(validate_args(&args).is_err());
    }

    #[test]
    fn too_many_args_are_rejected() {
        let args: Vec<String> = (0..40).map(|i| format!("--arg{}", i)).collect();
        assert!(validate_args(&args).is_err());
    }

    #[test]
    fn too_long_target_is_rejected() {
        let long = "a".repeat(300);
        assert!(validate_target(&long).is_err());
    }

    #[test]
    fn oversized_multibyte_arg_is_rejected_without_panic() {
        let args = vec!["\u{20AC}".repeat(200)];
        assert!(validate_args(&args).is_err());
    }
}
