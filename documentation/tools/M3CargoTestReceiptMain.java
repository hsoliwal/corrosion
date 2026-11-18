/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: EPL-2.0
 */
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * Validate actual Corrosion/Cargo PDE JUnit cases, not merely Maven's exit code.
 * A skipped real Cargo source-owner fixture is never accepted as qualification.
 */
public final class M3CargoTestReceiptMain {
    private static final String TEST = "M3CargoBuildEvidenceTest";
    private static final Set<String> REQUIRED = Set.of(
            "offlineLockedCargoSourceFactsAreStableAndByteSensitive",
            "refusesMissingLockUnsafePathsAndCanceledOwner",
            "originalCargoProducerBytesMatchPortableMavenDecoderFixture",
            "explicitExportCannotOverwriteAnotherOriginalBuildInvocation");

    private M3CargoTestReceiptMain() { }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException("usage: M3CargoTestReceiptMain <Corrosion reports dir>");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("original Corrosion test directory missing");
        }
        Set<String> seen = new HashSet<>();
        int count = 0;
        try (Stream<Path> reports = Files.list(root)) {
            for (Path xml : reports.filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted().limit(4097).toList()) {
                if (++count > 4096 || !Files.isRegularFile(xml, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(xml) > 16 * 1024 * 1024) {
                    throw new IllegalArgumentException("unsafe original Corrosion report");
                }
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var document = factory.newDocumentBuilder().parse(xml.toFile());
                var tests = document.getElementsByTagName("testcase");
                for (int i = 0; i < tests.getLength(); i++) {
                    Element item = (Element) tests.item(i);
                    String owner = item.getAttribute("classname");
                    if (!(owner.equals(TEST) || owner.endsWith("." + TEST))) continue;
                    String name = item.getAttribute("name");
                    if (!REQUIRED.contains(name) || !seen.add(name)) {
                        throw new IllegalArgumentException("duplicate/unknown Cargo test: " + name);
                    }
                    for (String invalid : new String[] {"error", "failure", "skipped"}) {
                        if (item.getElementsByTagName(invalid).getLength() != 0) {
                            throw new IllegalArgumentException("Corrosion Cargo test not accepted: "
                                    + name + " (" + invalid + ")");
                        }
                    }
                }
            }
        }
        if (!seen.equals(REQUIRED)) {
            Set<String> missing = new HashSet<>(REQUIRED);
            missing.removeAll(seen);
            throw new IllegalArgumentException("original Corrosion test cases missing: " + missing);
        }
        System.out.println("PASS: four original Corrosion Cargo evidence tests executed without skips");
    }
}
