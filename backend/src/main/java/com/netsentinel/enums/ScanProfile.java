package com.netsentinel.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum ScanProfile {

    RAPIDO(List.of("-sV", "-T4", "--top-ports", "100")),
    ESTANDAR(List.of("-sV", "-T4")),
    COMPLETO(List.of("-sV", "-T4", "-p-"));

    private final List<String> parameters;

    ScanProfile(List<String> parameters) {
        this.parameters = parameters;
    }

    public List<String> parameters() {
        return parameters;
    }

    public static Optional<ScanProfile> fromParameters(List<String> parameters) {
        if (parameters == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(p -> p.parameters.equals(parameters))
                .findFirst();
    }

    public static boolean isAllowed(List<String> parameters) {
        return fromParameters(parameters).isPresent();
    }

    public static List<List<String>> allowedParameters() {
        return Arrays.stream(values()).map(ScanProfile::parameters).toList();
    }
}
