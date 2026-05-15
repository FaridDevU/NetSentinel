package com.netsentinel.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);
    private static final Pattern SAFE_TARGET = Pattern.compile(
            "^(([a-zA-Z0-9\\-]+\\.)+[a-zA-Z]{2,}|\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?)$"
    );
    private static final int TIMEOUT_MINUTES = 10;

    @Value("${scan.nmap.mode:wsl}")
    private String nmapMode;

    @Value("${scan.nmap.wsl-distro:kali-linux}")
    private String wslDistro;

    @Value("${scan.nmap.windows-path:C:\\Program Files (x86)\\Nmap\\nmap.exe}")
    private String windowsNmapPath;

    public record SandboxResult(boolean success, String output, String error) {}

    public SandboxResult runNmap(String target, List<String> parameters) {
        if (!SAFE_TARGET.matcher(target).matches()) {
            return new SandboxResult(false, null, "Invalid target: " + target);
        }

        List<String> command = buildCommand(target, parameters);
        log.info("Executing: {}", String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            Thread stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);

            stdoutReader.join(5000);
            stderrReader.join(5000);

            if (!finished) {
                process.destroyForcibly();
                return new SandboxResult(false, null, "Scan timed out after " + TIMEOUT_MINUTES + " minutes");
            }

            if (process.exitValue() == 0) {
                return new SandboxResult(true, stdout.toString(), null);
            } else {
                return new SandboxResult(false, null, stderr.toString());
            }

        } catch (Exception e) {
            log.error("Failed to execute nmap", e);
            return new SandboxResult(false, null, "Execution error: " + e.getMessage());
        }
    }

    private List<String> buildCommand(String target, List<String> parameters) {
        List<String> command = new ArrayList<>();

        if ("wsl".equals(nmapMode)) {
            command.add("wsl");
            command.add("-d");
            command.add(wslDistro);
            command.add("--");
            command.add("nmap");
        } else {
            command.add(windowsNmapPath);
        }

        command.addAll(parameters);
        command.add(target);

        return command;
    }
}
