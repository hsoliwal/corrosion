# M3 Corrosion / original Cargo build evidence

The original Corrosion Eclipse plugin owns `CargoTools.getCargoCommand()`,
the Rust project and rust-analyzer integration. Its new additive
`org.eclipse.corrosion.cargo.core.M3CargoBuildEvidence` is the
**source-owner** for Rust project metadata; Maven and Gradle merely
validate it. This is separate from JDT and CDT compiler contexts.

## Explicit native owner workflow

`captureOfflineLocked(IProject, selectedEclipseConfig, invocationUuid, monitor)`
may be called by a trusted, explicitly requested Corrosion integration.
It invokes the Corrosion-configured original Cargo executable via
`ProcessBuilder` without a shell:

```sh
cargo metadata --format-version=1 --locked --offline
```

Cargo has no network access in this mode, and `--locked` refuses
an update that would modify `Cargo.lock`; the method also requires a
project-local `Cargo.lock` before starting. There is no
`cargo build`, `cargo test`, rustup setup, automatic project-import
step or dependency installation in this evidence path. Actual Cargo
build/test execution remains in the Maven polyglot native delegate
or original Eclipse/Cargo tasks.

The subprocess writes bounded temporary output outside the project,
has a 90-second timeout and checks the original Eclipse cancellation
monitor. Timeout/cancellation/failure terminates the process; no
partial report is admitted.

For tests or an already executed original Cargo metadata invocation,
`captureFromOriginalMetadata(projectRoot, projectName, selected,
invocation, configuredCargoCommand, nativeMetadataFile, monitor)`
hashes the supplied native JSON bytes without rerunning Cargo. Callers
must supply same-invocation original metadata; this method does not
authenticate provenance or parse Cargo's foreign JSON schema.

## Packet contract

`M3-CARGO-BUILD-FACTS-V1` includes original provider/project,
Eclipse-selected `IBuildConfiguration`, original m2e invocation UUID,
SHA-256 for Cargo.toml, Cargo.lock and optional project-local
`.cargo/config.toml`, `rust-toolchain`, `rust-toolchain.toml`,
original Cargo metadata byte SHA-256 and size, and the configured
Cargo executable string SHA-256. Missing optional files are encoded
as literal `absent`; missing Cargo.toml or Cargo.lock fails closed.

The project descriptor has a strict 8 MiB bound; native metadata is
bounded to 16 MiB and hashed in 64 KiB chunks. Symlinked descriptor
files/directories, changing lengths, malformed identities and
out-of-project export destinations are rejected. `writeSnapshot`
is explicit, no-overwrite and idempotent for byte-identical facts.
An authorized caller may choose a unique generation-scoped path under
`target/ide/cargo-build-facts.ide` after creating its parent.

The Corrosion JUnit test `M3CargoBuildEvidenceTest` checks hash changes,
a required Cargo.lock, project-local source custody, cancellation,
no auto build outputs and immutable export. The golden source-owner
packet in Corrosion and Maven has identical bytes.

## Shared Maven and Gradle consumers

Maven's Java 17 `M3CargoBuildEvidenceWire` reads the original packet,
rejects noncanonical values and requires original project, selected
configuration and invocation ID to agree with the m2e-owned
`M3-ECLIPSE-BUILD-HANDOFF-V1` packet:

```sh
java -cp /approved/m3-polyglot-maven-plugin.jar \
  io.synexia.maven.polyglot.EclipseModelCli cargo-facts \
  /project/target/ide/cargo-build-facts.ide \
  /project/target/ide/build-handoff.ide
```

Gradle's `m3EclipseCargoFacts` delegates to the same pinned Maven JAR
and emits `build/reports/m3/eclipse-cargo-facts.json` only on success.
Neither Maven nor Gradle contains a second Cargo metadata resolver.

## Explicit limitations

The observed metadata file may contain a native Cargo resolve graph
but is not itself authenticated as an effective target-specific
compiler invocation. **This receipt does not identify rustc binary
bytes, target triple, RUSTFLAGS, build.rs/proc-macro execution, link
dependencies, generated outputs or a complete source generation.**
Those independent source-owner facts must be qualified before any
M3DB build-cache admission or task skipping.
`authorizesBuildSkipping()` always returns false.
