package com.itsjamilahmed.solace.queuebalancer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

public class QueueBalancerApp {

	private static Logger logger = LogManager.getLogger(QueueBalancerApp.class);	// A log4j logger to handle all output
	private static Namespace argsParserResponse = null;								// argparse4j response after handling program arguments
	
	public static void main(String[] args) {

		// Arguments parser to collect and validate command line input
		parseArguments(args);
		
		QueueBalancer myQueueBalancer;
		
		myQueueBalancer = new QueueBalancer(
				argsParserResponse.getString("semp_base"),
				argsParserResponse.getString("semp_user"),
				argsParserResponse.getString("semp_password"),
				argsParserResponse.getString("message_vpn"),
				argsParserResponse.getString("queues_list").split(","));

		// How often to check the status, and when to perform the actual rebalancing, will depend on the use-case and expected message rates.
		// As an example, running every 10 minutes and needing 2 consecutive 'true' status may be when a rebalance is done.
		// This would allow for transient periods of imbalance to not trigger the move of messages, only persistently imbalanced periods.
		
		myQueueBalancer.determineBalancedStatus();
		
		logger.info("Do the queues need rebalancing? " + myQueueBalancer.isQueueRebalanceRequired());
		
		if (myQueueBalancer.isQueueRebalanceRequired()) {
			myQueueBalancer.performQueueRebalancing();
		}	
	}
	
	private static void parseArguments (String[] args) {
		
		ArgumentParser parser = ArgumentParsers.newFor("QueueBalancerApp").build()
				.description("An application to balance message backlog across a set of supplied queues.");
		
		parser.addArgument("--semp-base")
			.type(String.class)
			.help("Path to any SEMPv2 base. \ne.g. https://mysolace:943/SEMP/v2/config ")
			.required(true);
		parser.addArgument("--message-vpn")
			.type(String.class)
			.help("Name of the message VPN")
			.required(true);
		parser.addArgument("--semp-user")
			.type(String.class)
			.help("Admin user with read/write permission for the VPN")
			.required(true);
		parser.addArgument("--semp-password")
			.type(String.class)
			.help("Password of the admin user")
			.required(true);
		parser.addArgument("--queues-list")
			.type(String.class)
			.help("Comma seperated list of queue names. \ne.g. queue1,queue2,queue3")
			.required(true);	
		
		try {
			argsParserResponse = parser.parseArgs(args);
			
		} catch (ArgumentParserException e) {
			System.err.println(e.getMessage());
			parser.printHelp();
			System.exit(0);
		}
	}

}
