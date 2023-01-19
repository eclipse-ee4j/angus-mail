<br>

The Angus Mail project is a compatible implementation of
the [Jakarta Mail Specification 2.1+](https://jakarta.ee/specifications/mail/) providing a platform-independent and
protocol-independent framework to build mail and messaging applications.
It is available for use with the
[Java SE platform](http://www.oracle.com/technetwork/java/javase/index.html)
and in the [Jakarta EE platform](http://jakarta.ee).

This project does not support the
[Java EE platform](http://www.oracle.com/technetwork/java/javaee/index.html).
See [Jakarta Mail](https://jakartaee.github.io/mail-api/README-JakartaMail) for the version supporting the [Java EE platform](http://www.oracle.com/technetwork/java/javaee/index.html)
and versions 8 and 9 of the [Jakarta EE platform](http://jakarta.ee).

This project is part of [Eclipse Angus project](https://projects.eclipse.org/projects/ee4j.angus).

<br>

# Table of Contents
* [Latest News](#Latest_News)
* [Download Angus Mail Release](#Download_Angus_Mail_Release)
* [API Documentation](#API_Documentation)
* [Samples](#Samples)
* [Help](#Help)
* [Bugs](#Bugs)
* [Development Releases](#Development_Releases)
* [Angus Mail for Android](#Angus_Mail_for_Android)
* [Project Documentation](#Project_Documentation)

# <a name="Latest_News"></a>Latest News

## January TBD, 2023 - Angus Mail 2.0.0 Final Release ##

Changes `com.sun.mail` module name prefix
to `org.eclipse.angus.mail` and package name prefix from
`com.sun.mail` to `org.eclipse.angus.mail`.

## January 11, 2023 - Angus Mail 1.1.0 Final Release ##

Adds built-in support for GraalVM native-image, support for OSGi Mediator Specification,
and contains multiple bug fixes and small enhancements.

| native-image option            | description                               | value                      |
|:-------------------------------|:------------------------------------------|:---------------------------|
| angus.mail.native-image.enable | Turn on built-in support for native image | false / **true** (default) |
| angus.mail.native-image.trace  | Print log messages to `System.out`        | **false** (default) / true |

## December 14, 2021 - Angus Mail 1.0.0 Final Release ##

Initial release of the Eclipse Angus - Angus Mail project.
Provides implementation of the Jakarta Mail 2.1 Specification.
The main jar file is now located at
[org.eclipse.angus:angus-mail](https://search.maven.org/search?q=g:org.eclipse.angus%20a:angus-mail).

## August 18, 2021 - Jakarta Mail moves to Eclipse Angus ##

To break tight integration between Jakarta Mail Specification API and the implementation,
sources of the implementation were moved to this project and further development continues here.
Angus Mail is the direct successor of JavaMail/JakartaMail.

<br/>

# <a name="Download_Angus_Mail_Release"></a>Download Angus Mail Release

The latest release of Angus Mail is ${angus-mail.version}.

The following table provides easy access to the latest release. Most
people will only need the main Angus Mail implementation in the
`angus-mail.jar` and `jakarta.mail-api.jar` files together
with [the Angus Activation](https://eclipse-ee4j.github.io/angus-activation/)
on the module path or on the class path.

| Item                                                                                                                                     | Description                                                                            |
|:-----------------------------------------------------------------------------------------------------------------------------------------|:---------------------------------------------------------------------------------------|
| [angus-mail.jar](https://repo1.maven.org/maven2/org/eclipse/angus/angus-mail/${angus-mail.version}/angus-mail-${angus-mail.version}.jar) | The Jakarta Mail implementation, including the SMTP, IMAP, and POP3 protocol providers |
| [README.txt](docs/README.txt)                                                                                                            | Overview of the release                                                                |
| [NOTES.txt](docs/NOTES.txt)                                                                                                              | Additional notes about using Angus Mail                                                |
| [SSLNOTES.txt](docs/SSLNOTES.txt)                                                                                                        | Notes on using SSL/TLS with Angus Mail                                                 |
| [NTLMNOTES.txt](docs/NTLMNOTES.txt)                                                                                                      | Notes on using NTLM authentication with Angus Mail                                     |
| [CHANGES.txt](docs/CHANGES.txt)                                                                                                          | Changes since the previous release                                                     |
| [COMPAT.txt](docs/COMPAT.txt)                                                                                                            | Important notes about compatibility                                                    |

<br/>

In addition, the Angus Mail jar files are published to the Maven repository.
The main Angus Mail jar file, which is all most applications will need,
can be included using this Maven dependency:
```
        <dependencies>
            <dependency>
                <groupId>jakarta.mail</groupId>
                <artifactId>jakarta.mail-api</artifactId>
                <version>${mail-api.version}</version>
            </dependency>
            <dependency>
                <groupId>com.sun.mail</groupId>
                <artifactId>agnus-mail</artifactId>
                <version>${angus-mail.version}</version>
                <scope>runtime</scope>
            </dependency>
        </dependencies>
```
<br/>

You can find all of the Angus project jar files in
[Maven Central](https://search.maven.org/search?q=g:org.eclipse.angus). They can be used in following configurations:

<br/>

Preferred way of using Angus Mail jar files is to use Jakarta Mail API with Angus Mail runtime:  

| jar file                                                                                                                                                            | module name               | groupId           | artifactId          | Description                                                                                                          |
|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------|:--------------------------|:------------------|:--------------------|:---------------------------------------------------------------------------------------------------------------------|
| [jakarta.mail-api.jar](https://repo1.maven.org/maven2/jakarta/mail/jakarta.mail-api/${mail-api.version}/jakarta.mail-api-${mail-api.version}.jar)                   | jakarta.mail              | jakarta.mail      | jakarta.mail-api    | The Jakarta Mail API definitions only, suitable for compiling against                                                |
| [angus-mail.jar](https://repo1.maven.org/maven2/org/eclipse/angus/angus-mail/${angus-mail.version}/angus-mail-${angus-mail.version}.jar)                            | com.sun.mail              | org.eclipse.angus | angus-mail          | The Angus Mail runtime jar file, including the SMTP, IMAP, and POP3 protocol providers and java.util.logging handler |
| [gimap.jar](https://repo1.maven.org/maven2/org/eclipse/angus/gimap/${angus-mail.version}/gimap-${angus-mail.version}.jar)                                           | com.sun.mail.gimap        | org.eclipse.angus | gimap               | An EXPERIMENTAL Gmail IMAP protocol provider that supports Gmail-specific features                                   |
| [dsn.jar](https://repo1.maven.org/maven2/org/eclipse/angus/dsn/${angus-mail.version}/dsn-${angus-mail.version}.jar)                                                 | com.sun.mail.dsn          | org.eclipse.angus | dsn                 | Support for parsing and creating messages containing Delivery Status Notifications                                   |

<br/>

Alternatively, jakarta.mail.jar, which includes Jakarta Mail APIs with Angus Mail runtime as a default provider in one
jar file, can be used, ie to limit the length of the classpath or number of dependencies:

| jar file                                                                                                                                                            | module name               | groupId           | artifactId          | Description                                                                                                          |
|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------|:--------------------------|:------------------|:--------------------|:---------------------------------------------------------------------------------------------------------------------|
| [jakarta.mail.jar](https://repo1.maven.org/maven2/org/eclipse/angus/jakarta.mail/${angus-mail.version}/jakarta.mail-${angus-mail.version}.jar)                      | jakarta.mail              | org.eclipse.angus | jakarta.mail        | The Angus Mail implementation jar file, including the Jakarta Mail API, SMTP, IMAP, and POP3 protocol providers  and java.util.logging handler     |
| [gimap.jar](https://repo1.maven.org/maven2/org/eclipse/angus/gimap/${angus-mail.version}/gimap-${angus-mail.version}.jar)                                           | com.sun.mail.gimap        | org.eclipse.angus | gimap               | An EXPERIMENTAL Gmail IMAP protocol provider that supports Gmail-specific features                                   |
| [dsn.jar](https://repo1.maven.org/maven2/org/eclipse/angus/dsn/${angus-mail.version}/dsn-${angus-mail.version}.jar)                                                 | com.sun.mail.dsn          | org.eclipse.angus | dsn                 | Support for parsing and creating messages containing Delivery Status Notifications                                   |

<br/>

Finally, for fine-grained control over the providers in use, following jar files can be used:

| jar file                                                                                                                                                            | module name               | groupId           | artifactId          | Description                                                                                                          |
|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------|:--------------------------|:------------------|:--------------------|:---------------------------------------------------------------------------------------------------------------------|
| [jakarta.mail-api.jar](https://repo1.maven.org/maven2/jakarta/mail/jakarta.mail-api/${mail-api.version}/jakarta.mail-api-${mail-api.version}.jar)                   | jakarta.mail              | jakarta.mail      | jakarta.mail-api    | The Jakarta Mail API definitions only, suitable for compiling against                                                |
| [angus-core.jar](https://repo1.maven.org/maven2/org/eclipse/angus/angus-core/${angus-mail.version}/angus-core-${angus-mail.version}.jar)                            | com.sun.mail              | org.eclipse.angus | angus-core          | The Angus Mail runtime with no protocol providers; use with one of the following providers                           |
| [gimap.jar](https://repo1.maven.org/maven2/org/eclipse/angus/gimap/${angus-mail.version}/gimap-${angus-mail.version}.jar)                                           | com.sun.mail.gimap        | org.eclipse.angus | gimap               | An EXPERIMENTAL Gmail IMAP protocol provider that supports Gmail-specific features                                   |
| [dsn.jar](https://repo1.maven.org/maven2/org/eclipse/angus/dsn/${angus-mail.version}/dsn-${angus-mail.version}.jar)                                                 | com.sun.mail.dsn          | org.eclipse.angus | dsn                 | Support for parsing and creating messages containing Delivery Status Notifications                                   |
| [smtp.jar](https://repo1.maven.org/maven2/org/eclipse/angus/smtp/${angus-mail.version}/smtp-${angus-mail.version}.jar)                                              | com.sun.mail.smtp         | org.eclipse.angus | smtp                | The SMTP protocol provider                                                                                           |
| [imap.jar](https://repo1.maven.org/maven2/org/eclipse/angus/imap/${angus-mail.version}/imap-${angus-mail.version}.jar)                                              | com.sun.mail.imap         | org.eclipse.angus | imap                | The IMAP protocol provider                                                                                           |
| [pop3.jar](https://repo1.maven.org/maven2/org/eclipse/angus/pop3/${angus-mail.version}/pop3-${angus-mail.version}.jar)                                              | com.sun.mail.pop3         | org.eclipse.angus | pop3                | The POP3 protocol provider                                                                                           |
| [logging-mailhandler.jar](https://repo1.maven.org/maven2/org/eclipse/angus/logging-mailhandler/${angus-mail.version}/logging-mailhandler-${angus-mail.version}.jar) | com.sun.mail.util.logging | org.eclipse.angus | logging-mailhandler | A java.util.logging handler that uses Jakarta Mail, suitable for use in Google App Engine.                           |

<br/>

[Angus Activation](https://eclipse-ee4j.github.io/angus-activation/) provides following jar files:

| jar file                                                                                                                                                                              | module name                   | groupId            | artifactId             | Description                                                                 |
|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:------------------------------|:-------------------|:-----------------------|:----------------------------------------------------------------------------|
| [jakarta.activation-api.jar](https://repo1.maven.org/maven2/jakarta/activation/jakarta.activation-api/${activation-api.version}/jakarta.activation-api-${activation-api.version}.jar) | jakarta.activation            | jakarta.activation | jakarta.activation-api | The Jakarta Activation API definitions only, suitable for compiling against |
| [angus-activation.jar](https://repo1.maven.org/maven2/org/eclipse/angus/angus-activation/${angus-activation.version}/angus-activation-${angus-activation.version}.jar)                | com.sun.activation.registries | org.eclipse.angus  | angus-activation       | The Angus Activation runtime                                                |

<br/>

# <a name="API_Documentation"></a>API Documentation

The Jakarta Mail API is defined through the
[Jakarta EE Specification Process](https://jakarta.ee/about/jesp/).

The Jakarta Mail specification and API documentation are available
[here](https://jakarta.ee/specifications/mail/).

<br/>

# <a name="Samples"></a>Samples

Some sample programs showing how to use the Jakarta Mail APIs are available
[here](https://github.com/eclipse-ee4j/mail/releases/download/2.0.1/jakartamail-samples.zip).

<br/>

# <a name="Help"></a>Help

Please read the
[Angus Mail FAQ](FAQ.html)!
Read it again. Tell everyone you know to read it. Thank you!

You can post questions to the
[angus-dev mailing list](https://accounts.eclipse.org/mailing-list/angus-dev).

Or, post a question on [Stack Overflow](http://stackoverflow.com/) using the
[javamail](http://stackoverflow.com/questions/tagged/javamail) tag.

<br/>

# <a name="Bugs"></a>Bugs

Angus Mail bugs are tracked in the
[GitHub Angus Mail project issue tracker](https://github.com/eclipse-ee4j/angus-mail/issues).

<br/>

# <a name="Development_Releases"></a>Development Releases

From time to time snapshot releases of the next version of Angus Mail
under development are published to the
[Jakarta Sonatype OSS repository](https://jakarta.oss.sonatype.org).
These snapshot releases have received only minimal testing, but may
provide previews of bug fixes or new features under development.

For example, you can download the jakarta.mail.jar file from the Angus Mail
2.0.0-SNAPSHOT release
[here](https://jakarta.oss.sonatype.org/content/repositories/snapshots/org/eclipse/angus/jakarta.mail/2.0.0-SNAPSHOT/).
Be sure to scroll to the bottom and choose the jar file with the most
recent time stamp.

You'll need to add the following configuration to your Maven ~/.m2/settings.xml
to be able to use these with Maven:

```
    <profiles>
        <!-- to allow loading Jakarta snapshot artifacts -->
        <profile>
            <id>jakarta-snapshots</id>
            <pluginRepositories>
                <pluginRepository>
                    <id>jakarta-snapshots</id>
                    <name>Jakarta Snapshots</name>
                    <snapshots>
                        <enabled>true</enabled>
                        <updatePolicy>always</updatePolicy>
                        <checksumPolicy>fail</checksumPolicy>
                    </snapshots>
                    <url>https://jakarta.oss.sonatype.org/content/repositories/snapshots/</url>
                    <layout>default</layout>
                </pluginRepository>
            </pluginRepositories>
        </profile>
    </profiles>
```

And then when you build use `mvn -Pjakarta-snapshots ...`.

If you want the plugin repository to be enabled all the time so you don't need the -P, add:

```
    <activeProfiles>
        <activeProfile>jakarta-snapshots</activeProfile>
    </activeProfiles>
```

<br/>

# <a name="Angus_Mail_for_Android"></a>Angus Mail for Android

The latest release includes support for Angus Mail on Android.
See the [Android](Android) page for details.

<br/>

# <a name="Project_Documentation"></a>Project Documentation

You'll find more information about the protocol providers supported by
Angus Mail on the following pages:

-   [ smtp ](SMTP-Transport)
-   [ imap ](IMAP-Store)
-   [ pop3 ](POP3-Store)
-   [ mbox ](Mbox-Store)
-   [ pop3remote ](POP3-Remote-Store)

If you're interested in writing your own protocol provider (most people
won't need to), you can find more documentation on protocol providers
[here](https://javaee.github.io/javamail/docs/Providers.pdf).

The use of
[OAuth2 authentication](https://developers.google.com/gmail/xoauth2_protocol)
with Angus Mail is described [here](OAuth2).

The following pages provide hints and tips for using particular mail servers:

-   [Gmail](Gmail)
-   [ Yahoo! Mail ](Yahoo)
-   [ Exchange and Office 365 ](Exchange)
-   [ Outlook.com ](Outlook)

The following pages provide hints and tips for using Jakarta Mail on
particular operating systems or environments:

-   [Windows](Windows)
-   [Google App Engine](Google-App-Engine)

See [Build Instructions](Build-Instructions) for instructions on how to
download and build the most recent Angus Mail source code. You can also
find a bundle of the source code for the most recent Angus Mail release
in the [Releases](https://github.com/eclipse-ee4j/angus-mail/releases) area of
this project.

If you're interested in contributing to Angus Mail, see the
[Contributions](Contributions) page.

You can find a list of products related to JavaMail/Jakarta Mail/Angus Mail on the
[Third Party Products](ThirdPartyProducts) page.

Please see our page of
[links to additional information about Jakarta Mail and Internet email](Links)
and our list of
[books about Jakarta Mail and Internet email](Books).

To understand the Angus Mail license, see the [License](JakartaMail-License) page.
