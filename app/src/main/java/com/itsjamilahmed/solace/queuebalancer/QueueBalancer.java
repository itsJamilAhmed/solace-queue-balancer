package com.itsjamilahmed.solace.queuebalancer;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.itsjamilahmed.solace.semplib.action.model.MsgVpnQueueCopyMsgFromQueue;
import com.itsjamilahmed.solace.semplib.monitor.api.MsgVpnApi;
import com.itsjamilahmed.solace.semplib.monitor.lib.ApiClient;
import com.itsjamilahmed.solace.semplib.monitor.lib.ApiException;
import com.itsjamilahmed.solace.semplib.monitor.model.MsgVpnQueueCollections;
import com.itsjamilahmed.solace.semplib.monitor.model.MsgVpnQueueMsg;
import com.itsjamilahmed.solace.semplib.monitor.model.MsgVpnQueueMsgsResponse;
import com.itsjamilahmed.solace.semplib.monitor.model.MsgVpnQueueResponse;
import com.itsjamilahmed.solace.semplib.monitor.model.SempError;
import com.itsjamilahmed.solace.semplib.monitor.model.SempMetaOnlyResponse;

import com.google.gson.Gson;

public class QueueBalancer {

	private static Logger logger = LogManager.getLogger(QueueBalancer.class);	// A log4j logger to handle all output

	private static MsgVpnApi sempMonitorApiInstance;
	private static ApiClient sempMonitorClient = new ApiClient();

	// To avoid import clash, use fully-qualified names for the 'Action' SEMP API.
	private static com.itsjamilahmed.solace.semplib.action.api.MsgVpnApi sempActionApiInstance;
	private static com.itsjamilahmed.solace.semplib.action.lib.ApiClient sempActionClient = new com.itsjamilahmed.solace.semplib.action.lib.ApiClient();

	private String msgVpn;

	// Internal representation of a monitored queue, it's depth, determination against target depth, etc.
	private class MonitoredQueue {

		private String queueName = "";
		private long queueDepth = 0;
		private long depthChangeTarget = 0;
		private boolean clientsBound = false;
		private boolean rebalanceNeeded = false;

		public MonitoredQueue(String queueName) {
			this.queueName = queueName;
		}

		public String getQueueName() {
			return queueName;
		}
		public void setQueueName(String queueName) {
			this.queueName = queueName;
		}
		public long getQueueDepth() {
			return queueDepth;
		}
		public void setQueueDepth(long queueDepth) {
			this.queueDepth = queueDepth;
		}
		public boolean isAboveTargetDepth() {
			// Negative target means reduction is required from current message count
			return (depthChangeTarget < 0);
		}

		public long getDepthChangeTarget() {
			return depthChangeTarget;
		}

		public void setDepthChangeTarget(long targetDepth) {
			this.depthChangeTarget = targetDepth;
		}

		public void recordTargetReduction() {

			// Both a positive and negative target need to converge to zero as the rebalance progresses.
			if (depthChangeTarget > 0){
				depthChangeTarget--;
			} else if (depthChangeTarget < 0) {
				depthChangeTarget++;
			}
		}

		public boolean isClientsBound() {
			return clientsBound;
		}

		public void setClientsBound(boolean clientsBound) {
			this.clientsBound = clientsBound;
		}

		public boolean isRebalanceNeeded() {
			return rebalanceNeeded;
		}

		public void setRebalanceNeeded(boolean rebalanceNeeded) {
			this.rebalanceNeeded = rebalanceNeeded;
		}
	}

	// Class to hold details of the SEMP message move operations needed during a rebalance.
	private class MessageMoveOperation {

		private String rmid = "";	// Copy action uses this Replication Group Message ID
		private String id = "";		// Delete action needs yet another different 'Message ID'! So collect both...
		private String sourceQueue = "";
		private String targetQueue = "";


		public MessageMoveOperation(String rmid, String id, String sourceQueue, String targetQueue) {
			super();
			this.rmid = rmid;
			this.id = id;
			this.sourceQueue = sourceQueue;
			this.targetQueue = targetQueue;
		}

		public String getRmid() {
			return rmid;
		}
		public void setRmid(String rmid) {
			this.rmid = rmid;
		}
		public String getId() {
			return id;
		}
		public void setId(String id) {
			this.id = id;
		}
		public String getSourceQueue() {
			return sourceQueue;
		}
		public void setSourceQueue(String sourceQueue) {
			this.sourceQueue = sourceQueue;
		}
		public String getTargetQueue() {
			return targetQueue;
		}
		public void setTargetQueue(String targetQueue) {
			this.targetQueue = targetQueue;
		}

	}


	private LinkedList<MonitoredQueue> monitoredQueues;
	private boolean queueRebalanceRequired = false;

	public QueueBalancer(String sempBasePath, String sempUser, String sempPassword, String msgVpn) {

		this.msgVpn = msgVpn;

		String sempV2Path = "/SEMP/v2/";

		String sempBase = sempBasePath.substring(0, sempBasePath.indexOf(sempV2Path));
		logger.info("sempBase: " + sempBase);
		sempMonitorClient.setBasePath(sempBase + sempV2Path + "monitor");
		sempMonitorClient.setUsername(sempUser);
		sempMonitorClient.setPassword(sempPassword);
		sempMonitorApiInstance = new MsgVpnApi(sempMonitorClient);

		sempActionClient.setBasePath(sempBase + sempV2Path + "action");
		sempActionClient.setUsername(sempUser);
		sempActionClient.setPassword(sempPassword);
		sempActionApiInstance = new com.itsjamilahmed.solace.semplib.action.api.MsgVpnApi(sempActionClient);

	}

	public QueueBalancer(String sempBasePath, String sempUser, String sempPassword, 
			String msgVpn, LinkedList<String> queueNames) {

		this(sempBasePath, sempUser, sempPassword, msgVpn);

		importQueueNames(queueNames);
	}
	
	public QueueBalancer(String sempBasePath, String sempUser, String sempPassword, 
			String msgVpn, String[] queueNames) {

		this(sempBasePath, sempUser, sempPassword, msgVpn);

		LinkedList<String> queues = new LinkedList<String>();
		
		for (int i=0 ; i < queueNames.length; i++) {
			queues.add(queueNames[i]);
		}
		
		importQueueNames(queues);
	}

	/**
	 * Get a list of queue names that are being monitored for balancing
	 * @return a string linked list of queue names 
	 */
	public LinkedList<String> getMonitoredQueues() {

		LinkedList<String> monitoredQueues = new LinkedList<String>();

		this.monitoredQueues.forEach(
				(monitoredQueue) -> monitoredQueues.add(monitoredQueue.getQueueName())
				);

		return monitoredQueues;
	}

	/**
	 * This operation will reset the list of monitored queues with the supplied list. 
	 * i.e. No logic about checking each name and inserting only new entries
	 */
	public void setMonitoredQueues(LinkedList<String> queueNames) {
		importQueueNames(queueNames);
	}


	/**
	 * Trigger a check of each queue's depth and update state of whether rebalancing required
	 * This action will use the SEMP APIs to do this for each queue, expected to take some time
	 * @throws Exception if there is an unrecoverable error from SEMP API calls.
	 * 
	 */
	public boolean determineBalancedStatus () throws Exception {

		// (1) Iterate each queue name and get the current depth and bind count of the queue
		//     In a rebalancing operation, no value to moving messages *to* queues that are not being serviced
		//     Likewise, no need to balance at all if *all* queues show to be unbound.
		for (MonitoredQueue queue : this.monitoredQueues) { 

			LinkedList<String> sempSelect = new LinkedList<String>();

			// Reduce the size of SEMP response by only getting back these fields
			sempSelect.add("queueName");
			sempSelect.add("msgs.count");
			sempSelect.add("txFlows");

			MsgVpnQueueResponse resp;
			try {
				resp = sempMonitorApiInstance.getMsgVpnQueue(msgVpn, queue.getQueueName(), sempSelect);
				MsgVpnQueueCollections queueCollectionsResponse = resp.getCollections();

				if (queueCollectionsResponse.getTxFlows().getCount() == null) {
					// Would only be null if older SEMP where this is not implemented yet
					// Assume to be a bound queue until then...
					queue.setClientsBound(true);
				}
				else {
					queue.setClientsBound((queueCollectionsResponse.getTxFlows().getCount() > 0));
				}

				queue.setQueueDepth(queueCollectionsResponse.getMsgs().getCount());

				logger.info("\tStatus for queue: " + queue.getQueueName() + "... Current Depth: " + queue.getQueueDepth() + ". Clients Bound? " + queue.isClientsBound());

			} catch (ApiException e) {
				// Recoverable error?
				if (!handleSempError_queueOperation(queue, e)) {
					throw new Exception("Unrecoverable error.");
				}
			}            
		}

		// (2) Calculate what the average per queue should be, and which queues are needing rebalancing
		//	   NOTE: Any queues that are unbound will be emptied in the rebalance operation too93
		long totalMsgs = 0;

		totalMsgs = monitoredQueues.stream().mapToLong(queue -> queue.getQueueDepth()).sum();
		logger.info("Total number of messages across queues: " + totalMsgs);
		long nReadyQueues = monitoredQueues.stream().filter(queue -> queue.isClientsBound() == true).count();
		logger.info("Number of queues with clients bound: " + nReadyQueues);

		if (totalMsgs > 0 && nReadyQueues > 0) {

			double averageTarget = totalMsgs / nReadyQueues;	// This may round down to create the average count per queue
			long remainder = totalMsgs % nReadyQueues;		// Determine the expected remainder so those can be handled too


			logger.info("Average target per queue if balanced: " + averageTarget);
			logger.info("Modulus remainder to account for: " + remainder);

			double tolerancePercent = 10;	// Do not rebalance if message counts are within 10% of target. That is close enough to leave alone.

			logger.info("Determining message count changes needed for each queue to achieve balanced state...");

			for (MonitoredQueue queue : this.monitoredQueues) { 

				// If a queue has messages but no binds, target to completely drain it. (Average calculation already assumes this queue no longer in play)
				if (!queue.isClientsBound()) {				

					queue.setDepthChangeTarget( -1 * queue.getQueueDepth() );
					logger.info("\tSet depth change target for queue " + queue.queueName + " (to empty unbound queue) to: " + queue.getDepthChangeTarget());
				}
				else {

					queue.setDepthChangeTarget((long) averageTarget - queue.getQueueDepth());

					// Plan to touch only the queues with depth more than 10% off target. So mark how many queues meet the criteria.
					// Also if the target change is the same as calculated remainder, that could be from a previous rebalancing so also should be ignored.
					
					double diffMsgs = averageTarget - queue.getQueueDepth();
					double diffPercent = ((diffMsgs) / averageTarget ) * 100;
					if (Math.abs(diffPercent) > tolerancePercent && diffMsgs > remainder) {
						queue.setRebalanceNeeded(true);

						// Allocate the extra 'remainder' of messages to the first queue that gets processed with a positive (add messages) depth change target
						if (remainder > 0 && queue.getDepthChangeTarget() > 0) {

							logger.info("\tAllocating the remainder of " + remainder + " messages to queue: " + queue.queueName);
							queue.setDepthChangeTarget( queue.getDepthChangeTarget() + remainder );
							remainder = 0;	// All handled now so same as zero remainder calculation.
						}	
					}

					logger.info("\tSet depth change target for queue " + queue.queueName + " to: " + queue.getDepthChangeTarget());
				}
			}

			// Set a final flag if any queues have been determined to need rebalancing

			this.queueRebalanceRequired = (monitoredQueues.stream().filter(queue -> queue.isRebalanceNeeded() == true).count() > 0);
		}
		return queueRebalanceRequired;

	}


	/**
	 * After previously calling the determineBalancedStatus() method, this method will return the status of whether the queues need rebalancing. <br>
	 * It may be that the determination method is 'polled' in one thread at a given interval, 
	 * and another thread calls this function without concern about triggering the SEMP activity
	 * @return true  - Queues have message backlog that are not well balanced across the set. <br>
	 *         false - Queues are balanced and no action is required.
	 */
	public boolean isQueueRebalanceRequired() {
		return queueRebalanceRequired;
	}


	/**
	 * If a queue rebalancing is required, carry out the move of messages across the queues using SEMP. <br>
	 * Can only be done message by message, so the operation is expected to take some time if a large number of moves needed. <br>
	 * 
	 */
	public void performQueueRebalancing() {

		if (!this.queueRebalanceRequired) {
			logger.info("Rebalance operation called when 'rebalancing required' flag is false. Nothing to do.");
			return;
		}

		logger.info(">>> Rebalance operation started...");

		List<MonitoredQueue> reducingQueues = monitoredQueues.stream().filter(queue -> queue.isAboveTargetDepth()).collect(Collectors.toList());

		List<MonitoredQueue> increasingQueues = monitoredQueues.stream().filter(queue -> ! queue.isAboveTargetDepth()).collect(Collectors.toList());

		logger.info("Number of reducing queues: " + reducingQueues.size());
		logger.info("Number of increasing queues: " + increasingQueues.size());

		long messageMovesCompleted = 0;
		long messageMovesPlanned = reducingQueues.stream().mapToLong(queue -> queue.getDepthChangeTarget() * -1).sum();
		String progressPercent;
		
		logger.info("Number of messages to move: " + messageMovesPlanned);

		MsgVpnQueueMsg queueMsgDetails;
		String rmid, msgId;
		
		try {
			for (MonitoredQueue rQueue : reducingQueues) { 
				
				while (rQueue.getDepthChangeTarget() < 0) {

					for (MonitoredQueue iQueue : increasingQueues) {

						while (rQueue.getDepthChangeTarget() < 0 && iQueue.getDepthChangeTarget() > 0) {

							// Get the rmid of a message currently oldest in the queue, with a preference for those without any delivery attempt.
							// Note: Will return null if no details were fetched.
							
							queueMsgDetails = getMessageDetailsFromQueue(rQueue.getQueueName());
							
							if (queueMsgDetails == null) {

								// Could not perform move operation as queue is empty.
								// Abandon moving from this queue and move on to next.
								logger.info("Could not fetch rmid from queue " + rQueue.getQueueName() + " for message move. (Queue now empty?)"); 
								rQueue.setDepthChangeTarget(0);
								logger.info("Abandoning queue " + rQueue.getQueueName() + " for any further move operations.");
							}
							else {
								rmid  = queueMsgDetails.getReplicationGroupMsgId();
								msgId = queueMsgDetails.getMsgId().toString();

								MessageMoveOperation plannedMove = new MessageMoveOperation(rmid, msgId, rQueue.getQueueName(), iQueue.getQueueName());

								if (performMessageMove(plannedMove)) {
									rQueue.recordTargetReduction();
									iQueue.recordTargetReduction();	 
									messageMovesCompleted++;
									logger.info("\tMoved message " + messageMovesCompleted + " with ID: " + rmid + " from " + rQueue.getQueueName() + " to " + iQueue.getQueueName());
								}
								else {
									// Could not perform move operation as message to move has gone from the queue.
									// Abandon moving from this queue and move on to next.
									// If any other type of error, an exception will have been thrown that will break out of all the loops.	
									rQueue.setDepthChangeTarget(0);
									logger.info("Abandoning queue " + rQueue.getQueueName() + " for any further move operations.");
								}
							}
						}
					}
				}
			}

			// All completed successfully!
			this.queueRebalanceRequired = false;
			progressPercent = Long.toString((messageMovesCompleted *100) / messageMovesPlanned);
			logger.info(">>> Rebalance operation completed successfully after " + progressPercent + "% of planned moves." );
		}
		catch (Exception e) {
			progressPercent = Long.toString((messageMovesCompleted *100) / messageMovesPlanned);
			logger.error(e.getMessage());
			logger.error("Aborting this rebalance operation after " + progressPercent + "% progress." );
		}

	}

	private boolean performMessageMove (MessageMoveOperation moveOperation) throws Exception {

		MsgVpnQueueCopyMsgFromQueue body = new MsgVpnQueueCopyMsgFromQueue();
		body.setReplicationGroupMsgId(moveOperation.getRmid());
		body.setSourceQueueName(moveOperation.getSourceQueue());

		try {
			// Copy to target
			sempActionApiInstance.doMsgVpnQueueCopyMsgFromQueue(msgVpn, moveOperation.getTargetQueue(), body);
			logger.debug("\tMessage copy   for rmid: " + moveOperation.getRmid() + " successfully completed.");
			// Delete from source
			sempActionApiInstance.doMsgVpnQueueMsgDelete(msgVpn, moveOperation.getSourceQueue(), moveOperation.getId(), new Object());	// Last arg is just type "Object" and spec shows empty {}.
			logger.debug("\tMessage delete for rmid: " + moveOperation.getRmid() + " and message ID: " + moveOperation.getId() + " successfully completed.");
			return true;

		} catch (com.itsjamilahmed.solace.semplib.action.lib.ApiException e) {
			return handleSempError_moveOperation(e, moveOperation);	// Was the reason for exception the queue being empty? Will return false from the operation if so.
		}

	}

	private MsgVpnQueueMsg getMessageDetailsFromQueue (String queueName) {

		LinkedList<String> sempSelect = new LinkedList<String>();
		LinkedList<String> sempWhere = new LinkedList<String>();


		// Reduce the size of SEMP response by only getting back these fields
		sempSelect.add("replicationGroupMsgId");
		sempSelect.add("msgId");
		sempSelect.add("undelivered");

		// At the first pass, only get messages that undelivered=true, as in, they have not been assigned to any consumer and so no risk of double processing.
		sempWhere.add("undelivered==true");

		// Ideally would just set a count of 1 to get the minimal SEMP response back. However any *where* filter only applies AFTER getting some response. Grr.
		// So if set to 1, and the 5th message is undelivered==true, will not return in the SEMP response.
		// Compromise is to get 20 messages deep into the queue backlog and then grab the first undelivered==true RMID that can be *selected*.
		int sempRecordsCount = 20;

		MsgVpnQueueMsgsResponse resp;
		try {
			resp = sempMonitorApiInstance.getMsgVpnQueueMsgs(msgVpn, queueName, sempRecordsCount, null, sempWhere, sempSelect);
			List<MsgVpnQueueMsg> msgsList = resp.getData();

			if (msgsList.size() == 0) {
				// No undelivered messages? What about the whole queue then? (i.e. Fetch without the 'where' filter.)

				logger.debug("\tFor queue " + queueName + " no message was returned for undelivered==true, will try fetching wider.");
				// TODO: Should this fallback option be taken out or made switchable? 
				//       For messages that have had prior delivery, should they risk duplicate processing by moving them?
				//       An alternative would be to let them move to DMQ and pick up that way as a different form of rebalancing that subset of message backlog.

				resp = sempMonitorApiInstance.getMsgVpnQueueMsgs(msgVpn, queueName, 1, null, null, sempSelect);
				msgsList = resp.getData();
			}

			// 
			if (msgsList.size() == 0) {
				// Still no messages? Maybe the situation has changed drastically, the donating queue might just be suddenly empty now!
				return null;
			}
			else {
				return msgsList.get(0);
			}

		} catch (ApiException e) {
			handleSempError_rmidOperation(e);
		}

		return null;    	
	}

	private boolean handleSempError_queueOperation(MonitoredQueue queue, ApiException ae) {
		Gson gson = new Gson();
		String responseString = ae.getResponseBody();
		SempMetaOnlyResponse respObj = gson.fromJson(responseString, SempMetaOnlyResponse.class);
		SempError errorInfo = respObj.getMeta().getError();

		boolean recoverable = false;
		
		// What went wrong with the SEMP query?
		switch (errorInfo.getStatus()) {
		case "NOT_FOUND":
			logger.warn("Queue: " + queue.getQueueName() + " not configured on the broker! Will be ignored...");
			recoverable = true;
			break;
		case "UNAUTHORIZED":
			logger.fatal("Authorization Failed using provided SEMP Credentials!");
			recoverable = false;
			break;			
		default:
			logger.error("SEMP error during processing queue " + queue.getQueueName() +": " + errorInfo.getDescription() + "(" + errorInfo.getStatus() + ")");
			recoverable = true;
		}       
		return recoverable;
	}

	private void handleSempError_rmidOperation(ApiException ae) {
		Gson gson = new Gson();
		String responseString = ae.getResponseBody();
		SempMetaOnlyResponse respObj = gson.fromJson(responseString, SempMetaOnlyResponse.class);
		SempError errorInfo = respObj.getMeta().getError();

		logger.error("SEMP error during rmid fetch: " + errorInfo.getDescription() + "(" + errorInfo.getStatus() + ")");

	}

	// return false if error due to expected condition of message being consumed during rebalancing
	// throws new exception if not recoverable issue, so caller can handle impact.
	private boolean handleSempError_moveOperation(com.itsjamilahmed.solace.semplib.action.lib.ApiException ae, MessageMoveOperation moveOperation) throws Exception {
		Gson gson = new Gson();
		String responseString = ae.getResponseBody();
		SempMetaOnlyResponse respObj = gson.fromJson(responseString, SempMetaOnlyResponse.class);
		SempError errorInfo = respObj.getMeta().getError();
		
		logger.debug("SEMP error during message move: " + errorInfo.getDescription() + "(" + errorInfo.getStatus() + ")");

		if (errorInfo.getDescription().contains("Source Message Not Found")) {
			// The queue likely got fully consumed during the rebalancing. Move onto the next queue...
			logger.info("Could not copy message with ID: " + moveOperation.getRmid() + " from " + moveOperation.getSourceQueue() + 
					" to " + moveOperation.getTargetQueue() + ". (Already consumed/removed?)");

			return false;
		} else if (errorInfo.getDescription().contains("Could not find match for msg")) {
			// The message got consumed in the middle of the copy+delete operation, likely getting consumed. Move onto the next queue...
			logger.warn("Could not delete message with ID: " + moveOperation.getRmid() + " from " + moveOperation.getSourceQueue() + 
					" after copying it to " + moveOperation.getTargetQueue() + ". (Already consumed/removed?)");

			// TODO: Offer option of rolling back that copy?
			return false;
		}
		else {
			throw new Exception("SEMP error during message move: " + errorInfo.getDescription() + "(" + errorInfo.getStatus() + ")");	
		}
		
	}

	private void importQueueNames (LinkedList<String> queueNames) {

		// This operation can reset the list of monitored queues
		// i.e. No logic about checking each name and inserting only *new* entries
		this.monitoredQueues = new LinkedList<MonitoredQueue>();

		queueNames.forEach(
				(queue) -> monitoredQueues.add(new MonitoredQueue(queue))
				);

	}

}
