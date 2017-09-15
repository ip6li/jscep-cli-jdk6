package com.opencsi.jscepcli;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Iterator;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.jscep.client.CertificateVerificationCallback;
import org.jscep.client.Client;
import org.jscep.client.EnrollmentResponse;
import org.jscep.transaction.OperationFailureException;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.varia.NullAppender;

/**
 * @author asyd
 */
public class App {

    private AppParameters params;
//    KeyPair kp;
//    CertUtil certutil;

    private void setParams(AppParameters params) {
        this.params = params;
    }

    private App() {
    }

    private void scepCLI() throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        KeyManager km = new KeyManager();
        CertUtil certutil = new CertUtil();

        if (params.getVerbose()) {
            System.err.println("Generating RSA key...");
        }
        KeyPair kp = km.createRSA(params.getKeySize());

        X509Certificate cert = certutil.createSelfSignedCertificate(kp, params.getDn());

        PKCS10CertificationRequest request = certutil.createCertificationRequest(kp,
                params.getDn(),
                params.getChallenge());

        CallbackHandler handler = new ConsoleCallbackHandler();
        URL serverURL = new URL(params.getUrl());

        try {
            int seconds = 0;

            if (params.getCsrFile() != null) {
                saveToPEM(params.getCsrFile(), request);
            }
            if (params.getText()) {
                printPEM("PKCS#10 signing request", request);
            }

            Client client = new Client(serverURL, handler);

            CertStore caCertificateStore = client.getCaCertificate(params.getCaIdentifier());
            Collection<? extends Certificate> caCertificates = caCertificateStore.getCertificates(null);
            Iterator<? extends Certificate> caCertificatesIterator = caCertificates.iterator();
            int caCertificateNum = 0;

            if (caCertificates.size() == 0) {
                System.err.println("No CA certificates.");
            } else {
                System.out.println("Received " + caCertificates.size() + " CA certificate(s).");
            }

            while (caCertificatesIterator.hasNext()) {
                Certificate c = caCertificatesIterator.next();
                if (params.getCaCertificateFile() != null) {
                    saveToPEM(params.getCaCertificateFile(), c);
                }
                if (params.getText()) {
                    caCertificateNum++;
                    printPEM("CA Certificate " + caCertificateNum, c);
                }
            }


            if (params.getVerbose()) {
                System.err.println("Starting enrollment request...");
            }

            EnrollmentResponse response = client.enrol(cert,
                    kp.getPrivate(),
                    request,
                    params.getCaIdentifier());

            /*
             * handle asynchronous response
             */
            while (response.isPending()) {
                seconds++;
                System.out.println("Enrollment request returned CERT_REQ_PENDING; polling... " +
                        "(waited " + seconds + "s)");
                Thread.sleep(1000);

                response = client.poll(cert, kp.getPrivate(),
                        cert.getSubjectX500Principal(),
                        response.getTransactionId(),
                        params.getCaIdentifier());
            }

            if (response.isSuccess()) {
                if (params.getVerbose()) {
                    System.err.println("Enrollment request successful!");
                }

                X509Certificate clientCertificate = null;
                int certNum = 0;

                if (params.getKeyFile() != null) {
                    saveToPEM(params.getKeyFile(), kp.getPrivate());
                }
                if (params.getText()) {
                    printPEM("RSA Private Key", kp.getPrivate());
                }

                CertStore store = response.getCertStore();
                Collection<? extends Certificate> certs = store.getCertificates(null);

                System.out.println("Received response containing " + certs.size() + " certificate(s).");

                for (Object c : certs) {
                    X509Certificate certificate = (X509Certificate) c;

                    certNum++;
                    if (params.getText()) {
                        printPEM("Certificate " + certNum, certificate);
                    }

                    /* Not an intermediate CA certificate */
                    clientCertificate = certificate;
                    if (params.getCertificateFile() != null) {
                        saveToPEM(params.getCertificateFile(), certificate);
                    }
                }
                System.out.println("Certificate issued");

                if (params.getText() || params.getCrlFile() != null) {
                    X509CRL crl;

                    try {
                        assert (clientCertificate!=null);
                        crl = client.getRevocationList(clientCertificate,
                                kp.getPrivate(),
                                clientCertificate.getIssuerX500Principal(),
                                clientCertificate.getSerialNumber(),
                                params.getCaIdentifier());

                        saveToPEM(params.getCrlFile(), crl);

                        if (params.getText() && crl != null) {
                            printPEM("Certificate Revocation List", crl);
                        }

                    } catch (OperationFailureException ofe) {
                        System.err.println("Could not retrieve CRL.");
                        if (params.getVerbose()) {
                            ofe.printStackTrace();
                        }
                    }
                } else {
                    if (params.getVerbose()) {
                        System.err.println("Skipping CRL output (neither a file nor --text was specified)");
                    }
                }

            } else {
                System.err.println("Failure response: " + response.getFailInfo());
            }
        } catch (IOException e) {
            if (params.getVerbose()) {
                e.printStackTrace();
            }

            System.err.println(e.getMessage());
            if (e.getMessage().contains("400")) {
                System.err.println(". Probably a template issue, look at PKI log");
            } else if (e.getMessage().contains("404")) {
                System.err.println(". Invalid URL or CA identifier");
            } else if (e.getMessage().contains("401")) {
                System.err.println(". Probably EJBCA invalid entity status");
            }

        } catch (Exception e) {
            System.out.println(e.getMessage());
            if (params.getVerbose()) {
                e.printStackTrace();
            }
        }
    }

    private void saveToPEM(String filename, Object data) {
        if (filename == null) {
            return;
        }

        try {
            JcaPEMWriter writer = new JcaPEMWriter(new FileWriter(new File(filename), true));
            writer.writeObject(data);
            writer.close();

        } catch (IOException e) {
            if (params.getVerbose()) {
                e.printStackTrace();
            }
            System.err.println("Could not save file: " + filename);
            System.err.println(e.getMessage());
        }
    }

    private void printPEM(String title, Object data) throws IOException {
        System.out.println(title + ":");
        JcaPEMWriter writer = new JcaPEMWriter(new OutputStreamWriter(System.out));
        writer.writeObject(data);
        writer.flush();
        System.out.println();
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("javax.net.debug", "none");

        App app = new App();
        AppParameters params = new AppParameters();
        JCommander jcmd = new JCommander(params);

        try {
            jcmd.parse(args);

            app.setParams(params);

            Logger root = Logger.getRootLogger();
            if (params.getVerbose()) {
                root.addAppender(new ConsoleAppender(
                        new PatternLayout(PatternLayout.TTCC_CONVERSION_PATTERN)));
            } else {
                root.addAppender(new NullAppender());
            }

            app.scepCLI();
        } catch (ParameterException e) {
            jcmd.usage();
        }
    }

    private static class ConsoleCallbackHandler implements CallbackHandler {

        @Override
        public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
            for (Callback cb : callbacks) {
                if (cb instanceof CertificateVerificationCallback) {
                    CertificateVerificationCallback callback = (CertificateVerificationCallback) cb;
                    callback.setVerified(true);
                } else {
                    throw new UnsupportedCallbackException(cb);
                }
            }
        }
    }
}
