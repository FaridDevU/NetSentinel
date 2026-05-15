package com.netsentinel.dto;

import java.util.List;

public record ScanRequest(
        String target,
        List<String> parameters
) {}
