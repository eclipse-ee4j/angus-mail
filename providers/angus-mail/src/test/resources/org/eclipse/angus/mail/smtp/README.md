# SMTP envelope PIPELINING tests

This branch uses one reply reader per pipelined envelope. Enable it with
`mail.smtp.pipelining=true` or `mail.smtps.pipelining=true`; both default to
false and require fresh advertised PIPELINING support. No runtime dependency
is added.

## Shared transport contract and reader checks

From the repository root, using Maven and JDK 11, 17 or 21:

```text
mvn -B -pl providers/angus-mail -am -Poss-release,staging,build-only -Dgpg.skip=true -Denforcer.skip -Dtest=SMTPPipeliningTest,SMTPPipeliningTlsTest,SMTPReplyReaderTest -Dsurefire.failIfNoSpecifiedTests=false package
```

Use `package`, not just `test`, for the first reactor build: the combined mail
module unpacks the earlier modules' source JARs. Quote the comma-separated
arguments in PowerShell.

`SMTPPipeliningTest` gates replies to prove command grouping, DATA/BDAT boundaries
and sequential fallback, and checks recipient results, exceptions, events,
callbacks, content and reuse. Add `-Dangus.pipelining.contract.starttls=true`
to run that contract over STARTTLS. The implicit-TLS cases always run.
`SMTPReplyReaderTest` checks backpressure, socket abort and reader cleanup.
`SMTPPipeliningTlsTest` checks actual Transport sends and reuse over plain SMTP,
STARTTLS and implicit TLS. All connections use an in-process loopback server.

The optional `-Dangus.pipelining.benchmark=true` runs a reused-connection latency
comparison for 1, 10 and 100 recipients, without timing assertions. Run it
without the STARTTLS contract property for the plain-SMTP comparison.

The additional TLS stress and valid-command pressure methods are skipped by
default. They can reproduce the known SSLSocket key-update stall and need an
external process watchdog; a JUnit timeout alone cannot reliably stop a blocked
TLS write. A configured write timeout bounds the tested stall by failing the
connection. This branch does not reconnect or replay a failed transaction.

## Test certificate

`pipelining.jks` is a generated, public test key for localhost, not a deployment credential.
Both passwords are `changeit`. Tests trust this certificate explicitly and keep hostname
verification enabled. The existing general-purpose test keystore does not identify localhost.

Generated with JDK 11 keytool:

```text
keytool -genkeypair -alias pipelining-test -keyalg RSA -keysize 2048
  -dname "CN=localhost, O=Angus Mail test fixture"
  -ext "SAN=dns:localhost,ip:127.0.0.1"
  -startdate "2024/01/01 00:00:00" -validity 7305
  -storetype JKS -keystore pipelining.jks
  -storepass changeit -keypass changeit -noprompt
```

Run the command on one line when regenerating. The certificate expires in 2044.
