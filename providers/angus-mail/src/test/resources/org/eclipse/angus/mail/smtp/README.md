# SMTP envelope PIPELINING tests

This branch integrates channel I/O and SSLEngine into SMTPTransport. Enable it
with `mail.smtp.pipelining=true` or `mail.smtps.pipelining=true`; both default to
false and require fresh advertised PIPELINING support. No runtime dependency
is added. The test-only independent engine client remains a comparison arm,
not the implementation used by the `transport` arm.

## Shared transport contract

```text
mvn -B -pl providers/angus-mail -am -Poss-release,staging,build-only -Dgpg.skip=true -Denforcer.skip -Dtest=SMTPPipeliningTest,SMTPPipeliningEngineTest,SMTPPipeliningTlsTest -Dsurefire.failIfNoSpecifiedTests=false package
```

`SMTPPipeliningTest` is shared with the reader-thread alternative. It gates
replies to prove command grouping, DATA/BDAT boundaries and sequential fallback,
and checks recipient results, exceptions, events, callbacks, content and reuse.
Add `-Dangus.pipelining.contract.starttls=true` to run that contract over STARTTLS
as well. The implicit-TLS cases always run. Engine-specific tests check the
custom context hook and preservation of existing factories through sequential
fallback. The optional `-Dangus.pipelining.benchmark=true` runs a reused-connection
latency comparison for 1, 10 and 100 recipients, without timing assertions.

## Run

From the repository root, using Maven and JDK 11, 17 or 21:

```text
mvn -B -pl providers/angus-mail -am -Poss-release,staging,build-only -Dgpg.skip=true -Denforcer.skip -Dtest=SMTPSSLEngineTest -Dsurefire.failIfNoSpecifiedTests=false -Dangus.sslengine.prototype=true package
```

Use `package`, not just `test`, for the first reactor build: the combined mail
module unpacks the earlier modules' source JARs. The tests are opt-in.
Without `angus.sslengine.prototype=true`, they do not
start any child JVMs. All connections are to the in-process loopback server.
No mail is sent to an external server.

Each case starts a fresh JVM using the same JDK as Maven. A diagnostic security
property requests key updates every 16 KiB; certificate and hostname checks
remain enabled. JSSE tracing, runtime/provider information, progress counters
and outcomes are retained under
`providers/angus-mail/target/sslengine-prototype/run-*/`.
Each child has a 45-second process deadline. Only that child is forcibly stopped
if it cannot exit. A watchdog termination fails the test.

The matrix covers STARTTLS and implicit TLS, 32/64 KiB MAIL replies, a large
first RCPT reply, two messages per successful
connection, timeouts, disconnects and certificate/hostname rejection. The
server validates command order, envelope parameters and exact message content.
Only complete, valid SMTP commands and legal multiline replies are used.
The sequential control runs Angus from this branch. There is no automatic
reconnect or replay, including after an unacknowledged message body.

To compare the reader-thread prototype too, first build its combined
`providers/angus-mail` module through its normal reactor, then add:

```text
-Dangus.sslengine.readerClasses=/absolute/path/to/reader-worktree/providers/angus-mail/target/classes
```

Quote that whole argument if the path contains spaces. This adds six
reader-thread cases and makes the sequential control use the same compiled
provider with pipelining disabled. The helper refuses a provider without the
prototype hooks rather than treating an ignored pipelining property as a
successful pipelined send. No dependency artifact is installed or overwritten
by this option. The `transport` cases use this branch's actual SMTPTransport;
the `engine` cases use the independent test client, including a 37-byte I/O
fragmentation check. Set `-Dangus.sslengine.modes=transport` to run only the
integrated transport cases. This is a test filter, not a feature setting.

The integrated wrong-host case rejects an engine hook returning a different
peer before the handshake; the independent case checks certificate hostname
rejection during the handshake. Shared transport tests additionally verify
that a configured hostname verifier can reject both STARTTLS and implicit TLS.

A reader-thread case may either complete or report a recognized stall; both
are recorded. A stall is a comparison result, not a passing delivery. Unexpected
failures and children that need forced termination fail the test. Do not infer a
production failure rate from one run. OS buffer behavior and scheduling affect
whether a stall or a zero-byte write occurs.

This measures behavior, not performance: tracing, fresh JVMs, handshakes and
cleanup are included. The independent client has a limited SMTP state machine;
the integrated arm uses Angus's normal command construction and delivery state.
The pressure fixture itself does not cover every Transport feature. Use the
shared contract tests for partial recipients, BDAT, events and subclass hooks.

## Test certificate

`pipelining.jks` contains a generated, public localhost test key, not a deployment
credential. Both passwords are `changeit`. The fixture trusts it explicitly;
wrong-host and untrusted-certificate cases must fail before authentication.

The same fixture is used by the reader-thread prototype. To regenerate it,
run this command on one line from this directory:

```text
keytool -genkeypair -alias pipelining-test -keyalg RSA -keysize 2048 -dname "CN=localhost, O=Angus Mail test fixture" -ext "SAN=dns:localhost,ip:127.0.0.1" -startdate "2024/01/01 00:00:00" -validity 7305 -storetype JKS -keystore pipelining.jks -storepass changeit -keypass changeit -noprompt
```
