/*
 * Copyright (c) 1997, 2021 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

import java.awt.*;
import java.awt.event.*;
import jakarta.mail.*;
import jakarta.activation.*;
import java.util.Date;
import java.io.IOException;
import javax.swing.JPanel;

/**
 * @author	Christopher Cotton
 * @author	Bill Shannon
 */

public class MessageViewer extends JPanel implements CommandObject {
    
    Message	displayed = null;
    DataHandler	dataHandler = null;
    String	verb = null;
    Component	mainbody;
    TextArea	headers;

    public MessageViewer() {
	this(null);
    }
    
    public MessageViewer(Message what) {
	// set our layout
	super(new GridBagLayout());

	// add the toolbar
	addToolbar();

	GridBagConstraints gb = new GridBagConstraints();
	gb.gridwidth = GridBagConstraints.REMAINDER;
	gb.fill = GridBagConstraints.BOTH;
	gb.weightx = 1.0;
	gb.weighty = 0.0;

	// add the headers
	headers = new TextArea("", 4, 80, TextArea.SCROLLBARS_NONE);
	headers.setEditable(false);
	add(headers, gb);

	// now display our message
	setMessage(what);
    }
    
    /**
     * sets the current message to be displayed in the viewer
     */
    public void setMessage(Message what) {
	displayed = what;

	if (mainbody != null)
	    remove(mainbody);

	if (what != null) {
	    loadHeaders();
	    mainbody = getBodyComponent();
	} else {
	    headers.setText("");
	    TextArea dummy = new TextArea("", 24, 80, TextArea.SCROLLBARS_NONE);
	    dummy.setEditable(false);
	    mainbody = dummy;
	}

	// add the main body
	GridBagConstraints gb = new GridBagConstraints();
	gb.gridwidth = GridBagConstraints.REMAINDER;
	gb.fill = GridBagConstraints.BOTH;
	gb.weightx = 1.0;
	gb.weighty = 1.0;
	add(mainbody, gb);

	invalidate();
	validate();
    }

    protected void addToolbar() {
	GridBagConstraints gb = new GridBagConstraints();
	gb.gridheight = 1;
	gb.gridwidth = 1;
	gb.fill = GridBagConstraints.NONE;
	gb.anchor = GridBagConstraints.WEST;
	gb.weightx = 0.0;
	gb.weighty = 0.0;
	gb.insets = new Insets(4,4,4,4);

	// structure button
	gb.gridwidth = GridBagConstraints.REMAINDER; // only for the last one
	Button b = new Button("Structure");
	b.addActionListener( new StructureAction());
	add(b, gb);
    }

    protected void loadHeaders() {
	// setup what we want in our viewer
	StringBuffer sb = new StringBuffer();

	// date
	sb.append("Date: ");
	try {
	    Date duh = displayed.getSentDate();
	    if (duh != null) {
		sb.append(duh.toString());
	    } else {
		sb.append("Unknown");
	    }
	    
	    sb.append("\n");

	    // from
	    sb.append("From: ");
	    Address[] adds = displayed.getFrom();
	    if (adds != null && adds.length > 0) {
		sb.append(adds[0].toString());
	    }
	    sb.append("\n");

	    // to
	    sb.append("To: ");
	    adds = displayed.getRecipients(Message.RecipientType.TO);
	    if (adds != null && adds.length > 0) {
		sb.append(adds[0].toString());
	    }
	    sb.append("\n");

	    // subject
	    sb.append("Subject: ");
	    sb.append(displayed.getSubject());
	    
	    headers.setText(sb.toString());
	} catch (MessagingException me) {
	    headers.setText("");
	}
    }

    protected Component getBodyComponent() {
	//------------
	// now get a content viewer for the main type...
	//------------
	try {
	    DataHandler dh = displayed.getDataHandler();
	    CommandInfo ci = dh.getCommand("view");
	    if (ci == null) {
		throw new MessagingException("view command failed on: " +
					     displayed.getContentType());
	    }

	    Object bean = dh.getBean(ci);
	    if (bean instanceof Component) {
		return (Component)bean;
	    } else {
		throw new MessagingException("bean is not a component " +
					     bean.getClass().toString());
	    }
	} catch (MessagingException me) {
	    return new Label(me.toString());
	}
    }
    
    /**
     * the CommandObject method to accept our DataHandler
     * @param dh	the datahandler used to get the content
     */
    public void setCommandContext(String verb,
				  DataHandler dh) throws IOException {
	this.verb = verb;
	dataHandler = dh;

	Object o = dh.getContent();
	if (o instanceof Message) {
	    setMessage((Message)o);
	}
	else {
	    System.out.println( 
		"MessageViewer - content not a Message object, " + o);
	    if (o != null){
		System.out.println(o.getClass().toString());
	    }
	}
    }


    class StructureAction implements ActionListener {
	StringBuffer sb;

	public void actionPerformed(ActionEvent e) {
	    System.out.println("\n\nMessage Structure");
	    dumpPart("", displayed);
	}

	protected void dumpPart(String prefix, Part p) {
	    try {
		System.out.println(prefix + "----------------");
		System.out.println(prefix + 
				   "Content-Type: " + p.getContentType());
		System.out.println(prefix + 
				   "Class: " + p.getClass().toString());
			    
		Object o = p.getContent();
		if (o == null) {
		    System.out.println(prefix + "Content:  is null");
		} else {
		    System.out.println(prefix +
				       "Content: " + o.getClass().toString());
		}

		if (o instanceof Multipart) {
		    String newpref = prefix + "\t";
		    Multipart mp = (Multipart)o;
		    int count = mp.getCount();
		    for (int i = 0; i < count; i++) {
			dumpPart(newpref, mp.getBodyPart(i));
		    }
		}
	    } catch (MessagingException e) {
		e.printStackTrace();
	    } catch (IOException ioex) {
		System.out.println("Cannot get content" + ioex.getMessage());
	    }
	}
    }
}
