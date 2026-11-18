/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Synexia Project.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.corrosion.cargo.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

/**
 * Source-owned Rust/Cargo project evidence for the universal Maven/Gradle
 * exchange, without Java-toolchain assumptions.
 *
 * <p>Corrosion owns Cargo executable selection and project identity. Native
 * Cargo owns package resolution, feature unification and workspace targets.
 * The only optional command is explicitly selected, offline and locked
 * {@code cargo metadata --format-version=1 --locked --offline}. No compilation,
 * dependency installation, download, Cargo.toml edit or rustup invocation
 * occurs here; planning and ordinary Eclipse import never run this class.</p>
 *
 * <p>Raw metadata bytes are hashed as evidence of what Cargo reported. These
 * digests do not prove the compiler binary, target triple, build.rs effects,
 * environment, native dependencies, generated outputs or successful Rust
 * compilation. No result from this API permits Maven/Gradle cache admission
 * or build skipping.</p>
 */
public final class M3CargoBuildEvidence {
    public static final String SCHEMA = "M3-CARGO-BUILD-FACTS-V1"; //$NON-NLS-1$
    private static final long MAX_DESCRIPTOR = 8L * 1024 * 1024;
    private static final long MAX_METADATA = 16L * 1024 * 1024;
    private static final Duration MAX_NATIVE_METADATA_TIME = Duration.ofSeconds(90);

    private M3CargoBuildEvidence() { }

    public record Snapshot(String project, String invocation,
            String eclipseSelected, String manifestSha256, String lockSha256,
            String cargoConfigSha256, String rustToolchainSha256,
            String rustToolchainTomlSha256, String cargoMetadataSha256,
            long metadataBytes, String configuredCargoCommandSha256) {
        public Snapshot {
            project = valid(project, false);
            invocation = valid(invocation, false);
            if (!UUID.fromString(invocation).toString().equals(invocation)) {
                throw new IllegalArgumentException("invalid Eclipse invocation UUID"); //$NON-NLS-1$
            }
            eclipseSelected = valid(eclipseSelected, true);
            manifestSha256 = sha(manifestSha256, false);
            lockSha256 = sha(lockSha256, false);
            cargoConfigSha256 = sha(cargoConfigSha256, true);
            rustToolchainSha256 = sha(rustToolchainSha256, true);
            rustToolchainTomlSha256 = sha(rustToolchainTomlSha256, true);
            cargoMetadataSha256 = sha(cargoMetadataSha256, false);
            configuredCargoCommandSha256 = sha(configuredCargoCommandSha256, false);
            if (metadataBytes < 1 || metadataBytes > MAX_METADATA) {
                throw new IllegalArgumentException("invalid Cargo metadata byte budget"); //$NON-NLS-1$
            }
        }

        /** Deterministic, canonical UTF-8/LF facts; no native Cargo JSON copied into JVM. */
        public byte[] toExchangeBytes() {
            String out = SCHEMA + "\n" //$NON-NLS-1$
                    + "provider\teclipse.corrosion\n" //$NON-NLS-1$
                    + "project64\t" + b64(project) + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "invocation\t" + invocation + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "eclipseSelected64\t" + b64(eclipseSelected) + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "manifestSha256\t" + manifestSha256 + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "lockSha256\t" + lockSha256 + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "cargoConfigSha256\t" + cargoConfigSha256 + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "rustToolchainSha256\t" + rustToolchainSha256 + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "rustToolchainTomlSha256\t" + rustToolchainTomlSha256 + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "cargoMetadataSha256\t" + cargoMetadataSha256 + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "metadataBytes\t" + metadataBytes + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "cargoCommandSha256\t" + configuredCargoCommandSha256 + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
            return out.getBytes(StandardCharsets.UTF_8);
        }

        public boolean authorizesBuildSkipping() { return false; }
    }

    /**
     * Explicit native-owner command. The configured executable comes directly
     * from Corrosion's original CargoTools preference and is executed as one
     * argv token (no shell, PATH script interpolation or generated wrapper).
     */
    public static Snapshot captureOfflineLocked(IProject project, String selected,
            String invocation, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        Objects.requireNonNull(project, "project"); //$NON-NLS-1$
        if (project.getLocation() == null || !project.isAccessible()) {
            throw new IOException("Corrosion Cargo project is not accessible"); //$NON-NLS-1$
        }
        String command = CargoTools.getCargoCommand();
        valid(command, false);
        Path root = project.getLocation().toFile().toPath().toRealPath();
        requireDescriptor(root, "Cargo.toml", true); //$NON-NLS-1$
        requireDescriptor(root, "Cargo.lock", true); //$NON-NLS-1$
        cancelled(monitor);

        // Capture output outside the project. This API is never invoked
        // implicitly by the Eclipse project configurator.
        Path temporary = Files.createTempFile("m3-corrosion-cargo-metadata-", ".json"); //$NON-NLS-1$ //$NON-NLS-2$
        Path errors = Files.createTempFile("m3-corrosion-cargo-stderr-", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command,
                    "metadata", "--format-version=1", "--locked", "--offline"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            builder.directory(root.toFile());
            builder.redirectOutput(temporary.toFile());
            builder.redirectError(errors.toFile());
            process = builder.start();
            long deadline = System.nanoTime() + MAX_NATIVE_METADATA_TIME.toNanos();
            for (;;) {
                cancelled(monitor);
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() >= deadline) {
                    throw new IOException("offline Cargo metadata exceeded its explicit time budget"); //$NON-NLS-1$
                }
            }
            if (process.exitValue() != 0) {
                throw new IOException("original offline/locked Cargo metadata command failed: exit " //$NON-NLS-1$
                        + process.exitValue());
            }
            cancelled(monitor);
            return captureFromOriginalMetadata(root, project.getName(),
                    selected, invocation, command, temporary, monitor);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(errors);
        }
    }

    /**
     * Deterministic source-owner fixture path for testing and original Cargo
     * metadata exporters. The caller asserts that the supplied bytes are from
     * the same native Cargo invocation; this method cannot authenticate that.
     */
    public static Snapshot captureFromOriginalMetadata(Path root, String project,
            String eclipseSelected, String invocation, String cargoCommand,
            Path nativeMetadata, IProgressMonitor monitor) throws IOException {
        Objects.requireNonNull(root, "root"); //$NON-NLS-1$
        project = valid(project, false);
        eclipseSelected = valid(eclipseSelected, true);
        cargoCommand = valid(cargoCommand, false);
        cancelled(monitor);
        Path directory = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cargo project root is not a regular directory"); //$NON-NLS-1$
        }
        String manifest = requireDescriptor(directory, "Cargo.toml", true); //$NON-NLS-1$
        String lock = requireDescriptor(directory, "Cargo.lock", true); //$NON-NLS-1$
        String config = requireDescriptor(directory, ".cargo/config.toml", false); //$NON-NLS-1$
        String toolchain = requireDescriptor(directory, "rust-toolchain", false); //$NON-NLS-1$
        String toolchainToml = requireDescriptor(directory, "rust-toolchain.toml", false); //$NON-NLS-1$
        HashStat metadata = hashFile(Objects.requireNonNull(nativeMetadata, "nativeMetadata"), //$NON-NLS-1$
                MAX_METADATA, true);
        cancelled(monitor);
        return new Snapshot(project, invocation, eclipseSelected, manifest, lock, config,
                toolchain, toolchainToml, metadata.sha256(), metadata.bytes(),
                HexFormat.of().formatHex(digest().digest(cargoCommand.getBytes(StandardCharsets.UTF_8))));
    }

    /** Explicit non-overwriting project-local export for an approved invocation. */
    public static Path writeSnapshot(Snapshot facts, Path projectRoot, Path destination)
            throws IOException {
        Objects.requireNonNull(facts, "facts"); //$NON-NLS-1$
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath(); //$NON-NLS-1$
        Path requested = Objects.requireNonNull(destination, "destination"); //$NON-NLS-1$
        Path file = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new IOException("Cargo facts destination must be inside the source project"); //$NON-NLS-1$
        }
        for (Path cursor = file; cursor != null && cursor.startsWith(root); cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("Cargo facts destination contains a symbolic link"); //$NON-NLS-1$
            }
        }
        if (!Files.isDirectory(file.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cargo facts destination parent does not exist"); //$NON-NLS-1$
        }
        byte[] original = facts.toExchangeBytes();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) != original.length
                    || !java.util.Arrays.equals(Files.readAllBytes(file), original)) {
                throw new IOException("refusing to overwrite conflicting Cargo facts generation"); //$NON-NLS-1$
            }
            return file;
        }
        Files.write(file, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        return file;
    }

    private record HashStat(String sha256, long bytes) { }

    private static String requireDescriptor(Path root, String relative, boolean required)
            throws IOException {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) throw new IOException("Cargo descriptor outside project"); //$NON-NLS-1$
        for (Path cursor = file.getParent(); cursor != null && cursor.startsWith(root);
                cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("Cargo descriptor parent symlink: " + relative); //$NON-NLS-1$
            }
        }
        HashStat hash = hashFile(file, MAX_DESCRIPTOR, required);
        return hash == null ? "absent" : hash.sha256(); //$NON-NLS-1$
    }

    private static HashStat hashFile(Path path, long limit, boolean required) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!required) return null;
            throw new IOException("missing original Cargo input: " + path); //$NON-NLS-1$
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("original Cargo input is not a regular file: " + path); //$NON-NLS-1$
        }
        long size = Files.size(path);
        if (size > limit || (required && size == 0)) {
            throw new IOException("original Cargo input outside byte budget: " + path); //$NON-NLS-1$
        }
        MessageDigest sha = digest();
        byte[] buffer = new byte[65536];
        long count = 0;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            for (int bytes; (bytes = input.read(buffer)) != -1; ) {
                count += bytes;
                if (count > limit) {
                    throw new IOException("original Cargo input changed beyond byte budget"); //$NON-NLS-1$
                }
                sha.update(buffer, 0, bytes);
            }
        }
        if (count != size || Files.size(path) != count) {
            throw new IOException("original Cargo file length changed during capture"); //$NON-NLS-1$
        }
        return new HashStat(HexFormat.of().formatHex(sha.digest()), count);
    }

    private static String sha(String value, boolean optional) {
        if (optional && "absent".equals(value)) return value; //$NON-NLS-1$
        if (value == null || !value.matches("[0-9a-f]{64}")) { //$NON-NLS-1$
            throw new IllegalArgumentException("noncanonical original Cargo SHA-256"); //$NON-NLS-1$
        }
        return value;
    }

    private static String valid(String text, boolean empty) {
        Objects.requireNonNull(text, "original Cargo identity"); //$NON-NLS-1$
        if ((!empty && text.isBlank()) || text.length() > 4096
                || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid Cargo identity"); //$NON-NLS-1$
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("invalid original Cargo Unicode"); //$NON-NLS-1$
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("invalid original Cargo Unicode"); //$NON-NLS-1$
            }
        }
        return text;
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible); //$NON-NLS-1$
        }
    }

    private static void cancelled(IProgressMonitor monitor) {
        if (monitor != null && monitor.isCanceled()) {
            throw new OperationCanceledException();
        }
    }
}
