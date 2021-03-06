/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.client.tls;

import com.google.common.base.Throwables;
import com.google.common.io.BaseEncoding;

import com.eaio.uuid.UUID;
import com.spotify.sshagentproxy.AgentProxy;
import com.spotify.sshagentproxy.Identity;

import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509ExtensionUtils;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.security.provider.X509Factory;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.Calendar;
import java.util.Date;

public class X509CertificateFactory {

  private static final Logger log = LoggerFactory.getLogger(X509CertificateFactory.class);

  private static final JcaX509CertificateConverter CERTIFICATE_CONVERTER =
      new JcaX509CertificateConverter().setProvider("BC");

  private static final BaseEncoding KEY_ID_ENCODING =
      BaseEncoding.base16().upperCase().withSeparator(":", 2);
  private static final BaseEncoding CERT_ENCODING =
      BaseEncoding.base64().withSeparator("\n", 64);

  private static final int KEY_SIZE = 2048;
  private static final int HOURS_BEFORE = 1;
  private static final int HOURS_AFTER = 48;

  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  public static CertificateAndKeyPair get(final AgentProxy agentProxy, final Identity identity,
                                          final String username) {
    final UUID uuid = new UUID();
    final Calendar calendar = Calendar.getInstance();
    final X500Name issuerDN = new X500Name("C=US,O=Spotify,CN=helios-client");
    final X500Name subjectDN = new X500NameBuilder().addRDN(BCStyle.UID, username).build();

    calendar.add(Calendar.HOUR, -HOURS_BEFORE);
    final Date notBefore = calendar.getTime();

    calendar.add(Calendar.HOUR, HOURS_BEFORE + HOURS_AFTER);
    final Date notAfter = calendar.getTime();

    // Reuse the UUID time as a SN
    final BigInteger serialNumber = BigInteger.valueOf(uuid.getTime()).abs();

    try {
      final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
      keyPairGenerator.initialize(KEY_SIZE, new SecureRandom());

      final KeyPair keyPair = keyPairGenerator.generateKeyPair();
      final SubjectPublicKeyInfo subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(
          ASN1Sequence.getInstance(keyPair.getPublic().getEncoded()));

      final X509v3CertificateBuilder builder = new X509v3CertificateBuilder(issuerDN, serialNumber,
                                                                            notBefore, notAfter,
                                                                            subjectDN,
                                                                            subjectPublicKeyInfo);

      final DigestCalculator digestCalculator = new BcDigestCalculatorProvider()
          .get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));
      final X509ExtensionUtils utils = new X509ExtensionUtils(digestCalculator);

      final SubjectKeyIdentifier keyId = utils.createSubjectKeyIdentifier(subjectPublicKeyInfo);
      final String keyIdHex = KEY_ID_ENCODING.encode(keyId.getKeyIdentifier());
      log.info("generating an X509 certificate with key ID {}", keyIdHex);

      builder.addExtension(Extension.subjectKeyIdentifier, false, keyId);
      builder.addExtension(Extension.authorityKeyIdentifier, false,
                           utils.createAuthorityKeyIdentifier(subjectPublicKeyInfo));
      builder.addExtension(Extension.keyUsage, false,
                           new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign));
      builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

      final X509CertificateHolder holder = builder.build(new SshAgentContentSigner(agentProxy,
                                                                                   identity));
      log.debug("generated certificate:\n{}\n{}\n{}",
                X509Factory.BEGIN_CERT,
                CERT_ENCODING.encode(holder.getEncoded()),
                X509Factory.END_CERT);


      return new CertificateAndKeyPair(CERTIFICATE_CONVERTER.getCertificate(holder), keyPair);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  public static class CertificateAndKeyPair {
    private final Certificate certificate;
    private final KeyPair keyPair;

    public CertificateAndKeyPair(final Certificate certificate,
                                 final KeyPair keyPair) {
      this.certificate = certificate;
      this.keyPair = keyPair;
    }

    public Certificate getCertificate() {
      return certificate;
    }

    public KeyPair getKeyPair() {
      return keyPair;
    }
  }
}
