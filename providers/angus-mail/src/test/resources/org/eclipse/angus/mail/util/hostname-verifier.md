# Hostname verifier TLS fixture

`hostname-verifier.jks` contains a self-signed localhost certificate and its
test-only private key. Both passwords are `changeit`. The tests use normal JSSE
trust and endpoint checks, trusting this certificate specifically, so a custom
verifier's rejection cannot be confused with a failed TLS handshake.

The RSA certificate has DNS `localhost` and IP `127.0.0.1` subject alternative
names. It is valid for 36,500 days from January 1, 2020. It was generated with:

```text
keytool -genkeypair -alias localhost -keyalg RSA -keysize 2048 \
    -sigalg SHA256withRSA -dname "CN=localhost, OU=Angus Mail tests" \
    -ext "SAN=dns:localhost,ip:127.0.0.1" -startdate "2020/01/01 00:00:00" \
    -validity 36500 -storetype JKS -keystore hostname-verifier.jks \
    -storepass changeit -keypass changeit -noprompt
```
