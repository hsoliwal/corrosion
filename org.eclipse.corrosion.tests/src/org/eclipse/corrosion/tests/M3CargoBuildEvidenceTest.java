/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Synexia Project.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.corrosion.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.corrosion.cargo.core.M3CargoBuildEvidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rust build evidence is owned by native Cargo, never by a Maven/Gradle parser. */
public class M3CargoBuildEvidenceTest {
    private static final String UUID = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    private Path root;

    @BeforeEach
    public void createTemporaryCargoProject() throws IOException {
        root = Files.createTempDirectory("m3-corrosion-cargo-tests-");
    }

    @AfterEach
    public void deleteTemporaryCargoProject() throws IOException {
        if (root != null && Files.exists(root)) {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    public void offlineLockedCargoSourceFactsAreStableAndByteSensitive() throws Exception {
        Path metadata = setup();
        var first = snapshot(metadata);
        assertEquals("cargo-fixture", first.project());
        assertEquals(UUID, first.invocation());
        assertEquals("Debug", first.eclipseSelected());
        assertEquals("absent", first.rustToolchainSha256());
        assertEquals("absent", first.cargoConfigSha256());
        assertEquals(Files.size(metadata), first.metadataBytes());
        assertTrue(first.cargoMetadataSha256().matches("[a-f0-9]{64}"));
        assertTrue(first.manifestSha256().matches("[a-f0-9]{64}"));
        assertTrue(first.lockSha256().matches("[a-f0-9]{64}"));
        assertFalse(first.authorizesBuildSkipping());
        assertArrayEquals(first.toExchangeBytes(), snapshot(metadata).toExchangeBytes());
        assertTrue(new String(first.toExchangeBytes(), StandardCharsets.UTF_8)
                .startsWith(M3CargoBuildEvidence.SCHEMA + "\nprovider\teclipse.corrosion\n"));

        Files.writeString(root.resolve("Cargo.lock"), "version = 4\n[[package]]\nname='changed'\n");
        var second = snapshot(metadata);
        assertNotEquals(first.lockSha256(), second.lockSha256(),
                "Cargo.lock byte changes must invalidate source-owner evidence");
        assertEquals(first.cargoMetadataSha256(), second.cargoMetadataSha256(),
                "Cargo metadata is not invented or regenerated during read-only capture");
        Files.writeString(metadata, "{\"format_version\":1,\"resolve\":{\"nodes\":[]}}\n");
        assertNotEquals(second.cargoMetadataSha256(), snapshot(metadata).cargoMetadataSha256(),
                "native Cargo resolved graph changes must be observed");
        assertFalse(Files.exists(root.resolve("target")),
                "metadata capture never compiles Rust or creates Cargo build outputs");
    }

    @Test
    public void refusesMissingLockUnsafePathsAndCanceledOwner() throws Exception {
        Path metadata = setup();
        Files.delete(root.resolve("Cargo.lock"));
        assertThrows(IOException.class, () -> snapshot(metadata));
        Files.writeString(root.resolve("Cargo.lock"), "version = 4\n");
        NullProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        assertThrows(OperationCanceledException.class,
                () -> M3CargoBuildEvidence.captureFromOriginalMetadata(
                        root, "cargo-fixture", "Debug", UUID, "cargo", metadata, monitor));
        assertThrows(IllegalArgumentException.class,
                () -> new M3CargoBuildEvidence.Snapshot("cargo-fixture", UUID, "Debug",
                        "A".repeat(64), "b".repeat(64), "absent", "absent", "absent",
                        "c".repeat(64), 1, "d".repeat(64)),
                "uppercase SHA-256 cannot identify original Cargo metadata");
        Path outside = Files.createTempFile("corrosion-external", ".txt");
        try {
            Path redirect = root.resolve("rust-toolchain.toml");
            try {
                Files.createSymbolicLink(redirect, outside);
                assertThrows(IOException.class, () -> snapshot(metadata));
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Windows runner without symlink creation privileges.
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    public void originalCargoProducerBytesMatchPortableMavenDecoderFixture() throws Exception {
        var packet = new M3CargoBuildEvidence.Snapshot(
                "cargo-fixture", UUID, "Debug",
                "a".repeat(64), "b".repeat(64), "absent", "absent", "absent",
                "c".repeat(64), 1234, "d".repeat(64));
        try (var data = M3CargoBuildEvidenceTest.class.getResourceAsStream(
                "/projects/m3-cargo-build-facts-v1.fixture")) {
            if (data == null) throw new IOException("missing Corrosion/Maven protocol fixture");
            assertArrayEquals(data.readAllBytes(), packet.toExchangeBytes(),
                    "Corrosion/Cargo source-owned bytes must match the independent Maven reader");
        }
    }

    @Test
    public void explicitExportCannotOverwriteAnotherOriginalBuildInvocation() throws Exception {
        Path metadata = setup();
        var original = snapshot(metadata);
        Path folder = Files.createDirectories(root.resolve("target/ide"));
        Path output = M3CargoBuildEvidence.writeSnapshot(original, root,
                Path.of("target/ide/cargo-build-facts.ide"));
        assertEquals(folder.resolve("cargo-build-facts.ide"), output);
        assertArrayEquals(original.toExchangeBytes(), Files.readAllBytes(output));
        assertEquals(output, M3CargoBuildEvidence.writeSnapshot(original, root,
                Path.of("target/ide/cargo-build-facts.ide")));
        var other = new M3CargoBuildEvidence.Snapshot(original.project(),
                "c111dc33-33bd-4f33-8aa9-6c3c11223344", original.eclipseSelected(),
                original.manifestSha256(), original.lockSha256(),
                original.cargoConfigSha256(), original.rustToolchainSha256(),
                original.rustToolchainTomlSha256(), original.cargoMetadataSha256(),
                original.metadataBytes(), original.configuredCargoCommandSha256());
        assertThrows(IOException.class, () -> M3CargoBuildEvidence.writeSnapshot(
                other, root, Path.of("target/ide/cargo-build-facts.ide")));
        assertThrows(IOException.class, () -> M3CargoBuildEvidence.writeSnapshot(
                original, root, Path.of("../foreign-build.ide")));
        assertArrayEquals(original.toExchangeBytes(), Files.readAllBytes(output));
    }

    private Path setup() throws IOException {
        Files.writeString(root.resolve("Cargo.toml"),
                "[package]\nname='cargo-fixture'\nversion='0.1.0'\n");
        Files.writeString(root.resolve("Cargo.lock"), "version = 4\n");
        return Files.writeString(root.resolve("original-cargo-metadata.json"),
                "{\"format_version\":1,\"resolve\":{\"nodes\":[]},\"packages\":[],\"workspace_members\":[]}\n");
    }

    private M3CargoBuildEvidence.Snapshot snapshot(Path metadata) throws IOException {
        return M3CargoBuildEvidence.captureFromOriginalMetadata(root,
                "cargo-fixture", "Debug", UUID, "cargo", metadata, new NullProgressMonitor());
    }
}
