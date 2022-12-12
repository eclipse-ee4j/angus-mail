Angus Mail Workspace Structure
================================

Here's the structure of the Angus Mail workspace, showing the different
maven modules I needed to create to allow Angus Mail to be built by
maven.

First, the main maven module.

    pom.xml

And then some files for checking that the API signatures match the spec
(not yet integrated into the build).

    mail.sig
    siggen
    sigtest

The main Angus Mail source code module, containing the core of the runtime
that goes into angus-mail.jar.

    core/pom.xml
    core/src/main/java/com/sun/mail/auth/MD4.java
    ...
    core/src/main/java/com/sun/mail/handlers/handler_base.java
    ...
    core/src/main/java/com/sun/mail/util/ASCIIUtility.java
    ...
    core/src/main/java/com/sun/mail/util/package-info.java
    core/src/main/java/module-info.java
    core/src/main/resources/META-INF/services/jakarta.mail.util.StreamProvider

Several modules containing demo source code. They're buildable to make
sure they do build before shipping them, but they're shipped only as
source code. Note the embedded README.txt files

    demos/client/pom.xml
    demos/client/src/main/java/ComponentFrame.java
    demos/client/src/main/java/FolderModel.java
    demos/client/src/main/java/FolderTreeNode.java
    demos/client/src/main/java/FolderViewer.java
    demos/client/src/main/java/MessageViewer.java
    demos/client/src/main/java/MultipartViewer.java
    demos/client/src/main/java/README.txt
    demos/client/src/main/java/SimpleAuthenticator.java
    demos/client/src/main/java/SimpleClient.java
    demos/client/src/main/java/StoreTreeNode.java
    demos/client/src/main/java/TextViewer.java
    demos/client/src/main/java/simple.mailcap
    demos/demo/pom.xml
    demos/demo/src/main/java/README.txt
    demos/demo/src/main/java/example/app/CRLFOutputStream.java
    demos/demo/src/main/java/example/app/NewlineOutputStream.java
    demos/demo/src/main/java/example/app/copier.java
    demos/demo/src/main/java/example/app/folderlist.java
    demos/demo/src/main/java/example/app/internal/TtyAuthenticator.java
    demos/demo/src/main/java/example/app/internal/answer.java
    demos/demo/src/main/java/example/app/internal/foldersplit.java
    demos/demo/src/main/java/example/app/internal/fpopulate.java
    demos/demo/src/main/java/example/app/internal/msgsperweek.java
    demos/demo/src/main/java/example/app/internal/testidle.java
    demos/demo/src/main/java/example/app/monitor.java
    demos/demo/src/main/java/example/app/mover.java
    demos/demo/src/main/java/example/app/msgmultisendsample.java
    demos/demo/src/main/java/example/app/msgsend.java
    demos/demo/src/main/java/example/app/msgsendsample.java
    demos/demo/src/main/java/example/app/msgshow.java
    demos/demo/src/main/java/example/app/namespace.java
    demos/demo/src/main/java/example/app/populate.java
    demos/demo/src/main/java/example/app/registry.java
    demos/demo/src/main/java/example/app/search.java
    demos/demo/src/main/java/example/app/sendfile.java
    demos/demo/src/main/java/example/app/sendhtml.java
    demos/demo/src/main/java/example/app/smtpsend.java
    demos/demo/src/main/java/example/app/transport.java
    demos/demo/src/main/java/example/app/uidmsgshow.java
    demos/demo/src/main/java/module-info.java
    demos/logging/pom.xml
    demos/logging/src/main/java/README.txt
    demos/logging/src/main/java/example/app/FileErrorManager.java
    demos/logging/src/main/java/example/app/MailHandlerDemo.java
    demos/logging/src/main/java/example/app/SummaryFormatter.java
    demos/logging/src/main/java/maildemo.policy
    demos/logging/src/main/java/maildemo.properties
    demos/logging/src/main/java/module-info.java
    demos/outlook/pom.xml
    demos/outlook/src/main/java/MSBodyPart.java
    demos/outlook/src/main/java/MSMessage.java
    demos/outlook/src/main/java/MSMultipartDataSource.java
    demos/outlook/src/main/java/README.txt
    demos/pom.xml
    demos/servlet/pom.xml
    demos/servlet/src/main/java/JakartaMail.html
    demos/servlet/src/main/java/README.txt
    demos/servlet/src/main/java/example/app/JakartaMailServlet.java
    demos/servlet/src/main/java/module-info.java
    demos/taglib/pom.xml
    demos/taglib/src/main/java/demo/AttachmentInfo.java
    demos/taglib/src/main/java/demo/ListAttachmentsTEI.java
    demos/taglib/src/main/java/demo/ListAttachmentsTag.java
    demos/taglib/src/main/java/demo/ListMessagesTEI.java
    demos/taglib/src/main/java/demo/ListMessagesTag.java
    demos/taglib/src/main/java/demo/MessageInfo.java
    demos/taglib/src/main/java/demo/MessageTEI.java
    demos/taglib/src/main/java/demo/MessageTag.java
    demos/taglib/src/main/java/demo/SendTag.java
    demos/taglib/src/main/java/module-info.java
    demos/taglib/src/main/resources/META-INF/taglib.tld
    demos/webapp/build.bat
    demos/webapp/build.sh
    demos/webapp/pom.xml
    demos/webapp/src/main/java/demo/AttachmentServlet.java
    demos/webapp/src/main/java/demo/FilterServlet.java
    demos/webapp/src/main/java/demo/MailUserBean.java
    demos/webapp/src/main/webapp/WEB-INF/web.xml
    demos/webapp/src/main/webapp/compose.jsp
    demos/webapp/src/main/webapp/errordetails.jsp
    demos/webapp/src/main/webapp/errorpage.jsp
    demos/webapp/src/main/webapp/folders.jsp
    demos/webapp/src/main/webapp/index.html
    demos/webapp/src/main/webapp/login.jsp
    demos/webapp/src/main/webapp/logout.jsp
    demos/webapp/src/main/webapp/messagecontent.jsp
    demos/webapp/src/main/webapp/messageheaders.jsp
    demos/webapp/src/main/webapp/send.jsp
    demos/webapp/webapp.README.txt

The source code for basic protocol providers.

    providers/gimap/pom.xml
    providers/imap/pom.xml
    providers/pop3/pom.xml
    providers/smtp/pom.xml

A module for java.util.logging integration.

    mailhandler/pom.xml

A module that contains only the Delivery Status Notification support. I
moved all the source code here because none of this appears in
mail.jar.

    dsn/pom.xml
    dsn/src/main/java/com/sun/mail/dsn/DeliveryStatus.java
    dsn/src/main/java/com/sun/mail/dsn/DispositionNotification.java
    dsn/src/main/java/com/sun/mail/dsn/MessageHeaders.java
    dsn/src/main/java/com/sun/mail/dsn/MultipartReport.java
    dsn/src/main/java/com/sun/mail/dsn/Report.java
    dsn/src/main/java/com/sun/mail/dsn/message_deliverystatus.java
    dsn/src/main/java/com/sun/mail/dsn/message_dispositionnotification.java
    dsn/src/main/java/com/sun/mail/dsn/multipart_report.java
    dsn/src/main/java/com/sun/mail/dsn/package-info.java
    dsn/src/main/java/com/sun/mail/dsn/text_rfc822headers.java
    dsn/src/main/java/module-info.java
    dsn/src/main/resources/META-INF/MANIFEST.MF
    dsn/src/main/resources/META-INF/mailcap

The mbox protocol provider module. Again, source code moved here
because none of this appears in mail.jar. Also includes a submodule to
build the native code (even though the native source code is in the
upper module; is that too weird?)

    providers/mbox/native/pom.xml
    providers/mbox/pom.xml
    providers/mbox/src/main/cpp/com/sun/mail/mbox/UNIXFile.c
    providers/mbox/src/main/cpp/com/sun/mail/mbox/UNIXInbox.c
    providers/mbox/src/main/java/com/sun/mail/mbox/ContentLengthCounter.java
    ...
    providers/mbox/src/main/java/com/sun/mail/remote/POP3RemoteProvider.java
    providers/mbox/src/main/java/com/sun/mail/remote/POP3RemoteStore.java
    ...
    providers/mbox/src/main/java/module-info.java
    providers/mbox/src/main/resources/META-INF/javamail.providers
    providers/mbox/src/main/resources/META-INF/services/jakarta.mail.Provider
    providers/mbox/src/test/java/com/sun/mail/mbox/MboxFolderExpungeTest.java
    providers/mbox/src/test/java/com/sun/mail/mbox/MboxFolderTest.java

A module just for building the javadocs. Putting these rules in the
parent pom.xml just didn't work so I moved them here.

    javadoc/pom.xml

Finally, the documentation. Not a module. Most of it is copied to the
website gh-pages branch when a release is published.

    doc/release/ApacheJServ.html
    doc/release/CHANGES.txt
    doc/release/COMPAT.txt
    doc/release/IssueMap.txt
    doc/release/JavaWebServer.html
    doc/release/NOTES.txt
    doc/release/NTLMNOTES.txt
    doc/release/README.txt
    doc/release/SSLNOTES.txt
    doc/release/Tomcat.html
    doc/release/classpath-NT.html
    doc/release/iPlanet.html
    doc/release/images/direct-classpath.jpg
    doc/release/images/indirect-classpath.jpg
