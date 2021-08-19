/*
 * Copyright (c) 1997, 2021 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.mail.smtp;

import jakarta.mail.MessagingException;

import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.RealmChoiceCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

import com.sun.mail.util.ASCIIUtility;
import com.sun.mail.util.MailLogger;

/**
 * This class contains a single method that does authentication using
 * SASL.  This is in a separate class so that it can be compiled with
 * J2SE 1.5.  Eventually it should be merged into SMTPTransport.java.
 */

public class SMTPSaslAuthenticator implements SaslAuthenticator {

    private SMTPTransport pr;
    private String name;
    private Properties props;
    private MailLogger logger;
    private String host;

    /*
     * This is a hack to initialize the OAUTH SASL provider just before,
     * and only if, we might need it.  This avoids the need for the user 
     * to initialize it explicitly, or manually configure the security
     * providers file.
     */
    static {
	try {
	    com.sun.mail.auth.OAuth2SaslClientFactory.init();
	} catch (Throwable t) { }
    }

    public SMTPSaslAuthenticator(SMTPTransport pr, String name,
		Properties props, MailLogger logger, String host) {
	this.pr = pr;
	this.name = name;
	this.props = props;
	this.logger = logger;
	this.host = host;
    }

    @Override
    public boolean authenticate(String[] mechs, final String realm,
				final String authzid, final String u,
				final String p) throws MessagingException {

	boolean done = false;
	if (logger.isLoggable(Level.FINE)) {
	    logger.fine("SASL Mechanisms:");
	    for (int i = 0; i < mechs.length; i++)
		logger.fine(" " + mechs[i]);
	    logger.fine("");
	}

	SaslClient sc;
	CallbackHandler cbh = new CallbackHandler() {
	    @Override
	    public void handle(Callback[] callbacks) {
		if (logger.isLoggable(Level.FINE))
		    logger.fine("SASL callback length: " + callbacks.length);
		for (int i = 0; i < callbacks.length; i++) {
		    if (logger.isLoggable(Level.FINE))
			logger.fine("SASL callback " + i + ": " + callbacks[i]);
		    if (callbacks[i] instanceof NameCallback) {
			NameCallback ncb = (NameCallback)callbacks[i];
			ncb.setName(u);
		    } else if (callbacks[i] instanceof PasswordCallback) {
			PasswordCallback pcb = (PasswordCallback)callbacks[i];
			pcb.setPassword(p.toCharArray());
		    } else if (callbacks[i] instanceof RealmCallback) {
			RealmCallback rcb = (RealmCallback)callbacks[i];
			rcb.setText(realm != null ?
				    realm : rcb.getDefaultText());
		    } else if (callbacks[i] instanceof RealmChoiceCallback) {
			RealmChoiceCallback rcb =
			    (RealmChoiceCallback)callbacks[i];
			if (realm == null)
			    rcb.setSelectedIndex(rcb.getDefaultChoice());
			else {
			    // need to find specified realm in list
			    String[] choices = rcb.getChoices();
			    for (int k = 0; k < choices.length; k++) {
				if (choices[k].equals(realm)) {
				    rcb.setSelectedIndex(k);
				    break;
				}
			    }
			}
		    }
		}
	    }
	};

	try {
	    @SuppressWarnings("unchecked")
	    Map<String, ?> propsMap = (Map) props;
	    sc = Sasl.createSaslClient(mechs, authzid, name, host,
					propsMap, cbh);
	} catch (SaslException sex) {
	    logger.log(Level.FINE, "Failed to create SASL client", sex);
	    throw new UnsupportedOperationException(sex.getMessage(), sex);
	}
	if (sc == null) {
	    logger.fine("No SASL support");
	    throw new UnsupportedOperationException("No SASL support");
	}
	if (logger.isLoggable(Level.FINE))
	    logger.fine("SASL client " + sc.getMechanismName());

	int resp;
	try {
	    String mech = sc.getMechanismName();
	    String ir = null;
	    if (sc.hasInitialResponse()) {
		byte[] ba = sc.evaluateChallenge(new byte[0]);
		if (ba.length > 0) {
		    ba = Base64.getEncoder().encode(ba);
		    ir = ASCIIUtility.toString(ba, 0, ba.length);
		} else
		    ir = "=";
	    }
	    if (ir != null)
		resp = pr.simpleCommand("AUTH " + mech + " " + ir);
	    else
		resp = pr.simpleCommand("AUTH " + mech);

	    /*
	     * A 530 response indicates that the server wants us to
	     * issue a STARTTLS command first.  Do that and try again.
	     */
	    if (resp == 530) {
		pr.startTLS();
		if (ir != null)
		    resp = pr.simpleCommand("AUTH " + mech + " " + ir);
		else
		    resp = pr.simpleCommand("AUTH " + mech);
	    }

	    if (resp == 235)
		return true;	// success already!

	    if (resp != 334)
		return false;
	} catch (Exception ex) {
	    logger.log(Level.FINE, "SASL AUTHENTICATE Exception", ex);
	    return false;
	}

	while (!done) { // loop till we are done
	    try {
	    	if (resp == 334) {
		    byte[] ba = null;
		    if (!sc.isComplete()) {
			ba = ASCIIUtility.getBytes(responseText(pr));
			if (ba.length > 0)
			    ba = Base64.getDecoder().decode(ba);
			if (logger.isLoggable(Level.FINE))
			    logger.fine("SASL challenge: " +
				ASCIIUtility.toString(ba, 0, ba.length) + " :");
			ba = sc.evaluateChallenge(ba);
		    }
		    if (ba == null) {
			logger.fine("SASL: no response");
			resp = pr.simpleCommand("");
		    } else {
			if (logger.isLoggable(Level.FINE))
			    logger.fine("SASL response: " +
				ASCIIUtility.toString(ba, 0, ba.length) + " :");
			ba = Base64.getEncoder().encode(ba);
			resp = pr.simpleCommand(ba);
		    }
		} else
		    done = true;
	    } catch (Exception ioex) {
		logger.log(Level.FINE, "SASL Exception", ioex);
		done = true;
		// XXX - ultimately return true???
	    }
	}
	if (resp != 235)
	    return false;

	if (sc.isComplete() /*&& res.status == SUCCESS*/) {
	    String qop = (String)sc.getNegotiatedProperty(Sasl.QOP);
	    if (qop != null && (qop.equalsIgnoreCase("auth-int") ||
				qop.equalsIgnoreCase("auth-conf"))) {
		// XXX - NOT SUPPORTED!!!
		logger.fine(
		    "SASL Mechanism requires integrity or confidentiality");
		return false;
	    }
	}

	return true;
    }

    private static final String responseText(SMTPTransport pr) {
	String resp = pr.getLastServerResponse().trim();
	if (resp.length() > 4)
	    return resp.substring(4);
	else
	    return "";
    }
}
