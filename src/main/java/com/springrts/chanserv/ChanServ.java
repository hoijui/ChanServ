/*
 * Created on 4.3.2006
 */

package com.springrts.chanserv;


import com.springrts.chanserv.antispam.SpamSettings;
import com.springrts.chanserv.antispam.DefaultAntiSpamSystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For the list of commands, see commands.html!
 *
 * *** LINKS ****
 * * http://java.sun.com/docs/books/tutorial/extra/regex/test_harness.html
 *   (how to use regex in java to match a pattern)
 *
 * * http://www.regular-expressions.info/floatingpoint.html
 *   (how to properly match a floating value in regex)
 *
 * *** NOTES ***
 * * ArrayList & LinkedList are not thread-safe -> use Collections.synchronizedList()!
 *
 * *** TODO QUICK NOTES ***
 * * diff between synchronized(object) {...} and using a Semaphore... ?
 *
 * @author Betalord
 */
public class ChanServ {

	private static final Logger logger = LoggerFactory.getLogger(ChanServ.class);

	private static final String VERSION = "0.1+";
	static final String CONFIG_FILENAME = "conf/settings.xml";

	/** are we connected to the lobby server? */
	private boolean connected = false;
	private Socket socket = null;
	private PrintWriter sockout = null;
	private BufferedReader sockin = null;
	private Timer keepAliveTimer;
	private boolean timersStarted = false;

	/**
	 * We use it when there is a danger of config object being used
	 * by main and TaskTimer threads simultaneously
	 */
	private Semaphore configLock = new Semaphore(1, true);

	/** Needs to be thread-save */
	final List<Client> clients;

	/** list of mute entries for a specified channel (see lastMuteListChannel) */
	private final List<String> lastMuteList;
	/**
	 * name of channel for which we are currently receiving
	 * (or we already did receive) mute list from the server
	 */
	private String lastMuteListChannel;
	/** list of current requests for mute lists. */
	private final List<MuteListRequest> forwardMuteList;

	private Context context;

	ChanServ() {

		clients = Collections.synchronizedList(new LinkedList<Client>());
//		channels = Collections.synchronizedList(new LinkedList<Channel>());
		lastMuteList = Collections.synchronizedList(new LinkedList<String>());
		forwardMuteList = Collections.synchronizedList(new LinkedList<MuteListRequest>());
	}

	public void init() {

		context = new Context();
		context.setChanServ(this);
		context.setConfiguration(new Configuration());
		context.setConfigStorage(new JAXBConfigStorage(context));
		context.setAntiSpamSystem(new DefaultAntiSpamSystem(context));
	}

	public void closeAndExit() {
		closeAndExit(0);
	}

	public void closeAndExit(int returncode) {

		context.getAntiSpamSystem().uninitialize();
		if (timersStarted) {
			try {
				stopTimers();
			} catch (Exception e) {
				// ignore
			}
		}
		logger.info("Program stopped.");
		System.exit(returncode);
	}

	public void forceDisconnect() {
		try {
			socket.close();
		} catch (IOException e) {
			//
		}
	}

	public boolean isConnected() {
		return connected;
	}

	/** multiple threads may call this method */
	public synchronized void sendLine(String s) {

		logger.debug("Client: \"{}\"", s);
		sockout.println(s);
	}

	private boolean tryToConnect() {

		Configuration config = context.getConfiguration();

		try {
			logger.info("Connecting to " + config.getServerAddress() + ":" + config.getServerPort() + " ...");
			socket = new Socket(config.getServerAddress(), config.getServerPort());
			sockout = new PrintWriter(socket.getOutputStream(), true);
			sockin = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		} catch (UnknownHostException ex) {
			logger.error("Unknown host error: " + config.getServerAddress(), ex);
			return false;
		} catch (IOException ex) {
			logger.error("Could not get I/O for the connection to: " + config.getServerAddress(), ex);
			return false;
		}

		logger.info("Now connected to " + config.getServerAddress());
		return true;
	}

	public void messageLoop() {

		String line = null;
		while (true) {
			try {
				line = sockin.readLine();
			} catch (IOException e) {
				logger.error("Connection with server closed with exception.");
				break;
			}
			if (line == null) {
				break;
			}
			logger.debug("Server: \"{}\"", line);

			// parse command and respond to it:
			try {
				configLock.acquire();
				execRemoteCommand(line);
			} catch (InterruptedException e) {
				//return;
			} finally {
				configLock.release();
			}

		}

		try {
			sockout.close();
			sockin.close();
			socket.close();
		} catch (IOException e) {
			// do nothing
		}
		logger.info("Connection with server closed.");
	}

	// processes messages that were only sent to server admins. "message" parameter must be a
	// message string withouth the "[broadcast to all admins]: " part.
	public void processAdminBroadcast(String message) {
		logger.debug("admin broadcast: '" + message + "'");

		// let's check if some channel founder/operator has just renamed his account:
		if (message.matches("User <[^>]{1,}> has just renamed his account to <[^>]{1,}>")) {
			String oldNick = message.substring(message.indexOf('<')+1, message.indexOf('>'));
			String newNick = message.substring(message.indexOf('<', message.indexOf('>'))+1, message.indexOf('>', message.indexOf('>')+1));

			// lets rename all founder/operator entries for this user:
			for (int i = 0; i < context.getConfiguration().getChannels().size(); i++) {
				Channel chan = context.getConfiguration().getChannels().get(i);
				if (chan.getFounder().equals(oldNick)) {
					chan.renameFounder(newNick);
					logger.info("Founder <" + oldNick + "> of #" + chan.getName() + " renamed to <" + newNick + ">");
				}
				if (chan.isOperator(oldNick)) {
					chan.renameOperator(oldNick, newNick);
					logger.info("Operator <" + oldNick + "> of #" + chan.getName() + " renamed to <" + newNick + ">");
				}
			}
		}
	}

	public boolean execRemoteCommand(String command) {

		String cleanCommand = command.trim();
		if (cleanCommand.equals("")) {
			return false;
		}

		// try to extract message ID if present:
		if (cleanCommand.charAt(0) == '#') {
			try {
				if (!cleanCommand.matches("^#\\d+\\s[\\s\\S]*")) {
					// malformed command
					return false;
				}
				int threadId = Integer.parseInt(cleanCommand.substring(1).split("\\s")[0]);
				// remove ID field from the rest of command:
				cleanCommand = cleanCommand.replaceFirst("#\\d+\\s", "");
				// forward the command to the waiting thread:
				return context.getRemoteAccessServer().forwardCommand(threadId, cleanCommand);
			} catch (NumberFormatException ex) {
				logger.trace("Malformed command: " + command, ex);
				return false;
			} catch (PatternSyntaxException ex) {
				logger.trace("Malformed command: " + command, ex);
				return false;
			}
		}

		String[] commands = cleanCommand.split(" ");
		commands[0] = commands[0].toUpperCase();

		if (commands[0].equals("TASSERVER")) {
			sendLine("LOGIN " + context.getConfiguration().getUsername() + " " + context.getConfiguration().getPassword() + " 0 * ChanServ " + VERSION);
		} else if (commands[0].equals("ACCEPTED")) {
			logger.info("Login accepted.");
			// join registered and static channels:
			for (Channel channel : context.getConfiguration().getChannels()) {
				sendLine("JOIN " + channel.getName());
			}
		} else if (commands[0].equals("DENIED")) {
			logger.info("Login denied. Reason: " + Misc.makeSentence(commands, 1));
			closeAndExit();
		} else if (commands[0].equals("AGREEMENT")) {
			// not done yet. Should respond with CONFIRMAGREEMENT and then resend LOGIN command.
			logger.info("Server is requesting agreement confirmation. Cancelling ...");
			closeAndExit();
		} else if (commands[0].equals("ADDUSER")) {
			clients.add(new Client(commands[1]));
		} else if (commands[0].equals("REMOVEUSER")) {
			for (Client client : clients) {
				if (client.getName().equals(commands[1])) {
					clients.remove(client);
					break;
				}
			}
		} else if (commands[0].equals("CLIENTSTATUS")) {
			for (Client client : clients) {
				if (client.getName().equals(commands[1])) {
					client.setStatus(Integer.parseInt(commands[2]));
					context.getAntiSpamSystem().processClientStatusChange(client);
					break;
				}
			}
		} else if (commands[0].equals("JOIN")) {
			logger.info("Joined #" + commands[1]);
			Channel chan = getChannel(commands[1]);
			if (chan == null) {
				// this could happen just after we unregistered the channel,
				// since there is always some lag between us and the server
				return false;
			}
			chan.setJoined(true);
			chan.clearClients();
			// set topic, lock channel, ... :
			if (!chan.isStatic()) {
				if (!chan.getKey().equals("")) {
					sendLine("SETCHANNELKEY " + chan.getName() + " " + chan.getKey());
				}
				if (chan.getTopic().equals("")) {
					sendLine("CHANNELTOPIC " + chan.getName() + " *");
				} else {
					sendLine("CHANNELTOPIC " + chan.getName() + " " + chan.getTopic());
				}
			}
		} else if (commands[0].equals("CLIENTS")) {
			Channel chan = getChannel(commands[1]);
			if (chan == null) {
				// this could happen just after we unregistered the channel,
				// since there is always some lag between us and the server
				return false;
			}
			for (int i = 2; i < commands.length; i++) {
				chan.addClient(commands[i]);
			}
		} else if (commands[0].equals("JOINED")) {
			Channel chan = getChannel(commands[1]);
			if (chan == null) {
				// this could happen just after we unregistered the channel,
				// since there is always some lag between us and the server
				return false;
			}
			chan.addClient(commands[2]);
			Misc.logToFile(chan.getLogFileName(), "* " + commands[2] + " has joined " + "#" + chan.getName());
		} else if (commands[0].equals("LEFT")) {
			Channel chan = getChannel(commands[1]);
			if (chan == null) {
				// this could happen just after we unregistered the channel,
				// since there is always some lag between us and the server
				return false;
			}
			chan.removeClient(commands[2]);
			String out = "* " + commands[2] + " has left " + "#" + chan.getName();
			if (commands.length > 3) {
				out = out + " (" + Misc.makeSentence(commands, 3) + ")";
			}
			Misc.logToFile(chan.getLogFileName(), out);
		} else if (commands[0].equals("JOINFAILED")) {
			context.getConfiguration().getChannels().add(new Channel(context, commands[1]));
			logger.info("Failed to join #" + commands[1] + ". Reason: " + Misc.makeSentence(commands, 2));
		} else if (commands[0].equals("CHANNELTOPIC")) {
			Channel chan = getChannel(commands[1]);
			if (chan == null) {
				// this could happen just after we unregistered the channel,
				// since there is always some lag between us and the server
				return false;
			}
			chan.setTopic(Misc.makeSentence(commands, 4));
			Misc.logToFile(chan.getLogFileName(), "* Channel topic is '" + chan.getTopic() + "' set by " + commands[2]);
		} else if (commands[0].equals("SAID")) {
			Channel chan = getChannel(commands[1]);
			if (chan == null) {
				// this could happen just after we unregistered the channel,
				// since there is always some lag between us and the server
				return false;
			}
			String user = commands[2];
			String msg = Misc.makeSentence(commands, 3);
			if (chan.isAntiSpam()) {
				context.getAntiSpamSystem().processUserMsg(chan.getName(), user, msg);
			}
			Misc.logToFile(chan.getLogFileName(), "<" + user + "> " + msg);
			if ((msg.length() > 0) && (msg.charAt(0) == '!')) {
				processUserCommand(msg.substring(1, msg.length()), getClient(user), chan);
			}
		} else if (commands[0].equals("SAIDEX")) {
			Channel chan = getChannel(commands[1]);
			if (chan == null) {
				// this could happen just after we unregistered the channel,
				// since there is always some lag between us and the server
				return false;
			}
			String user = commands[2];
			String msg = Misc.makeSentence(commands, 3);
			if (chan.isAntiSpam()) {
				context.getAntiSpamSystem().processUserMsg(chan.getName(), user, msg);
			}
			Misc.logToFile(chan.getLogFileName(), "* " + user + " " + msg);
		} else if (commands[0].equals("SAIDPRIVATE")) {

			String user = commands[1];
			String msg = Misc.makeSentence(commands, 2);

			Misc.logToFile(user + ".log", "<" + user + "> " + msg);
			if ((msg.length() > 0) && (msg.charAt(0)) == '!') {
				processUserCommand(msg.substring(1, msg.length()), getClient(user), null);
			}
		} else if (commands[0].equals("SERVERMSG")) {
			logger.info("Message from server: " + Misc.makeSentence(commands, 1));
			if (Misc.makeSentence(commands, 1).startsWith("[broadcast to all admins]")) {
				processAdminBroadcast(Misc.makeSentence(commands, 1).substring("[broadcast to all admins]: ".length(), Misc.makeSentence(commands, 1).length()));
			}
		} else if (commands[0].equals("SERVERMSGBOX")) {
			logger.info("MsgBox from server: " + Misc.makeSentence(commands, 1));
		} else if (commands[0].equals("CHANNELMESSAGE")) {
			Channel chan = getChannel(commands[1]);
			if (chan != null) {
				String out = "* Channel message: " + Misc.makeSentence(commands, 2);
				Misc.logToFile(chan.getLogFileName(), out);
			}
		} else if (commands[0].equals("BROADCAST")) {
			logger.info("*** Broadcast from server: " + Misc.makeSentence(commands, 1));
		} else if (commands[0].equals("MUTELISTBEGIN")) {
			lastMuteList.clear();
			lastMuteListChannel = commands[1];
		} else if (commands[0].equals("MUTELIST")) {
			lastMuteList.add(Misc.makeSentence(commands, 1));
		} else if (commands[0].equals("MUTELISTEND")) {
			int i = 0;
			while (i < forwardMuteList.size()) {
				MuteListRequest request = forwardMuteList.get(i);
				if (!request.getChannelName().equals(lastMuteListChannel)) {
					i++;
					continue;
				}
				Client target = getClient(request.getSendTo());
				if (target == null) { // user who made the request has already gone offline!
					forwardMuteList.remove(i);
					continue;
				}
				if (System.currentTimeMillis() - request.getRequestTime() > 10000) { // this request has already expired
					forwardMuteList.remove(i);
					continue;
				}
				// forward the mute list to the one who requested it:
				if (lastMuteList.isEmpty()) {
					sendMessage(target, getChannel(request.getReplyToChan()), "Mute list for #" + request.getChannelName() + " is empty!");
				}
				else {
					sendMessage(target, getChannel(request.getReplyToChan()), "Mute list for #" + request.getChannelName()+ " (" + lastMuteList.size() + " entries):");
					for (String lastMute : lastMuteList) {
						sendMessage(target, getChannel(request.getReplyToChan()), lastMute);
					}
				}
				forwardMuteList.remove(i);
			}
		}


		return true;
	}

	/**
	 * If the command was issued from a private chat,
	 * then the "channel" parameter should be <code>null</code>.
	 */
	public void processUserCommand(String command, Client client, Channel channel) {

		String commandTrimmed = command.trim();
		if (commandTrimmed.isEmpty()) {
			return;
		}
		String[] splitParams = commandTrimmed.split("[\\s]+");
		List<String> params = new LinkedList<String>();
		params.addAll(java.util.Arrays.asList(splitParams));
		// convert the base command
		String commandName = params.get(0).toUpperCase();
		params.remove(0);

		if (commandName.equals("HELP")) {
			// force the message to be sent to private chat rather than
			// to the channel (to avoid unneccessary bloating the channel):
			sendMessage(client, null, "Hello, " + client.getName() + "!");
			sendMessage(client, null, "I am an automated channel service bot,");
			sendMessage(client, null, "for the full list of commands, see http://spring.clan-sy.com/dl/ChanServCommands.html");
			sendMessage(client, null, "If you want to go ahead and register a new channel, please contact one of the server moderators!");
		} else if (commandName.equals("INFO")) {
			// if the command was issued from a channel:
			if (channel != null) {
				// insert <channame> parameter so we do not have to handle
				// two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() != 1) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if (chan == null) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (chan.isStatic()) {
				sendMessage(client, channel, "Channel #" + chanName + " is registered as a static channel, no further info available!");
				return;
			}

			StringBuilder respond = new StringBuilder("Channel #");
			respond.append(chan.getName()).append(" info: Anti-spam protection is ");
			respond.append(chan.isAntiSpam() ? "on" : "off").append(". Founder is <").append(chan.getFounder()).append(">, ");
			List<String> ops = chan.getOperatorList();
			if (ops.isEmpty()) {
				respond.append("no operators are registered.");
			} else if (ops.size() == 1) {
				respond.append("1 registered operator is <").append(ops.get(0)).append(">.");
			} else {
				respond.append(ops.size()).append(" registered operators are ");
				for (int i = 0; i < ops.size()-1; i++) {
					respond.append("<").append(ops.get(i)).append(">, ");
				}
				respond.append("<").append(ops.get(ops.size() - 1)).append(">.");
			}

			sendMessage(client, channel, respond.toString());
		} else if (commandName.equals("REGISTER")) {
			if (!client.isModerator()) {
				sendMessage(client, channel, "Sorry, you'll have to contact one of the server moderators to register a channel for you!");
				return;
			}

			if (params.size() != 2) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1, params.get(0).length());
			String valid = Channel.isChanNameValid(chanName);
			if (valid != null) {
				sendMessage(client, channel, "Error: Bad channel name (" + valid + ")");
				return;
			}

			for (Channel chan : context.getConfiguration().getChannels()) {
				if (chan.getName().equals(chanName)) {
					if (chan.isStatic()) {
						sendMessage(client, channel, "Error: channel #" + chanName + " is a static channel (cannot register it)!");
					} else {
						sendMessage(client, channel, "Error: channel #" + chanName + " is already registered!");
					}
					return;
				}
			}

			// ok register the channel now:
			Channel chan = new Channel(context, chanName);
			context.getConfiguration().getChannels().add(chan);
			chan.setFounder(params.get(1));
			chan.setStatic(false);
			chan.setAntiSpam(false);
			chan.setAntiSpamSettings(SpamSettings.DEFAULT_SETTINGS);
			sendLine("JOIN " + chan.getName());
			sendMessage(client, channel, "Channel #" + chanName + " successfully registered to " + chan.getFounder());
		} else if (commandName.equals("CHANGEFOUNDER")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() != 2) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			// just to protect from flooding the bot with long usernames:
			if (params.get(1).length() > 30) {
				sendMessage(client, channel, "Error: Too long username!");
				return;
			}

			// set founder:
			chan.setFounder(params.get(1));

			sendMessage(client, channel, "You've successfully set founder of #" + chanName + " to <" + chan.getFounder() + ">");
			sendLine("CHANNELMESSAGE " + chan.getName() + " <" + chan.getFounder() + "> has just been set as this channel's founder");
		} else if (commandName.equals("UNREGISTER")) {
			if (params.size() != 1) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			// ok unregister the channel now:
			context.getConfiguration().getChannels().remove(chan);
			sendLine("CHANNELMESSAGE " + chan.getName() + " " + "This channel has just been unregistered from <" + context.getConfiguration().getUsername() + "> by <" + client.getName() + ">");
			sendMessage(client, channel, "Channel #" + chanName + " successfully unregistered!");
			sendLine("LEAVE " + chan.getName());
		} else if (commandName.equals("ADDSTATIC")) {
			if (!client.isModerator()) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			if (params.size() != 1) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);

			for (Channel chan : context.getConfiguration().getChannels()) {
				if (chan.getName().equals(chanName)) {
					if (chan.isStatic()) {
						sendMessage(client, channel, "Error: channel #" + chanName + " is already static!");
					} else {
						sendMessage(client, channel, "Error: channel #" + chanName + " is already registered! (unregister it first and then add it to static list)");
					}
					return;
				}
			}

			// ok add the channel to static list:
			Channel chan = new Channel(context, chanName);
			context.getConfiguration().getChannels().add(chan);
			chan.setStatic(true);
			chan.setAntiSpam(false);
			chan.setAntiSpamSettings(SpamSettings.DEFAULT_SETTINGS);
			sendLine("JOIN " + chan.getName());
			sendMessage(client, channel, "Channel #" + chanName + " successfully added to static list.");
		} else if (commandName.equals("REMOVESTATIC")) {
			if (params.size() != 1) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (!chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not in the static channel list!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			// ok remove the channel from static channel list now:
			context.getConfiguration().getChannels().remove(chan);
			sendMessage(client, channel, "Channel #" + chanName + " successfully removed from static channel list!");
			sendLine("LEAVE " + chan.getName());
		} else if (commandName.equals("OP")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() != 2) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			if (chan.isOperator(params.get(1))) {
				sendMessage(client, channel, "Error: User is already in this channel's operator list!");
				return;
			}

			// just to protect from flooding the bot with long usernames:
			if (params.get(1).length() > 30) {
				sendMessage(client, channel, "Error: Too long username!");
				return;
			}

			if (chan.getOperatorList().size() > 100) {
				sendMessage(client, channel, "Error: Too many operators (100) registered. This is part of a bot-side protection against flooding, if you think you really need more operators assigned, please contact bot maintainer.");
				return;
			}

			// ok add user to channel's operator list:
			chan.addOperator(params.get(1));
			sendLine("CHANNELMESSAGE " + chan.getName() + " <" + params.get(1) + "> has just been added to this channel's operator list by <" + client.getName() + ">");
		} else if (commandName.equals("DEOP")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() != 2) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			if (!chan.isOperator(params.get(1))) {
				sendMessage(client, channel, "Error: User <" + params.get(1) + "> is not in this channel's operator list!");
				return;
			}

			// ok remove user from channel's operator list:
			chan.removeOperator(params.get(1));
			sendLine("CHANNELMESSAGE " + chan.getName() + " <" + params.get(1) + "> has just been removed from this channel's operator list by <" + client.getName() + ">");
		} else if (commandName.equals("SPAMPROTECTION")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if ((params.size() != 2) && (params.size() != 1)) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if (chan == null) {
				sendMessage(client, channel, "Channel #" + chanName + " does not exist!");
				return;
			}

			if (params.size() == 1) {
				sendMessage(client, channel, "Anti-spam protection for channel #" + chan.getName() + " is " + (chan.isAntiSpam() ? "on (settings: " + chan.getAntiSpamSettings() + ")" : "off"));
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			if (params.get(1).toUpperCase().equals("ON")) {
				sendMessage(client, channel, "Anti-spam protection has been enabled for #" + chan.getName());
				sendLine("CHANNELMESSAGE " + chan.getName() + " Anti-spam protection for channel #" + chan.getName() + " has been enabled");
				chan.setAntiSpam(true);
				return;
			} else if (params.get(1).toUpperCase().equals("OFF")) {
				sendMessage(client, channel, "Anti-spam protection has been disabled for #" + chan.getName());
				sendLine("CHANNELMESSAGE " + chan.getName() + " Anti-spam protection for channel #" + chan.getName() + " has been disabled");
				chan.setAntiSpam(false);
				return;
			} else {
				sendMessage(client, channel, "Error: Invalid parameter (\"" + params.get(1) + "\"). Valid is \"on|off\"");
				return;
			}
		} else if (commandName.equals("SPAMSETTINGS")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() != 6) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if (chan == null) {
				sendMessage(client, channel, "Channel #" + chanName + " does not exist!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			String spamSettingsString = Misc.makeSentence(params, 1);

			SpamSettings spamSettings = null;
			try {
				spamSettings = SpamSettings.fromProtocolString(spamSettingsString);
			} catch (Exception ex) {
				sendMessage(client, channel, "Invalid 'settings' parameter!");
				return;
			}

			chan.setAntiSpamSettings(spamSettings);
			context.getAntiSpamSystem().setSpamSettingsForChannel(chan.getName(), chan.getAntiSpamSettings());
			sendMessage(client, channel, "Anti-spam settings successfully updated (" + chan.getAntiSpamSettings() + ")");
		} else if (commandName.equals("TOPIC")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() < 1) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String topic;
			if (params.size() == 1) {
				topic = "*";
			} else {
				topic = Misc.makeSentence(params, 1);
			}
			if (topic.trim().equals("")) {
				topic = "*";
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()) || chan.isOperator(client.getName()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			// ok set the topic:
			sendLine("CHANNELTOPIC " + chan.getName() + " " + topic);
		} else if (commandName.equals("CHANMSG")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() < 2) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String msg = Misc.makeSentence(params, 1);
			if (msg.trim().equals("")) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()) || chan.isOperator(client.getName()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			// ok send the channel message:
			sendLine("CHANNELMESSAGE " + chan.getName() + " issued by <" + client.getName() + ">: " + msg);
		} else if (commandName.equals("LOCK")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() != 2) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()) || chan.isOperator(client.getName()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			if (!Misc.isValidName(params.get(1))) {
				sendMessage(client, channel, "Error: key contains some invalid characters!");
				return;
			}

			// ok lock the channel:
			sendLine("SETCHANNELKEY " + chan.getName() + " " + params.get(1));
			if (params.get(1).equals("*")) {
				chan.setKey("");
			} else {
				chan.setKey(params.get(1));
			}
		} else if (commandName.equals("UNLOCK")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() != 1) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()) || chan.isOperator(client.getName()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			// ok unlock the channel:
			sendLine("SETCHANNELKEY " + chan.getName() + " *");
			chan.setKey("");
		} else if (commandName.equals("KICK")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() < 2) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()) || chan.isOperator(client.getName()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			String target = params.get(1);
			if (!chan.isClient(target)) {
				sendMessage(client, channel, "Error: <" + target + "> not found in #" + chanName + "!");
				return;
			}

			if (target.equals(context.getConfiguration().getUsername())) {
				// not funny!
				sendMessage(client, channel, "You are not allowed to issue this command!");
				return;
			}

			String reason = "";
			if (params.size() > 2) {
				reason = " " + Misc.makeSentence(params, 2);
			}

			// ok kick the user:
			sendLine("FORCELEAVECHANNEL " + chan.getName() + " " + target + reason);
		} else if (commandName.equals("MUTE")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() < 2) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()) || chan.isOperator(client.getName()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			String target = params.get(1);
			if (getClient(target) == null) {
				sendMessage(client, channel, "Error: Invalid username - <" + target + "> does not exist or is not online. Command dropped.");
				return;
			}

			if (target.equals(context.getConfiguration().getUsername())) {
				// not funny!
				sendMessage(client, channel, "You are not allowed to issue this command!");
				return;
			}

			int duration = 0;
			if (params.size() == 3) {
				try {
					duration = Integer.parseInt(params.get(2));
				} catch (Exception e) {
					sendMessage(client, channel, "Error: <duration> argument should be an integer!");
					return;
				}
			}

			// ok mute the user:
			sendLine("MUTE " + chan.getName() + " " + target + " " + duration);
		} else if (commandName.equals("UNMUTE")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() != 2) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()) || chan.isOperator(client.getName()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			String target = params.get(1);

			// ok try to unmute the user:
			sendLine("UNMUTE " + chan.getName() + " " + target);
		} else if (commandName.equals("MUTELIST")) {
			// if the command was issued from a channel:
			if (channel != null) { // insert <channame> parameter so we don't have to handle two different situations for each command
				params.add(0, "#" + channel.getName());
			}

			if (params.size() != 1) {
				sendMessage(client, channel, "Error: Invalid params!");
				return;
			}

			if (params.get(0).charAt(0) != '#') {
				sendMessage(client, channel, "Error: Bad channel name (forgot #?)");
				return;
			}

			String chanName = params.get(0).substring(1);
			Channel chan = getChannel(chanName);
			if ((chan == null) || (chan.isStatic())) {
				sendMessage(client, channel, "Channel #" + chanName + " is not registered!");
				return;
			}

			if (!(client.isModerator() || client.getName().equals(chan.getFounder()) || chan.isOperator(client.getName()))) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			forwardMuteList.add(new MuteListRequest(chanName, client.getName(), System.currentTimeMillis(), (channel != null) ? channel.getName() : ""));
			sendLine("MUTELIST " + chan.getName());
		} else if (commandName.equals("SHUTDOWN")) {
			if (!client.isModerator()) {
				sendMessage(client, channel, "Insufficient access to execute " + commandName + " command!");
				return;
			}

			String reason = "restarting ..."; // default reason text

			if (params.size() > 0) {
				reason = Misc.makeSentence(params, 0);
			}

			for (Channel chan : context.getConfiguration().getChannels()) {
				// skip static channels
				if (!chan.isStatic()) {
					sendLine("SAYEX " + chan.getName() + " is quitting. Reason: " + reason);
				}
			}

			// stop the program:
			stopTimers();
			context.getConfigStorage().saveConfig(CONFIG_FILENAME);
			closeAndExit();
		}
	}

	public void sendPrivateMsg(Client client, String msg) {

		sendLine("SAYPRIVATE " + client.getName() + " " + msg);
		Misc.logToFile(client.getName() + ".log", "<" + context.getConfiguration().getUsername() + "> " + msg);
	}

	/**
	 * This method will send a message either to a client or a channel.
	 * This method decides what to do, by examining the 'client' and 'chan'
	 * parameters (null / not null):
	 *
	 *  1) client  && chan  - will send a msg to a channel with client's username in front of it
	 *  2) client  && !chan - will send a private msg to the client
	 *  3) !client && chan  - will send a msg to a channel (general message without a prefix)
	 *  4) !client && !chan - invalid parameters
	 */
	public void sendMessage(Client client, Channel chan, String msg) {

		if ((client == null) && (chan == null)) {
			// this should not happen!
			logger.warn("sendMessage() called, with neither a client nor a channel specified");
			return;
		} else if ((client == null) && (chan != null)) {
			chan.sendMessage(msg);
		} else if ((client != null) && (chan != null)) {
			chan.sendMessage(client.getName() + ": " + msg);
		} else if ((client != null) && (chan == null)) {
			sendPrivateMsg(client, msg);
		}
	}

	public Client getClient(String username) {

		for (Client client : clients) {
			if (client.getName().equals(username)) {
				return client;
			}
		}

		return null;
	}

	/** Returns <code>null</code> if the channel is not found */
	public Channel getChannel(String name) {

		for (Channel channel : context.getConfiguration().getChannels()) {
			if (channel.getName().equals(name)) {
				return channel;
			}
		}

		return null;
	}

	/**
	 * @author Betalord
	 */
	private class KeepAliveTask extends TimerTask {
		public void run() {
			try {
				configLock.acquire();

				sendLine("PING");
				// also save config on regular intervals:
				context.getConfigStorage().saveConfig(ChanServ.CONFIG_FILENAME);
			} catch (InterruptedException e) {
				forceDisconnect();
				return;
			} finally {
				configLock.release();
			}
		}
	}

	public void startTimers() {

		keepAliveTimer = new Timer();
		keepAliveTimer.schedule(new KeepAliveTask(),
				1000,     // initial delay
				15*1000); // subsequent rate

		timersStarted = true;
	}

	public void stopTimers() {

		try {
			keepAliveTimer.cancel();
		} catch (Exception ex) {
			logger.error("Failed stopping timers", ex);
		}
	}

	public void start() {

		logger.info("ChanServ started on " + Misc.easyDateFormat("dd/MM/yy"));
		logger.info("");

		// it is vital that we initialize AntiSpamSystem
		// before loading the configuration file,
		// since we will configure AntiSpamSystem too
		context.getAntiSpamSystem().initialize();

		context.getConfigStorage().loadConfig(CONFIG_FILENAME);
		context.getConfigStorage().saveConfig(CONFIG_FILENAME); //*** TODO FIXME debug
		
		Configuration config = context.getConfiguration();

		// run remote access server:
		RemoteAccessServer remoteAccessServer = new RemoteAccessServer(context, config.getRemoteAccessPort());
		context.setRemoteAccessServer(remoteAccessServer);
		remoteAccessServer.start();

		if (!tryToConnect()) {
			closeAndExit(1);
		} else {
			startTimers();
			connected = true;
			messageLoop();
			connected = false;
			stopTimers();
		}

		// we are out of the main loop (due to an error, for example),
		// lets reconnect:
		while (true) {
			try {
				// wait for 10 secs before trying to reconnect
				Thread.sleep(10000);
			} catch (InterruptedException ex) {
				// ignore Exception
			}

			logger.info("Trying to reconnect to the server ...");
			if (!tryToConnect()) {
				continue;
			}
			startTimers();
			connected = true;
			messageLoop();
			connected = false;
			stopTimers();
		}

		// AntiSpamSystem.uninitialize(); -> this code is unreachable. We call it in closeAndExit() method!

	}

	public static void main(String[] args) {

		ChanServ chanServ = new ChanServ();
		chanServ.init();
		chanServ.start();
	}
}
