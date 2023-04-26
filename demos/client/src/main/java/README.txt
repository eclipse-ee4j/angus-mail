example.client.SimpleClient
------------

Notes:
======

This should not be taken as a demo of how to use the Swing API, but
rather a very simple graphical mail client. It shows how viewers can
be used to display the content from mail messages.  It also (like the
other demos) shows how to retrieve Folders from a Store, Messages
from a Folder, and content from Messages.


To run the demo:
================

    1.  Set your CLASSPATH to include the "jakarta.mail.jar",
        "jakarta.activation.jar", and the current directory.  For example:

	export CLASSPATH=/u/me/download/jakarta.mail.jar:/u/me/download/jakarta.activation.jar:.

    2.  Go to the demo/client directory

    3.  Compile all the files using your Java compiler.  For example:

	  javac *.java

    4.  Run the demo. For example:

	  java example.client.SimpleClient -L imap://username:password@hostname/

	Note that example.client.SimpleClient expects to read the "simple.mailcap"
	file from the current directory.  The simple.mailcap file
	contains configuration information about viewers needed by
	the example.client.SimpleClient demo program.



Overview of the Classes
=======================

Main Classes:

	example.client.SimpleClient   =    contains main().
			     Uses the parameters to the application to
			     locate the correct Store.  e.g.

				example.client.SimpleClient -L imap://cotton:secret@snow-goon/

			     It will create the main frame and
			     creates a tree.  The tree uses the
			     StoreTreeNodes and FolderTreeNodes.

	example.client.StoreTreeNode   =    subclass of Swing's DefaultMutableTreeNode.
			     This class shows how to get Folders from
			     the Store.

	example.client.FolderTreeNode  =    subclass of Swing's DefaultMutableTreeNode.
			     If the folder has messages, it will create
			     a example.client.FolderViewer.  Otherwise it will add the
			     subfolders to the tree.

	example.client.SimpleAuthenticator = subclass of jakarta.mail.Authenticator. If
			     the Store is missing the username or the
			     password, this authenticator will be used.
			     It displays a dialog requesting the
			     information from the user.
				

Viewing Folders:

	example.client.FolderViewer    =    Uses a Swing Table to display all of the
			     Message in a Folder.  The "model" of the
			     data for this Table is a example.client.FolderModel which
			     knows how to get displayable information
			     from a Message.

Jakarta Activation Viewers:

	example.client.MessageViewer   =    Uses the content of the DataHandler.  The
			     content will be a jakarta.mail.Message
			     object.  Displays the headers and then
			     uses Jakarta Activation to find another viewer for
			     the content type of the Message.  (either
			     multipart/mixed, image/gif, or text/plain)

	example.client.MultipartViewer =    Uses the content of the DataHandler.  The
			     content will be a jakarta.mail.Multipart
			     object.  Uses Jakarta Activation to find another
			     viewer for the first BodyPart's content.
			     Also puts Buttons (as "attachments") for
			     the rest of the BodyParts.  When the
			     Button are pressed, it uses Jakarta Activation to
			     find a viewer for the BodyPart's content,
			     and displays it in a separate frame (using
			     example.client.ComponentFrame).

	example.client.TextViewer      =    Uses the content of the DataHandler.  The
			     content will be either a java.lang.String
			     object, or a java.io.InputStream object.
			     Creates a TextArea and sets the text using
			     the String or InputStream.

Support Classes:

	example.client.ComponentFrame  =    support class which takes a java.awt.Component
			     and displays it in a Frame.
