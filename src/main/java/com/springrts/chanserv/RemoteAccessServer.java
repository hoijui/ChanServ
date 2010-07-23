/*
 * Created on 2006.11.17
 */

package com.springrts.chanserv;


import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Protocol description:
 *
 * - communication is meant to be short - 1 or 2 commands and then connection should be terminated
 * - connection gets closed automatically after 30 seconds of inactivity.
 * - communication is strictly synchronous - when client sends a command server responds with another command.
 *   If same client must query multiple commands, he should either wait for a response after each command
 *   before sending new command, or use new connection for next command.
 *
 * *** COMMAND LIST: ***
 *
 * * IDENTIFY key
 *   This is the first command that client should send immediately after connecting. 'key' parameter
 *   is a key provided to the user with which he identifies himself to the server. Server will reply
 *   with either PROCEED or FAILED command.
 *
 * * PROCEED
 *   Sent by server upon accepting connection.
 *
 * * FAILED
 *   Sent by server if IDENTIFY command failed (either because of incorrect key or because service is unavailable).
 *
 * * TESTLOGIN username password
 *   Sent by client when trying to figure out if login info is correct. Server will respond with either LOGINOK or LOGINBAD command.
 *
 * * LOGINOK
 *   Sent by server as a response to a successful TESTLOGIN command.
 *
 * * LOGINBAD
 *   Sent by server as a response to a failed TESTLOGIN command.
 *
 * * OK
 *   Sent by server as a response to commands that don't return anything else.
 *   Serves also as positive confirmation (also see "NOTOK" command).
 *
 * * NOTOK
 *   Sent by server as a response to commands that require some positive/negative response (in this case, the response is negative).
 *   Also see "OK" command.
 *
 * * ISONLINE username
 *   Queries the server trying to find out if user <username> is currently online.
 *   Returns either OK or NOTOK (OK if user is online, NOTOK otherwise).
 *
 * * GETACCESS username
 *   Sent by client trying to figure out access status of some user.
 *   Returns 1 for "normal", 2 for "moderator", 3 for "admin".
 *   If user is not found, returns 0 as a result.
 *   If the operation fails for some reason, socket will simply get disconnected.
 *
 * * GENERATEUSERID username
 *   Will send acquireuserid command to <username>. This command doesn't return anything, you won't
 *   be notified if the command succeeded or not (but new notification will be added to TASServer if
 *   specified user responded with a USERID command properly).
 *
 * * QUERYSERVER {server command}
 *   This will forward the given command directly to server and then forward server's response back to the client.
 *   This command means potential security risk. Will be replaced in the future by a set of user specific commands.
 *   Currently commands that may be passed to this function are limited - only some command are allowed. This is so
 *   in order to avoid some security risks with the command.
 *   If the operation fails for some reason, socket will simply get disconnected.
 */
public class RemoteAccessServer extends Thread {

	/** in milliseconds */
	final static int TIMEOUT = 30000;
	final static boolean DEBUG = true;

	/** Keys for remote server access */
	public static List<String> remoteAccounts = new Vector<String>();

	/** used with QUERYSERVER command */
	public static final String allowedQueryCommands[] = {
		"GETREGISTRATIONDATE",
		"GETINGAMETIME",
		"GETLASTIP",
		"GETLASTLOGINTIME",
		"RELOADUPDATEPROPERTIES",
		"GETLOBBYVERSION",
		"UPDATEMOTD",
		"RETRIEVELATESTBANLIST",
		"GETUSERID"
		};

	/** A list of all currently running client threads */
	public List<RemoteClientThread> threads = new Vector<RemoteClientThread>();
	private int port;

	public RemoteAccessServer(int port) {
		this.port = port;
	}

	@Override
	public void run() {
		try {
			ServerSocket ss = new ServerSocket(port);

			while (true) {
				Socket cs = ss.accept();
				RemoteClientThread thread = new RemoteClientThread(this, cs);
				threads.add(thread);
				thread.start();
			}
		} catch (IOException e) {
			Log.error("Error occured in RemoteAccessServer: " + e.getMessage());
		}
	}

}