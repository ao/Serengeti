package com.ataiva.serengeti.core.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Key stores for tests, made with the JDK's keytool: a CA, and node certificates it signs. */
public final class TestCerts {
    public static final String PASSWORD = "changeit";

    private TestCerts() {}

    private static void keytool(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("keytool " + String.join(" ", args) + " failed: " + out);
        }
    }

    /** A self-signed CA in {@code dir/name-ca.p12}, its certificate in {@code name-ca.pem}, and a trust store. */
    public static Path ca(Path dir, String name) throws IOException, InterruptedException {
        Path ks = dir.resolve(name + "-ca.p12");
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + name + " CA",
                "-ext", "bc:c", "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", PASSWORD);
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", ks.toString(), "-storepass", PASSWORD,
                "-file", dir.resolve(name + "-ca.pem").toString());
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", dir.resolve(name + "-ca.pem").toString(),
                "-storetype", "PKCS12", "-keystore", dir.resolve(name + "-trust.p12").toString(), "-storepass", PASSWORD);
        return ks;
    }

    public static Path truststore(Path dir, String caName) {
        return dir.resolve(caName + "-trust.p12");
    }

    /**
     * A node key store {@code dir/node.p12} whose certificate is signed by CA {@code caName} and names
     * {@code san} (keytool syntax, e.g. {@code dns:localhost,ip:127.0.0.1}).
     */
    public static Path node(Path dir, String caName, String node, String san) throws IOException, InterruptedException {
        Path ks = dir.resolve(node + ".p12");
        Path caKs = dir.resolve(caName + "-ca.p12");
        keytool("-genkeypair", "-alias", "node", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + node,
                "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", PASSWORD);
        Path csr = dir.resolve(node + ".csr");
        Path crt = dir.resolve(node + ".pem");
        keytool("-certreq", "-alias", "node", "-keystore", ks.toString(), "-storepass", PASSWORD, "-file", csr.toString());
        keytool("-gencert", "-rfc", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", PASSWORD,
                "-infile", csr.toString(), "-outfile", crt.toString(), "-validity", "2", "-ext", "san=" + san,
                "-ext", "eku=serverAuth,clientAuth");
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", dir.resolve(caName + "-ca.pem").toString(),
                "-keystore", ks.toString(), "-storepass", PASSWORD);
        keytool("-importcert", "-noprompt", "-alias", "node", "-file", crt.toString(), "-keystore", ks.toString(),
                "-storepass", PASSWORD);
        return ks;
    }
}
