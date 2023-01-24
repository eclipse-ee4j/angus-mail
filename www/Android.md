Angus Mail for Android
========================

Angus Mail for Android is now available!

Standard Angus Mail distribution can run on Android.

This version is available from the maven central repository.
You can try out this version by adding the following to your
build.gradle file for your Android application:

    android {
        packagingOptions {
            pickFirst 'META-INF/LICENSE.md' // picks the Angus Mail license file
            pickFirst 'META-INF/NOTICE.md' // picks the Angus Mail notice file
        }
    }
    
    dependencies {
        // use whatever the current version is...
        compile 'org.eclipse.angus:jakarta.mail:2.0.0'
        compile 'org.eclipse.angus:angus-activation:2.0.0'
        compile 'jakarta.activation:jakarta.activation-api:2.1.1'
    }

One of the standard Java features not supported on Android is SASL.  That means
none of the "mail._protocol_.sasl.*" properties will have any effect.  One of
the main uses of SASL was to enable OAuth2 support.  The latest version
of Angus Mail includes built-in OAuth2 support that doesn't require SASL.
See the [OAuth2](OAuth2) page for more details.

Angus Mail for Android requires at least Android API level 19,
which corresponds to
[Android KitKat](https://en.wikipedia.org/wiki/Android_version_history#Android_4.4_KitKat_.28API_19.29),
currently the oldest supported version of Android.

If you discover problems, please report them to
[angus-dev@eclipse.org](https://accounts.eclipse.org/mailing-list/angus-dev).
