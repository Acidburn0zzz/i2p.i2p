/*
 * Created on Nov 4, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.1 $
 */
package i2p.susi.webmail.pop3;

import i2p.susi.debug.Debug;
import i2p.susi.webmail.Messages;
import i2p.susi.webmail.NewMailListener;
import i2p.susi.webmail.WebMail;
import i2p.susi.util.Config;
import i2p.susi.util.ReadBuffer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.data.DataHelper;
import net.i2p.util.InternalSocket;

/**
 * @author susi23
 */
public class POP3MailBox implements NewMailListener {

	private final String host, user, pass;

	private String lastLine, lastError;

	private final int port;
	private int mails;

	private boolean connected;
	private boolean gotCAPA;
	private boolean supportsPipelining;
	private boolean supportsTOP;
	private boolean supportsUIDL;

	/** ID to size */
	private final HashMap<Integer, Integer> sizes;
	/** UIDL to ID */
	private final HashMap<String, Integer> uidlToID;

	private Socket socket;
	private final AtomicLong lastActive;
	private final AtomicLong lastChecked;

	private final Object synchronizer;
	private final DelayedDeleter delayedDeleter;
	// instantiated after first successful connection
	private BackgroundChecker backgroundChecker;
	// instantiated after every successful connection
	private IdleCloser idleCloser;
	private volatile NewMailListener newMailListener;

	/**
	 * Does not connect. Caller must call connectToServer() if desired.
	 *
	 * @param host
	 * @param port
	 * @param user
	 * @param pass
	 */
	public POP3MailBox(String host, int port, String user, String pass) {
		Debug.debug(
			Debug.DEBUG,
			"Mailbox(" + host + "," + port + "," + user + ",password)");
		this.host = host;
		this.port = port;
		this.user = user;
		this.pass = pass;
		uidlToID = new HashMap<String, Integer>();
		sizes = new HashMap<Integer, Integer>();
		synchronizer = new Object();
		// this appears in the UI so translate
		lastLine = _t("No response from server");
		lastActive = new AtomicLong(System.currentTimeMillis());
		lastChecked = new AtomicLong();
		delayedDeleter = new DelayedDeleter(this);
	}

	/**
	 * Fetch the header. Does not cache.
	 * 
	 * @param uidl
	 * @return Byte buffer containing header data or null
	 */
	public ReadBuffer getHeader( String uidl ) {
		synchronized( synchronizer ) {
			try {
				// we must be connected to know the UIDL to ID mapping
				checkConnection();
			} catch (IOException ioe) {
				Debug.debug( Debug.DEBUG, "Error fetching header: " + ioe);
				return null;
			}
			int id = getIDfromUIDL(uidl);
			if (id < 0)
				return null;
			return getHeader(id);
		}
	}

	/**
	 * retrieves header from pop3 server (with TOP command and RETR as fallback)
	 * Caller must sync.
	 * 
	 * @param id message id
	 * @return Byte buffer containing header data or null
	 */
	private ReadBuffer getHeader( int id ) {
			Debug.debug(Debug.DEBUG, "getHeader(" + id + ")");
			ReadBuffer header = null;
			if (id >= 1 && id <= mails) {
				/*
				 * try 'TOP n 0' command
				 */
				header = sendCmdN("TOP " + id + " 0" );
				if( header == null) {
					/*
					 * try 'RETR n' command
					 */
					header = sendCmdN("RETR " + id );
					if (header == null)
						Debug.debug( Debug.DEBUG, "RETR returned null" );
				}
			} else {
				lastError = "Message id out of range.";
			}
			return header;
	}

	/**
	 * Fetch the body. Does not cache.
	 * 
	 * @param uidl
	 * @return Byte buffer containing body data or null
	 */
	public ReadBuffer getBody( String uidl ) {
		synchronized( synchronizer ) {
			try {
				// we must be connected to know the UIDL to ID mapping
				checkConnection();
			} catch (IOException ioe) {
				Debug.debug( Debug.DEBUG, "Error fetching body: " + ioe);
				return null;
			}
			int id = getIDfromUIDL(uidl);
			if (id < 0)
				return null;
			return getBody(id);
		}
	}

	/**
	 * Fetch headers and/or bodies. Does not cache.
	 * ReadBuffer objects are inserted into the requests.
	 * No total time limit.
	 * 
	 * @since 0.9.13
	 */
	public void getBodies(Collection<FetchRequest> requests) {
		List<SendRecv> srs = new ArrayList<SendRecv>(requests.size());
		synchronized( synchronizer ) {
			try {
				// we must be connected to know the UIDL to ID mapping
				checkConnection();
			} catch (IOException ioe) {
				Debug.debug( Debug.DEBUG, "Error fetching: " + ioe);
				return;
			}
			for (FetchRequest fr : requests) {
				int id = getIDfromUIDL(fr.getUIDL());
				if (id < 0)
					continue;
				SendRecv sr;
				if (fr.getHeaderOnly() && supportsTOP)
					sr = new SendRecv("TOP " + id + " 0", Mode.RB);
				else
					sr = new SendRecv("RETR " + id, Mode.RB);
				sr.savedObject = fr;
				srs.add(sr);
			}
			if (srs.isEmpty())
				return;
			try {
				sendCmds(srs);
			} catch (IOException ioe) {
				Debug.debug( Debug.DEBUG, "Error fetching bodies: " + ioe);
				// todo maybe
			}
		}
		for (SendRecv sr : srs) {
			if (sr.result) {
				FetchRequest fr = (FetchRequest) sr.savedObject;
				fr.setBuffer(sr.rb);
			}
		}
	}
	
	/**
	 * retrieve message body from pop3 server (via RETR command)
	 * Caller must sync.
	 * 
	 * @param id message id
	 * @return Byte buffer containing body data or null
	 */
	private ReadBuffer getBody(int id) {
			Debug.debug(Debug.DEBUG, "getBody(" + id + ")");
			ReadBuffer body = null;
			if (id >= 1 && id <= mails) {
				try {
					body = sendCmdN( "RETR " + id );
					if (body == null)
						Debug.debug( Debug.DEBUG, "RETR returned null" );
				} catch (OutOfMemoryError oom) {
					Debug.debug( Debug.ERROR, "OOM fetching mail" );
					lastError = oom.toString();
					close();
				}
			}
			else {
				lastError = "Message id out of range.";
			}
			return body;
	}

	/**
	 * Call performDelete() after this or they will come back
	 * UNUSED
	 * 
	 * @param uidl
	 * @return Success of delete operation: true if successful.
	 */
/****
	public boolean delete( String uidl )
	{
		Debug.debug(Debug.DEBUG, "delete(" + uidl + ")");
		synchronized( synchronizer ) {
			try {
				// we must be connected to know the UIDL to ID mapping
				checkConnection();
			} catch (IOException ioe) {
				Debug.debug( Debug.DEBUG, "Error deleting: " + ioe);
				return false;
			}
			int id = getIDfromUIDL(uidl);
			if (id < 0)
				return false;
			return delete(id);
		}
	}
****/

	/**
	 * Queue for later deletion. Non-blocking.
	 * 
	 * @since 0.9.13
	 */
	public void queueForDeletion(Collection<String> uidls) {
		for (String uidl : uidls) {
			queueForDeletion(uidl);
		}
	}

	/**
	 * Queue for later deletion. Non-blocking.
	 * 
	 * @since 0.9.13
	 */
	public void queueForDeletion(String uidl) {
		Debug.debug(Debug.DEBUG, "Queueing for deletion: " + uidl);
		delayedDeleter.queueDelete(uidl);
	}

	/**
	 * Delete all at once and close. Does not reconnect.
	 * Do NOT call performDelete() after this.
	 * Returns all UIDLs successfully deleted OR were not known by the server.
	 * 
	 * @since 0.9.13
	 */
	Collection<String> delete(Collection<String> uidls) {
		List<String> rv = new ArrayList<String>(uidls.size());
		List<SendRecv> srs = new ArrayList<SendRecv>(uidls.size() + 1);
		synchronized( synchronizer ) {
			try {
				// we must be connected to know the UIDL to ID mapping
				checkConnection();
			} catch (IOException ioe) {
				Debug.debug( Debug.DEBUG, "Error deleting: " + ioe);
				return rv;
			}
			for (String uidl : uidls) {
				int id = getIDfromUIDL(uidl);
				if (id < 0) {
					// presumed already deleted
					rv.add(uidl);
					continue;
				}
				SendRecv sr = new SendRecv("DELE " + id, Mode.A1);
				sr.savedObject = uidl;
				srs.add(sr);
			}
			if (srs.isEmpty())
				return rv;
			// TODO don't quit now, just set timer to quit later
			SendRecv quit = new SendRecv("QUIT", Mode.A1);
			srs.add(quit);
			try {
				sendCmds(srs);
				// do NOT call close() here, we included QUIT above
				try {
					socket.close();
				} catch (IOException e) {}
				clear();
				// result of QUIT
				boolean success = srs.get(srs.size() - 1).result;
				if (success) {
					for (int i = 0; i < srs.size() - 1; i++) {
						SendRecv sr = srs.get(i);
						// ignore sr.result, if it failed it's because
						// it's already deleted
						rv.add((String) sr.savedObject);
					}
				}
				// why reconnect?
				//connect();
			} catch (IOException ioe) {
				Debug.debug( Debug.DEBUG, "Error deleting: " + ioe);
				// todo maybe
			}
		}
		return rv;
	}
	
	/**
	 * delete message on pop3 server
	 * UNUSED
	 * 
	 * @param id message id
	 * @return Success of delete operation: true if successful.
	 */
/****
	private boolean delete(int id)
	{
		Debug.debug(Debug.DEBUG, "delete(" + id + ")");
		
		boolean result = false;
		
		synchronized( synchronizer ) {
			
			try {
				result = sendCmd1a( "DELE " + id );
			}
			catch (IOException e) {
			}
		}
		return result;
	}
****/

	/**
	 * Get cached size of a message (via previous LIST command).
	 * 
	 * @param uidl
	 * @return Message size in bytes or 0 if not found
	 */
	public int getSize( String uidl ) {
		synchronized( synchronizer ) {
			int id = getIDfromUIDL(uidl);
			if (id < 0)
				return 0;
			return getSize(id);
		}
	}
	
	/**
	 * Get cached size of a message (via previous LIST command).
	 * Caller must sync.
	 * 
	 * @param id message id
	 * @return Message size in bytes or 0 if not found
	 */
	private int getSize(int id) {
			int result = 0;
			/*
			 * find value in hashtable
			 */
			Integer resultObj = sizes.get(Integer.valueOf(id));
			if (resultObj != null)
				result = resultObj.intValue();
			Debug.debug(Debug.DEBUG, "getSize(" + id + ") = " + result);
			return result;
	}

	/**
	 * Is the connection is still alive
	 * 
	 * @return true or false
	 */
	public boolean isConnected() {
		if (socket == null
			|| !socket.isConnected()
			|| socket.isInputShutdown()
			|| socket.isOutputShutdown()
			|| socket.isClosed()) {
			connected = false;
		}
		return connected;
	}

	/**
	 * If not connected, connect now.
	 * Should be called from all public methods before sending a command.
	 * Caller must sync.
	 * 
	 * @return true or false
	 */
	private void checkConnection() throws IOException {
		Debug.debug(Debug.DEBUG, "checkConnection()");
		if (!isConnected()) {
			connect();
			if (!isConnected())
				throw new IOException("Cannot connect");
		}
	}

	/**
	 * Timestamp.
	 * 
	 * @since 0.9.13
	 */
	private void updateActivity() {
		lastActive.set(System.currentTimeMillis());
	}

	/**
	 * Timestamp.
	 * 
	 * @since 0.9.13
	 */
	long getLastActivity() {
		return lastActive.get();
	}

	/**
	 * Timestamp. When we last successfully got the UIDL list.
	 * 
	 * @since 0.9.13
	 */
	long getLastChecked() {
		return lastChecked.get();
	}

	/**
	 * 
	 * @param response line starting with +OK
	 */
	private void updateMailCount(String response) {
		if (response == null || response.length() < 4) {
			mails = 0;
			return;
		}
		response = response.trim();
		try {
			int i = response.indexOf(' ', 5);
			mails =
				Integer.parseInt(
					i != -1
						? response.substring(4, i)
						: response.substring(4));
		} catch (NumberFormatException nfe) {
			mails = 0;
		}
	}

	/**
	 * Caller must sync.
	 * 
	 * @throws IOException
	 */
	private void updateUIDLs(List<String> lines) {
			uidlToID.clear();
			if (lines != null) {
				for (String line : lines) {
					int j = line.indexOf(' ');
					if( j != -1 ) {
						try {
							int n = Integer.parseInt( line.substring( 0, j ) );
							String uidl = line.substring(j + 1).trim();
							uidlToID.put( uidl, Integer.valueOf( n ) );
						} catch (NumberFormatException nfe) {
							Debug.debug(Debug.DEBUG, "UIDL error " + nfe);
						} catch (IndexOutOfBoundsException ioobe) {
							Debug.debug(Debug.DEBUG, "UIDL error " + ioobe);
						}
					}
				}
				lastChecked.set(System.currentTimeMillis());
			} else {
				Debug.debug(Debug.DEBUG, "Error getting UIDL list from server.");
			}
	}

	/**
	 * Caller must sync.
	 * 
	 * @throws IOException
	 */
	private void updateSizes(List<String> lines) {
		/*
		 * try LIST
		 */
		sizes.clear();
		if (lines != null) {
			for (String line : lines) {
				int j = line.indexOf(' ');
				if (j != -1) {
					try {
						int key = Integer.parseInt(line.substring(0, j));
						int value = Integer.parseInt(line.substring(j + 1).trim());
						sizes.put(Integer.valueOf(key), Integer.valueOf(value));
					} catch (NumberFormatException nfe) {
						Debug.debug(Debug.DEBUG, "LIST error " + nfe);
					}
				}
			}
		} else {
			Debug.debug(Debug.DEBUG, "Error getting LIST from server.");
		}
	}

	/**
	 * 
	 *
	 */
	public void refresh() {
		synchronized( synchronizer ) {
			close(true);
			connect();
		}
	}

	/**
	 * Caller must sync.
	 */
	private void clear()
	{
		uidlToID.clear();
		sizes.clear();
		mails = 0;
	}

	/**
	 * Connect to pop3 server if not connected.
	 * Does nothing if already connected.
	 *
	 * @return true if connected
	 * @since 0.9.13
	 */
	public boolean connectToServer() {
		synchronized( synchronizer ) {
			if (isConnected())
				return true;
			connect();
			return isConnected();
		}
	}

	/**
	 * connect to pop3 server, login with USER and PASS and try STAT then
	 *
	 * Caller must sync.
	 */
	private void connect() {
		Debug.debug(Debug.DEBUG, "connect()");
		if (Debug.getLevel() == Debug.DEBUG)
			(new Exception("I did it")).printStackTrace();

		clear();
		
		if (socket != null && socket.isConnected())
			close();
		
		try {
			socket = InternalSocket.getSocket(host, port);
		} catch (IOException e) {
			Debug.debug( Debug.DEBUG, "Error connecting: " + e);
			lastError = _t("Cannot connect") + " (" + host + ':' + port + ") - " + e.getLocalizedMessage();
			return;
		}
		if (socket != null) {
			try {
				// pipeline 2 commands
				lastError = "";
				socket.setSoTimeout(120*1000);
				boolean ok = doHandshake();
				if (ok) {
					// TODO APOP (unsupported by postman)
					List<SendRecv> cmds = new ArrayList<SendRecv>(4);
					cmds.add(new SendRecv("USER " + user, Mode.A1));
					cmds.add(new SendRecv("PASS " + pass, Mode.A1));
					socket.setSoTimeout(60*1000);
					ok =  sendCmds(cmds);
				}
				if (ok) {
					connected = true;
					List<SendRecv> cmds = new ArrayList<SendRecv>(4);
					SendRecv stat = new SendRecv("STAT", Mode.A1);
					cmds.add(stat);
					SendRecv uidl = new SendRecv("UIDL", Mode.LS);
					cmds.add(uidl);
					SendRecv list = new SendRecv("LIST", Mode.LS);
					cmds.add(list);
					// check individual responses
					socket.setSoTimeout(120*1000);
					ok = sendCmds(cmds);
					if (stat.result)
						updateMailCount(stat.response);
					else
						Debug.debug(Debug.DEBUG, "STAT failed");
					if (uidl.result)
						updateUIDLs(uidl.ls);
					else
						Debug.debug(Debug.DEBUG, "UIDL failed");
					if (list.result)
						updateSizes(list.ls);
					else
						Debug.debug(Debug.DEBUG, "LIST failed");
					socket.setSoTimeout(300*1000);
					if (ok && backgroundChecker == null &&
						Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_BACKGROUND_CHECK)))
						backgroundChecker = new BackgroundChecker(this);
					if (ok && idleCloser == null)
						idleCloser = new IdleCloser(this);
				} else {
					if (lastError.equals(""))
						lastError = _t("Error connecting to server");
					close();
				}
			}
			catch (NumberFormatException e1) {
				lastError = _t("Error opening mailbox") + ": " + e1;
			}
			catch (IOException e1) {
				lastError = _t("Error opening mailbox") + ": " + e1.getLocalizedMessage();
			}
		}
	}

	/**
	 * Check the initial response, send CAPA, check the CAPA result
	 * Caller must sync.
	 * 
	 * @return true if successful
	 * @throws IOException
	 * @since 0.9.13
	 */
	private boolean doHandshake() throws IOException {
		List<SendRecv> cmds = new ArrayList<SendRecv>(2);
		cmds.add(new SendRecv(null, Mode.A1));
		SendRecv capa = null;
		if (gotCAPA) {
			Debug.debug(Debug.DEBUG, "Skipping CAPA");
		} else {
			capa = new SendRecv("CAPA", Mode.LS);
			cmds.add(capa);
		}
		boolean rv = sendCmds(cmds);
		if (rv && capa != null) {
			if (capa.ls != null) {
				for (String cap : capa.ls) {
					String t = cap.trim();
					if (t.equals("PIPELINING"))
						supportsPipelining = true;
					else if (t.equals("UIDL"))
						supportsUIDL = true;
					else if (t.equals("TOP"))
						supportsTOP = true;
				}
			}
			gotCAPA = true;
			Debug.debug(Debug.DEBUG, "POP3 server caps: pipelining? " + supportsPipelining +
		                                           " UIDL? " + supportsUIDL +
		                                           " TOP? " + supportsTOP);
		}
		return rv;
	}
	
	/**
	 * send command to pop3 server (and expect single line answer)
	 * Response will be in lastLine. Does not read past the first line of the response.
	 * Caller must sync.
	 * 
	 * @param cmd command to send
	 * @return true if command was successful (+OK)
	 * @throws IOException
	 */
	private boolean sendCmd1a(String cmd) throws IOException {
		boolean result = false;
		sendCmd1aNoWait(cmd);
		socket.getOutputStream().flush();
		String foo = DataHelper.readLine(socket.getInputStream());
		updateActivity();
		// Debug.debug(Debug.DEBUG, "sendCmd1a: read " + read + " bytes");
		if (foo != null) {
			lastLine = foo;
			if (lastLine.startsWith("+OK")) {
				if (cmd.startsWith("PASS"))
					cmd = "PASS provided";
				Debug.debug(Debug.DEBUG, "sendCmd1a: (" + cmd + ") success: \"" + lastLine.trim() + '"');
				result = true;
			} else {
				if (cmd.startsWith("PASS"))
					cmd = "PASS provided";
				Debug.debug(Debug.DEBUG, "sendCmd1a: (" + cmd + ") FAIL: \"" + lastLine.trim() + '"');
				lastError = lastLine;
			}
		} else {
			Debug.debug(Debug.DEBUG, "sendCmd1a: (" + cmd + ") NO RESPONSE");
			lastError = _t("No response from server");
			throw new IOException(lastError);
		}
		return result;
	}

	/**
	 * Send commands to pop3 server all at once (and expect answers).
	 * Sets lastError to the FIRST error.
	 * Caller must sync.
	 * 
	 * @param cmd command to send
	 * @param rcvLines lines to receive
	 * @return true if ALL received lines were successful (+OK)
	 * @throws IOException
	 * @since 0.9.13
	 */
	private boolean sendCmds(List<SendRecv> cmds) throws IOException {
		boolean result = true;
		boolean pipe = supportsPipelining;
		if (pipe) {
			Debug.debug(Debug.DEBUG, "POP3 pipelining " + cmds.size() + " commands");
			for (SendRecv sr : cmds) {
				String cmd = sr.send;
				if (cmd != null)
					sendCmd1aNoWait(cmd);
			}
		} // else we will do it below
		socket.getOutputStream().flush();
		InputStream in = socket.getInputStream();
		int i = 0;
		for (SendRecv sr : cmds) {
			if (!pipe) {
				String cmd = sr.send;
				if (cmd != null) {
					sendCmd1aNoWait(cmd);
					socket.getOutputStream().flush();
				}
			}
			String foo = DataHelper.readLine(in);
			updateActivity();
			if (foo == null) {
				lastError = _t("No response from server");
				throw new IOException(lastError);
			}
			sr.response = foo.trim();
			i++;
			if (!foo.startsWith("+OK")) {
				Debug.debug(Debug.DEBUG, "Fail after " + i + " of " + cmds.size() + " responses: \"" + foo.trim() + '"');
				if (result)
				    lastError = foo;   // actually the first error, for better info to the user
				result = false;
				sr.result = false;
			} else {
				Debug.debug(Debug.DEBUG, "OK after " + i + " of " + cmds.size() + " responses: \"" + foo.trim() + '"');
				switch (sr.mode) {
				    case A1:
					sr.result = true;
					break;

				    case RB:
					try {
						sr.rb = getResultNa();
						sr.result = true;
					} catch (IOException ioe) {
						Debug.debug( Debug.DEBUG, "Error getting RB: " + ioe);
						result = false;
						sr.result = false;
					}
					break;

				    case LS:
					try {
						sr.ls = getResultNl();
						sr.result = true;
					} catch (IOException ioe) {
						Debug.debug( Debug.DEBUG, "Error getting LS: " + ioe);
						result = false;
						sr.result = false;
					}
					break;
				}
			}
			lastLine = foo;
		}
		return result;
	}
	
	/**
	 * send command to pop3 server. Does NOT flush or read or wait.
	 * Caller must sync.
	 * 
	 * @param cmd command to send non-null
	 * @throws IOException
         * @since 0.9.13
	 */
	private void sendCmd1aNoWait(String cmd) throws IOException {
		/*
		 * dont log password
		 */
		String msg = cmd;
		if (msg.startsWith("PASS"))
			msg = "PASS provided";
		Debug.debug(Debug.DEBUG, "sendCmd1a(" + msg + ")");
		cmd += "\r\n";
		socket.getOutputStream().write(DataHelper.getASCII(cmd));
		updateActivity();
	}

	/**
	 * Tries twice
	 * Caller must sync.
	 * 
	 * @return buffer or null
	 */
	private ReadBuffer sendCmdN(String cmd )
	{
		synchronized (synchronizer) {
			try {
				return sendCmdNa(cmd);
			} catch (IOException e) {
				lastError = e.toString();
				Debug.debug( Debug.DEBUG, "sendCmdNa throws: " + e);
			}
			connect();
			if (connected) {
				try {
					return sendCmdNa(cmd);
				} catch (IOException e2) {
					lastError = e2.toString();
					Debug.debug( Debug.DEBUG, "2nd sendCmdNa throws: " + e2);
				}
			} else {
				Debug.debug( Debug.DEBUG, "not connected after reconnect" );					
			}
		}
		return null;
	}

	/**
	 * No total timeout (result could be large)
	 * Caller must sync.
	 *
	 * @return buffer or null
	 * @throws IOException
	 */
	private ReadBuffer sendCmdNa(String cmd) throws IOException
	{
		if (sendCmd1a(cmd)) {
			return getResultNa();
		} else {
			Debug.debug( Debug.DEBUG, "sendCmd1a returned false" );
			return null;
		}
	}

	/**
	 * Like sendCmdNa but returns a list of strings, one per line.
	 * Strings will have trailing \r but not \n.
	 * Total timeout 2 minutes.
	 * Caller must sync.
	 *
	 * @return the lines or null on error
	 * @throws IOException on timeout
         * @since 0.9.13
	 */
	private List<String> sendCmdNl(String cmd) throws IOException
	{
		if (sendCmd1a(cmd)) {
			return getResultNl();
		} else {
			Debug.debug( Debug.DEBUG, "sendCmd1a returned false" );
			return null;
		}
	}

	/**
	 * No total timeout (result could be large)
	 * Caller must sync.
	 *
	 * @return buffer non-null
	 * @throws IOException
	 */
	private ReadBuffer getResultNa() throws IOException
	{
		InputStream input = socket.getInputStream();
		StringBuilder buf = new StringBuilder(512);
		ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
		while (DataHelper.readLine(input, buf)) {
			updateActivity();
			int len = buf.length();
			if (len == 0)
				break; // huh? no \r?
			if (len == 2 && buf.charAt(0) == '.' && buf.charAt(1) == '\r')
				break;
			String line;
			// RFC 1939 sec. 3 de-byte-stuffing
			if (buf.charAt(0) == '.')
				line = buf.substring(1);
			else
				line = buf.toString();
			baos.write(DataHelper.getASCII(line));
			if (buf.charAt(len - 1) != '\r')
				baos.write((byte) '\n');
			baos.write((byte) '\n');
			buf.setLength(0);
		}
		return new ReadBuffer(baos.toByteArray(), 0, baos.size());
	}

	/**
	 * Like getResultNa but returns a list of strings, one per line.
	 * Strings will have trailing \r but not \n.
	 * Total timeout 2 minutes.
	 * Caller must sync.
	 *
	 * @return the lines non-null
	 * @throws IOException on timeout
         * @since 0.9.13
	 */
	private List<String> getResultNl() throws IOException
	{
		List<String> rv = new ArrayList<String>(16);
		long timeOut = 120*1000;
		InputStream input = socket.getInputStream();
		long startTime = System.currentTimeMillis();
		StringBuilder buf = new StringBuilder(512);
		while (DataHelper.readLine(input, buf)) {
			updateActivity();
			int len = buf.length();
			if (len == 0)
				break; // huh? no \r?
			if (len == 2 && buf.charAt(0) == '.' && buf.charAt(1) == '\r')
				break;
			if( System.currentTimeMillis() - startTime > timeOut )
				throw new IOException( "Timeout while waiting on server response." );
			String line;
			// RFC 1939 sec. 3 de-byte-stuffing
			if (buf.charAt(0) == '.')
				line = buf.substring(1);
			else
				line = buf.toString();
			rv.add(line);
			buf.setLength(0);
		}
		return rv;
	}

	/**
	 * Warning - forces a connection.
	 *
	 * @return The amount of e-mails available.
	 */
	public int getNumMails() {
		synchronized( synchronizer ) {
			Debug.debug(Debug.DEBUG, "getNumMails()");
			try {
				checkConnection();
			} catch (IOException ioe) {}
			return connected ? mails : 0;
		}
	}

	/**
	 * @return The most recent error message.
	 */
	public String lastError() {
		//Debug.debug(Debug.DEBUG, "lastError()");
		// Hide the "-ERR" from the user
		String e = lastError;
		if (e.startsWith("-ERR ") && e.length() > 5)
			e = e.substring(5);
		// translate this common error
		if (e.trim().equals("Login failed."))
			e = _t("Login failed");
		return e;
	}


	/**
	 *  Relay from the checker to the webmail session object,
	 *  which relays to MailCache, which will fetch the mail from us
	 *  in a big circle
	 *
	 *  @since 0.9.13
	 */
	public void setNewMailListener(NewMailListener nml) {
		newMailListener = nml;
	}

	/**
	 *  Relay from the checker to the webmail session object,
	 *  which relays to MailCache, which will fetch the mail from us
	 *  in a big circle
	 *
	 *  @since 0.9.13
	 */
	public void foundNewMail() {
		NewMailListener  nml = newMailListener;
		if (nml != null)
			nml.foundNewMail();
	}

	/**
	 *  Close without waiting for response,
	 *  and remove any delayed tasks and resources.
	 */
	public void destroy() {
		delayedDeleter.cancel();
		synchronized( synchronizer ) {
			if (backgroundChecker != null)
				backgroundChecker.cancel();
			close(false);
		}
	}

	/**
	 *  For helper threads to lock
	 *  @since 0.9.13
	 */
	Object getLock() {
		return synchronizer;
	}

	/**
	 *  Do we have UIDLs to delete?
	 *  @since 0.9.13
	 */
	boolean hasQueuedDeletions() {
		return !delayedDeleter.getQueued().isEmpty();
	}

	/**
	 *  Close without waiting for response.
	 *  Deletes all queued deletions.
	 */
	public void close() {
		close(false);
	}

	/**
	 *  Close and optionally wait for response.
	 *  Deletes all queued deletions.
	 *  @since 0.9.13
	 */
	void close(boolean shouldWait) {
		synchronized( synchronizer ) {
			Debug.debug(Debug.DEBUG, "close()");
			if (idleCloser != null)
				idleCloser.cancel();
			if (socket != null && socket.isConnected()) {
				try {
					Collection<String> toDelete = delayedDeleter.getQueued();
					Map<String, Integer> sendDelete = new HashMap<String, Integer>(toDelete.size());
					for (String uidl : toDelete) {
						int id = getIDfromUIDL(uidl);
						if (id >= 0) {
							sendDelete.put(uidl, Integer.valueOf(id));
						}
					}
					if (shouldWait) {
						if (!sendDelete.isEmpty()) {
							// Verify deleted, remove from the delete queue
							// this does the quit and close
							Collection<String> deleted = delete(sendDelete.keySet());
							for (String uidl : deleted) {
								delayedDeleter.removeQueued(uidl);
							}
						} else {
							sendCmd1a("QUIT");
						}
						Debug.debug( Debug.DEBUG, "close() with wait complete");
					} else {
						if (!sendDelete.isEmpty()) {
							// spray and pray the deletions, don't remove from delete queue
							for (Integer id : sendDelete.values()) {
								sendCmd1aNoWait("DELE " + id);
							}
						}
						sendCmd1aNoWait("QUIT");
					}
					socket.close();
				} catch (IOException e) {
					Debug.debug( Debug.DEBUG, "error closing: " + e);
				}
			}
			socket = null;
			connected = false;
			clear();
		}
	}

	/**
	 * returns number of message with given UIDL
	 * Caller must sync.
	 * 
	 * @param uidl
	 * @return Message number or -1
	 */
	private int getIDfromUIDL( String uidl )
	{
		int result = -1;
		Integer intObject = uidlToID.get( uidl );
		if( intObject != null ) {
			result = intObject.intValue();
		}
		return result;
	}

	/**
	 * Unused
	 * @param id
	 * @return UIDL or null
	 */
/****
	public String getUIDLfromID( int id )
	{
		synchronized( synchronizer ) {
			try {
				return uidlList.get( id );
			} catch (IndexOutOfBoundsException ioobe) {
				return null;
			}
		}
	}
****/

	/**
	 * Only if connected. Does not force a connect.
	 * If not connected, returns null.
	 * 
	 * @return A new array of the available UIDLs. No particular order.
	 */
	public Collection<String> getUIDLs()
	{
		if (!isConnected())
			return null;
		synchronized( synchronizer ) {
		       return new ArrayList<String>(uidlToID.keySet());
		}
	}

	/**
	 * 
	 * @param args
	 */
/****
	public static void main( String[] args )
	{
		Debug.setLevel( Debug.DEBUG );
		POP3MailBox mailbox = new POP3MailBox( "localhost", 7660 , "test", "test");
		ReadBuffer readBuffer = mailbox.sendCmdN( "LIST" );
		System.out.println( "list='" + readBuffer + "'" );
	}
****/

	/**
	 *  Close and reconnect. Takes a while.
	 *  UNUSED
	 */
/****
	public void performDelete()
	{
		synchronized( synchronizer ) {
			close(true);
			// why reconnect?
			//connect();
		}
	}
****/

	/** for SendRecv */
	private enum Mode {
		/** no extra lines (sendCmd1a) */
		A1,
		/** return extra lines in ReadBuffer (sendCmdNa) */
		RB,
		/** return extra lines in List of Strings (sendCmdNl) */
		LS
	}

	/**
	 *  A command to send and a mode to receive and return the results
	 *  @since 0.9.13
	 */
	private static class SendRecv {
		public final String send;
		public final Mode mode;
		public String response;
		public boolean result;
		public ReadBuffer rb;
		public List<String> ls;
		// to remember things
		public Object savedObject;

		/** @param s may be null */
		public SendRecv(String s, Mode m) {
			send = s;
			mode = m;
		}
	}

	public interface FetchRequest {
		public String getUIDL();
		public boolean getHeaderOnly();
		public void setBuffer(ReadBuffer buffer);
	}

	/** translate */
	private static String _t(String s) {
		return Messages.getString(s);
	}
}
