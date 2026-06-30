package com.netsentinel.enums;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScanProfileTest {

    @Test
    void rapidoTraeTopPorts100() {
        assertThat(ScanProfile.QUICK.parameters())
                .containsExactly("-sV", "-T4", "--top-ports", "100");
    }

    @Test
    void estandarTraeFlagsBasicos() {
        assertThat(ScanProfile.STANDARD.parameters())
                .containsExactly("-sV", "-T4");
    }

    @Test
    void completoTraeTodosLosPuertos() {
        assertThat(ScanProfile.FULL.parameters())
                .containsExactly("-sV", "-T4", "-p-");
    }

    @Test
    void isAllowedAceptaPerfilesConocidos() {
        assertThat(ScanProfile.isAllowed(List.of("-sV", "-T4"))).isTrue();
        assertThat(ScanProfile.isAllowed(List.of("-sV", "-T4", "-p-"))).isTrue();
        assertThat(ScanProfile.isAllowed(List.of("-sV", "-T4", "--top-ports", "100"))).isTrue();
    }

    @Test
    void isAllowedRechazaParametrosArbitrarios() {
        assertThat(ScanProfile.isAllowed(List.of("-A", "--script=vuln"))).isFalse();
        assertThat(ScanProfile.isAllowed(List.of())).isFalse();
        assertThat(ScanProfile.isAllowed(null)).isFalse();
    }

    @Test
    void allowedParametersDevuelveTresPerfiles() {
        assertThat(ScanProfile.allowedParameters()).hasSize(3);
    }

    @Test
    void fromParametersDevuelveEnumCorrecto() {
        assertThat(ScanProfile.fromParameters(List.of("-sV", "-T4", "-p-")))
                .contains(ScanProfile.FULL);
    }
}
