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

package com.sun.mail.imap.protocol;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

import com.sun.mail.iap.Argument;
import com.sun.mail.iap.ProtocolException;
import com.sun.mail.iap.Response;
import com.sun.mail.util.ASCIIUtility;
import com.sun.mail.util.MailLogger;
import com.sun.mail.util.PropUtil;

/**
 * This class contains a single method that does authentication using
 * SASL.  This is in a separate class so that it can be compiled with
 * J2SE 1.5.  Eventually it should be merged into IMAPProtocol.java.
 */

public class IMAPSaslAuthenticator implements SaslAuthenticator {

    private IMAPProtocol pr;
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

    public IMAPSaslAuthenticator(IMAPProtocol pr, String name, Properties props,
				MailLogger logger, String host) {
	this.pr = pr;
	this.name = name;
	this.props = props;
	this.logger = logger;
	this.host = host;
    }

    @Override
    public boolean authenticate(String[] mechs, final String realm,
				final String authzid, final String u,
				final String p) throws ProtocolException {

	synchronized (pr) {	// authenticate method should be synchronized
	List<Response> v = new ArrayList<>();
	String tag = null;
	Response r = null;
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

	try {
	    Argument args = new Argument();
	    args.writeAtom(sc.getMechanismName());
	    if (pr.hasCapability("SASL-IR") && sc.hasInitialResponse()) {
		String irs;
		byte[] ba = sc.evaluateChallenge(new byte[0]);
		if (ba.length > 0) {
		    ba = Base64.getEncoder().encode(ba);
		    irs = ASCIIUtility.toString(ba, 0, ba.length);
		} else
		    irs = "=";
		args.writeAtom(irs);
	    }
	    tag = pr.writeCommand("AUTHENTICATE", args);
	} catch (Exception ex) {
	    logger.log(Level.FINE, "SASL AUTHENTICATE Exception", ex);
	    return false;
	}

	OutputStream os = pr.getIMAPOutputStream(); // stream to IMAP server

	/*
	 * Wrap a BASE64Encoder around a ByteArrayOutputstream
	 * to craft b64 encoded username and password strings
	 *
	 * Note that the encoded bytes should be sent "as-is" to the
	 * server, *not* as literals or quoted-strings.
	 *
	 * Also note that unlike the B64 definition in MIME, CRLFs 
	 * should *not* be inserted during the encoding process. So, I
	 * use Integer.MAX_VALUE (0x7fffffff (> 1G)) as the bytesPerLine,
	 * which should be sufficiently large !
	 */

	ByteArrayOutputStream bos = new ByteArrayOutputStream();
	byte[] CRLF = { (byte)'\r', (byte)'\n'};

	// Hack for Novell GroupWise XGWTRUSTEDAPP authentication mechanism
	// http://www.novell.com/developer/documentation/gwimap/?
	//   page=/developer/documentation/gwimap/gwimpenu/data/al7te9j.html
	boolean isXGWTRUSTEDAPP =
	    sc.getMechanismName().equals("XGWTRUSTEDAPP") &&
	    PropUtil.getBooleanProperty(props,
		"mail." + name + ".sasl.xgwtrustedapphack.enable", true);
	while (!done) { // loop till we are done
	    try {
		r = pr.readResponse();
	    	if (r.isContinuation()) {
		    byte[] ba = null;
		    if (!sc.isComplete()) {
			ba = r.readByteArray().getNewBytes();
			if (ba.length > 0)
			    ba = Base64.getDecoder().decode(ba);
			if (logger.isLoggable(Level.FINE))
			    logger.fine("SASL challenge: " +
				ASCIIUtility.toString(ba, 0, ba.length) + " :");
			ba = sc.evaluateChallenge(ba);
		    }
		    if (ba == null) {
			logger.fine("SASL no response");
			os.write(CRLF); // write out empty line
			os.flush(); 	// flush the stream
			bos.reset(); 	// reset buffer
		    } else {
			if (logger.isLoggable(Level.FINE))
			    logger.fine("SASL response: " +
				ASCIIUtility.toString(ba, 0, ba.length) + " :");
			ba = Base64.getEncoder().encode(ba);
			if (isXGWTRUSTEDAPP)
			    bos.write(ASCIIUtility.getBytes("XGWTRUSTEDAPP "));
			bos.write(ba);

			bos.write(CRLF); 	// CRLF termination
			os.write(bos.toByteArray()); // write out line
			os.flush(); 	// flush the stream
			bos.reset(); 	// reset buffer
		    }
		} else if (r.isTagged() && r.getTag().equals(tag))
		    // Ah, our tagged response
		    done = true;
		else if (r.isBYE()) // outta here
		    done = true;
		else // hmm .. unsolicited response here ?!
		    v.add(r);
	    } catch (Exception ioex) {
		logger.log(Level.FINE, "SASL Exception", ioex);
		// convert this into a BYE response
		r = Response.byeResponse(ioex);
		done = true;
		// XXX - ultimately return true???
	    }
	}

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

	Response[] responses = v.toArray(new Response[v.size()]);

	// handle an illegal but not uncommon untagged CAPABILTY response
	pr.handleCapabilityResponse(responses);

	/*
	 * Dispatch untagged responses.
	 * NOTE: in our current upper level IMAP classes, we add the
	 * responseHandler to the Protocol object only *after* the 
	 * connection has been authenticated. So, for now, the below
	 * code really ends up being just a no-op.
	 */
	pr.notifyResponseHandlers(responses);

	// Handle the final OK, NO, BAD or BYE response
	pr.handleLoginResult(r);
	pr.setCapabilities(r);

	/*
	 * If we're using the Novell Groupwise XGWTRUSTEDAPP mechanism
	 * to run as a specified authorization ID, we have to issue a
	 * LOGIN command to select the user we want to operate as.
	 */
	if (isXGWTRUSTEDAPP && authzid != null) {
	    Argument args = new Argument();
	    args.writeString(authzid);

	    responses = pr.command("LOGIN", args);

	    // dispatch untagged responses
	    pr.notifyResponseHandlers(responses);

	    // Handle result of this command
	    pr.handleResult(responses[responses.length-1]);
	    // If the response includes a CAPABILITY response code, process it
	    pr.setCapabilities(responses[responses.length-1]);
	}
	return true;
    }
    }
}
