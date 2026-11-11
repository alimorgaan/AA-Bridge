# `headunit.crt` / `headunit.key` — where these came from

**What it is.** An Android Auto *head-unit (receiver) identity*: an X.509 leaf
certificate for `C=JP, ST=Tokyo, L=Hachioji, O=JVC Kenwood, OU=01`, issued by
`C=US, ST=California, L=Mountain View, O=Google Automotive Link`, plus the
matching 2048-bit RSA private key, plus the issuing (self-signed, `CA:TRUE`)
`Google Automotive Link` certificate as the chain.

```
leaf  sha256 1C:0E:0E:F9:E6:72:DD:1A:63:AB:4D:61:AF:AA:89:96:A5:7C:A7:AA:96:6D:19:22:B9:7D:8F:93:85:EA:3C:35
      subject C=JP, ST=Tokyo, L=Hachioji, O=JVC Kenwood, OU=01
      issuer  C=US, ST=California, L=Mountain View, O=Google Automotive Link
      serial  0x1B   valid 2014-07-04 .. 2045-04-29   v1, sha256WithRSA
CA    sha256 49:E5:2E:FC:13:AD:2E:D0:9F:20:4C:3B:10:69:8B:D8:4B:B7:10:5F:51:05:58:AA:14:B8:11:9A:5C:4A:D1:7F
      self-signed, basicConstraints CA:TRUE, sha1WithRSA
```

`headunit.crt` contains **both** certs (leaf first, then CA), PEM-concatenated.
`headunit.key` is the same key as shipped in PKCS#1 form, re-encoded as
unencrypted PKCS#8 (`-----BEGIN PRIVATE KEY-----`) so Android's `KeyFactory`
can read it without an ASN.1 parser.

## Provenance

Extracted from the `PhoneAuto-beta-2027-01-19` reference APK
(`classes.dex`, three PEM string constants at offsets 1735456 / 1736618 /
1737906), then compared against the public upstream: it is **byte-identical**
to `cert/headunit.crt` and `cert/headunit.key` in
[`opencardev/aasdk`](https://github.com/opencardev/aasdk) — the certificate
shipped by virtually every self-hosted Android Auto head unit (openauto,
headunit-desktop, Crankshaft, ...). Same leaf fingerprint, same RSA modulus.

aasdk's own `cert/README.md` describes it as "a publicly available certificate
used in Android Auto head units ... not a secret". It has been publicly
distributed in that GPL project since the openauto era, and it is the de-facto
standard interop credential for a head unit that did not come out of a car
factory.

**So this is not CarbitLink's proprietary key and it is not something
reverse-engineered out of CarbitLink's binary** — the project rule
about not porting CarbitLink's own crypto does not bite here. It is a
third-party public credential, sourced and cited independently.

## Caveats worth stating plainly

1. It is still **JVC Kenwood's** vendor key. Presenting it means the phone
   sees a JVC Kenwood head unit. That is what every openauto build does, and
   it is Google's own chain check that decides whether to accept it — but it
   is not *our* identity and we did not obtain it from Google.
2. Shipping it inside a **public repo's APK** is the part with actual exposure
   (redistribution of a vendor private key), more so than using it locally.
   If that becomes a problem: `git rm` these two files and drop your own copy
   into the app's external files dir instead — `AaIdentity` prefers
   `<externalFilesDir>/aa/headunit.{crt,key}` over the assets, so no rebuild of
   logic is needed.
3. It can be rotated/revoked upstream at any time; validity runs to 2045 but
   Google's acceptance of it is not guaranteed by the cert dates.
