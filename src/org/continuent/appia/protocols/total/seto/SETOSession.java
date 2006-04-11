
/**
 * Appia: Group communication and protocol composition framework library
 * Copyright 2006 University of Lisbon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 *
 * Initial developer(s): Nuno Carvalho and Jose' Mocito.
 * Contributor(s): See Appia web page for a list of contributors.
 */
package org.continuent.appia.protocols.total.seto;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.ListIterator;

import org.apache.log4j.Logger;

import org.continuent.appia.core.AppiaEventException;
import org.continuent.appia.core.AppiaException;
import org.continuent.appia.core.Channel;
import org.continuent.appia.core.Direction;
import org.continuent.appia.core.Event;
import org.continuent.appia.core.EventQualifier;
import org.continuent.appia.core.Layer;
import org.continuent.appia.core.Session;
import org.continuent.appia.core.TimeProvider;
import org.continuent.appia.core.events.channel.ChannelClose;
import org.continuent.appia.core.events.channel.ChannelInit;
import org.continuent.appia.core.message.Message;
import org.continuent.appia.protocols.group.LocalState;
import org.continuent.appia.protocols.group.ViewState;
import org.continuent.appia.protocols.group.events.GroupSendableEvent;
import org.continuent.appia.protocols.group.intra.View;
import org.continuent.appia.protocols.group.sync.BlockOk;
import org.continuent.appia.protocols.total.common.RegularServiceEvent;
import org.continuent.appia.protocols.total.common.SETOServiceEvent;
import org.continuent.appia.protocols.total.common.UniformServiceEvent;
import org.continuent.appia.xml.interfaces.InitializableSession;
import org.continuent.appia.xml.utils.SessionProperties;

/**
 * Optimistical total order protocol implementing the algorithm described in the paper
 * <i>Optimistic Total Order in Wide Area Networks</i> from A. Sousa, J. Pereira,
 * F. Moura and R. Oliveira.
 * 
 * @author Nuno Carvalho and Jose Mocito
 */
public class SETOSession extends Session implements InitializableSession {
	
	private static Logger log = Logger.getLogger(SETOSession.class);
	
	private int lastsender;
	private long lastfinal;
	private long lastfast;
	private double alfa;
	private long globalSN;
	private long localSN;
	private long sendingLocalSN;

	private boolean isBlocked = true;
	
	private LocalState ls = null;
	private ViewState vs = null;
	private Channel channel = null;
	private TimeProvider timeProvider = null;
	private final int seq = 0;
	
	
	private LinkedList R = new LinkedList(), // Received 
		S = new LinkedList(),  // Sequence
		G = new LinkedList(),  // Regular
		O = new LinkedList();  // Optimistic
	private long [] delay = null, r_delay = null;
	
	/**
	 * Constructs a new TotalFastABSession.
	 * 
	 * @param layer
	 */
	public SETOSession(Layer layer) {
		super(layer);
		alfa=0.95; // default
		reset();
	}

	/**
	 * Initialization method.
	 * 
	 * @see appia.xml.interfaces.InitializableSession#init(appia.xml.utils.SessionProperties)
	 */
	public void init(SessionProperties params) {
		if(params.containsKey("alfa")){
			alfa = params.getDouble("alfa");			
		}
		log.info("Initializing static parameter alfa. Set to "+alfa);
	}

	
	/** 
	 * Main handler of events
	 * @see appia.Session#handle(appia.Event)
	 */
	public void handle(Event event){
		if(log.isDebugEnabled())
			log.debug("MAIN Handle: "+event + " Direction is "+(event.getDir()==Direction.DOWN? "DOWN" : "UP"));

		if(event instanceof ChannelInit)
			handleChannelInit((ChannelInit) event);
		else if(event instanceof ChannelClose)
			handleChannelClose((ChannelClose)event);
		else if(event instanceof SeqOrderEvent)
			handleSequencerMessage((SeqOrderEvent)event);
		else if (event instanceof UniformInfoEvent)
			handleUniformInfo((UniformInfoEvent) event);
		else if(event instanceof GroupSendableEvent)
			handleGroupSendable((GroupSendableEvent)event);
		else if(event instanceof View)
			handleNewView((View)event);
		else if(event instanceof BlockOk)
			handleBlockOk((BlockOk)event);
		else if(event instanceof SETOTimer)
			handleTimer((SETOTimer)event);
		else if (event instanceof UniformTimer)
			handleUniformTimer((UniformTimer) event);
		else{
			log.warn("Got unexpected event in handle: "+event+". Forwarding it.");
			try {
				event.go();
			} catch (AppiaEventException e) {
				e.printStackTrace();
			}
		}
	}

	private void handleChannelInit(ChannelInit init) {
		channel = init.getChannel();
		timeProvider = channel.getTimeProvider();
		try {
			init.go();
		} catch (AppiaEventException e) {
			e.printStackTrace();
		}
		
		UniformTimer ut;
		try {
			ut = new UniformTimer(UNIFORM_INFO_PERIOD,init.getChannel(),Direction.DOWN,this,EventQualifier.ON);
			ut.go();
		} catch (AppiaEventException e) {
			e.printStackTrace();
		} catch (AppiaException e) {
			e.printStackTrace();
		}
	}
	
	private void handleChannelClose(ChannelClose close) {
		log.warn("Channel is closing!");
		channel = null;
		try {
			close.go();
		} catch (AppiaEventException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * The group os blocked. It is going to change view.
	 * @param ok
	 */
	private void handleBlockOk(BlockOk ok) {
		log.debug("The group is blocked.");
		log.debug("Impossible to send messages. Waiting for a new View");
		isBlocked = true;
		
		sendUniformInfo(ok.getChannel());
		
		try {
			ok.go();
		} catch (AppiaEventException e) {
			e.printStackTrace();
		}
	}

	/**
	 * New view.
	 * @param view
	 */
	private void handleNewView(View view) {
		isBlocked = false;
		ls=view.ls;
		vs=view.vs;
		
		// Sends any pending messages before delivering the new view to other layers
		deliverRegular();
		deliverUniform();
		dumpPendingMessages();

		log.debug(vs.toString());
		log.debug(ls.toString());
		log.debug("NEW VIEW: My rank: "+ls.my_rank+" My ADDR: "+vs.addresses[ls.my_rank]);

		// resets sequence numbers and information about delays
		reset();
		delay = new long[vs.addresses.length];
		Arrays.fill(delay,0);
		r_delay = new long[vs.addresses.length];
		Arrays.fill(r_delay,0);

		lastOrderList = new long[vs.view.length];
		Arrays.fill(lastOrderList,0);

		try {
			view.go();
		} catch (AppiaEventException e) {
			e.printStackTrace();
		}
		
	}
	
	private long timeLastMsgSent;
	private static final long UNIFORM_INFO_PERIOD = 10;
	
	private void handleUniformTimer(UniformTimer timer) {
		log.debug("Uniform timer expired. Now is: "+timeProvider.currentTimeMicros());
		if (!isBlocked && timeProvider.currentTimeMicros() - timeLastMsgSent >= UNIFORM_INFO_PERIOD) {
			log.debug("Last message sent was at time "+timeLastMsgSent+". Will send Uniform info!");
			sendUniformInfo(timer.getChannel());
		}
	}
	
	private void sendUniformInfo(Channel channel) {
		UniformInfoEvent event = new UniformInfoEvent();
		
		Message msg = event.getMessage();
		for (int i = 0; i < lastOrderList.length; i++)
			msg.pushLong(lastOrderList[i]);
		
		event.setChannel(channel);
		event.setDir(Direction.DOWN);
		event.setSource(this);
		try {
			event.init();
			event.go();
		} catch (AppiaEventException e) {
			e.printStackTrace();
		}
	}
	
	private long[] lastOrderList;
	
	private void handleUniformInfo(UniformInfoEvent event) {
		log.debug("Received UniformInfo from "+event.orig+". Uniformity information table now is: ");
		Message msg = event.getMessage();
		long[] uniformInfo = new long[vs.view.length];
		for (int i = uniformInfo.length; i > 0; i--)
			uniformInfo[i-1] = msg.popLong();
		mergeUniformInfo(uniformInfo);
		if (log.isDebugEnabled())
			for (int i = 0; i < lastOrderList.length; i++)
				log.debug("RANK :"+i+" | LAST_ORDER: "+lastOrderList[i]);
		deliverUniform();
	}
	
	private void mergeUniformInfo(long[] table) {
		for (int i = 0; i < table.length; i++)
			if (table[i] > lastOrderList[i])
				lastOrderList[i] = table[i];
	}
	
	/**
	 * Received a message delayed by a timer.
	 * @param timer
	 */
	private void handleTimer(SETOTimer timer) {
		long now = timeProvider.currentTimeMicros();
		log.debug(ls.my_rank+": received timer on "+now);
		deliverOptimistic(timer.container);
	}
	
	/**
	 * Received Sequencer message
	 * @param message
	 */
	private void handleSequencerMessage(SeqOrderEvent message) {
		log.debug("Received SEQ message from "+message.orig+" timestamp is "+timeProvider.currentTimeMicros());
		if(message.getDir() == Direction.DOWN)
			log.error("Wrong direction (DOWN) in event "+message.getClass().getName());
		else
			reliableSEQDeliver(message);	
	}

	/**
	 * @param event
	 */
	private void handleGroupSendable(GroupSendableEvent event) {
		log.debug("------------> " + vs.addresses[ls.my_rank] + " Received from "+vs.addresses[event.orig]);
		// events from the application
		if(event.getDir() == Direction.DOWN){
			if(isBlocked){
				log.error("Received event while blocked. ignoring it.");
				return;
			}
			long msgDelay = max(delay) - delay[seq];
			reliableDATAMulticast(event, msgDelay);
		}
		// events from the network
		else{
			reliableDATADeliver(event);
		}		
	}

	/**
	 * Multicast a DATA event to the group.
	 * 
	 * @param event the event to be multicasted.
	 * @param msgDelay the message delay associated with the event.
	 */
	private void reliableDATAMulticast(GroupSendableEvent event, long msgDelay) {
		DATAHeader header = new DATAHeader(ls.my_rank, sendingLocalSN++, msgDelay);
		DATAHeader.push(header,event.getMessage());
		Message msg = event.getMessage();
		for (int i = 0; i < lastOrderList.length; i++)
			msg.pushLong(lastOrderList[i]);
		log.debug("Sending DATA message from appl. Rank="+ls.my_rank+" SN="+sendingLocalSN+" Delay="+msgDelay);
		try {
			event.go();
		} catch (AppiaEventException e) {
			e.printStackTrace();
		}
		timeLastMsgSent = timeProvider.currentTimeMicros();
	}
	
	/**
	 * Deliver a DATA event received from the network.
	 * 
	 * @param event the event received from the network.
	 */
	private void reliableDATADeliver(GroupSendableEvent event){
		Message msg = event.getMessage();
		long[] uniformInfo = new long[vs.view.length];
		for (int i = uniformInfo.length; i > 0; i--)
			uniformInfo[i-1] = msg.popLong();
		mergeUniformInfo(uniformInfo);
		DATAHeader header = DATAHeader.pop(event.getMessage());
		log.debug("Received DATA message: "+header.id+":"+header.sn+" timestpamp is "+timeProvider.currentTimeMicros());
		
//		GroupSendableEvent clone = null;
//		try {
//			clone = (GroupSendableEvent) event.cloneEvent();
//		} catch (CloneNotSupportedException e) {
//			e.printStackTrace();
//		}
//		try {
//			// deliver spontaneous event to application
//			SpontaneousEvent spontaneous = new SpontaneousEvent(channel,Direction.UP,this,clone);
//			spontaneous.go();
//		} catch (AppiaEventException e1) {
//			e1.printStackTrace();
//		}
		
		header.setTime(delay[header.id]+timeProvider.currentTimeMicros());
		ListContainer container = new ListContainer(event, header);
		// add the event to the RECEIVED list...
		R.addLast(container);
		// ... and set a timer to be delivered later, acording to the delay that came with the message
		setTimer(container,delay[header.id]);
		
		// Deliver event to the upper layer (spontaneous order)
		try {
			event.go();
		} catch (AppiaEventException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Multicast a SEQUENCER message to the group.
	 * 
	 * @param container the container of the message to be sequenced.
	 */
	private void reliableSEQMulticast(ListContainer container) {
		SEQHeader header = new SEQHeader(container.header.sender(), container.header.sn(), globalSN);
		SeqOrderEvent event;
		try {
			event = new SeqOrderEvent(channel,Direction.DOWN,this,vs.group,vs.id);
			SEQHeader.push(header,event.getMessage());
			Message msg = event.getMessage();
			for (int i = 0; i < lastOrderList.length; i++)
				msg.pushLong(lastOrderList[i]);
			log.debug("Sending SEQ message. Rank="+ls.my_rank+" Header: "+header);
			event.go();
		} catch (AppiaEventException e2) {
			e2.printStackTrace();
		}
	}
	
	/**
	 * Deliver a SEQUENCER message received from the network.
	 */
	private void reliableSEQDeliver(SeqOrderEvent event) {
		Message msg = event.getMessage();
		long[] uniformInfo = new long[vs.view.length];
		for (int i = uniformInfo.length; i > 0; i--)
			uniformInfo[i-1] = msg.popLong();
		mergeUniformInfo(uniformInfo);
		SEQHeader header = SEQHeader.pop(event.getMessage());
		log.debug("["+ls.my_rank+"] Received SEQ message "+header.id+":"+header.sn+" timestamp is "+timeProvider.currentTimeMicros());
		lastOrderList[ls.my_rank] = header.order;
		
		// add it to the sequencer list
		S.add(new ListSEQContainer(header,timeProvider.currentTimeMicros()));
		log.debug("Received SEQ from "+event.orig+" at time "+timeProvider.currentTimeMicros());
		// and tries to deliver messages that already have the order
		deliverRegular();
		deliverUniform();
	}

	private void deliverOptimistic(ListContainer container){//,long time) {
		if (!O.contains(container)) {
			// clone event to keep a copy
//			GroupSendableEvent clone = null;
//			try {
//				clone = (GroupSendableEvent) container.event.cloneEvent();
//			} catch (CloneNotSupportedException e) {
//				e.printStackTrace();
//			}
			log.debug("Delivering optimistic message.");
			try {
				// deliver optimistic event to application
//				OptimisticEvent optimistic = new OptimisticEvent(channel,Direction.UP,this,clone);
//				optimistic.go();
				SETOServiceEvent sse = new SETOServiceEvent(channel, Direction.UP, this, container.event.getMessage());
				sse.go();
			} catch (AppiaEventException e1) {
				e1.printStackTrace();
			} 
			O.add(container);
			if(coordinator() && !isBlocked) {
				log.debug("I'm the coordinator. Sending message to order");
				globalSN++;
				reliableSEQMulticast(container);
				r_delay[container.header.id] = container.header.get_delay();
				delay[ls.my_rank] = max(r_delay);
			}
		}
	}
	
	/**
	 * Tries to deliver REGULAR message.
	 */
	private void deliverRegular() {
		boolean finnished = false;
		while (!finnished) {
			ListSEQContainer orderedMsg = getOrderedMessage(localSN+1);
			if (log.isDebugEnabled()) {
				log.debug("Message in order with SN="+(localSN+1)+" -> "+orderedMsg);
				log.debug("Messages in S {");
				listOrderedMessage();
				log.debug("}");
			}
		
			if (orderedMsg != null) {
				ListContainer msgContainer = getMessage(orderedMsg.header,R);
				// FIXME NOTE: uncomment next line to always deliver optimistic message before regular delivery.
				//deliverOptimistic(msgContainer);
				
				if (msgContainer != null && !hasMessage(orderedMsg,G)) {
					O.add(msgContainer);
//					GroupSendableEvent clone = null;
//					try {
//						clone = (GroupSendableEvent) msgContainer.event.cloneEvent();
//					} catch (CloneNotSupportedException e) {
//						e.printStackTrace();
//					}
					log.debug("["+ls.my_rank+"] Delivering regular "+msgContainer.header.id+":"+msgContainer.header.sn+" timestamp "+timeProvider.currentTimeMicros());
					try {
						// deliver regular event to application
//						RegularEvent regular = new RegularEvent(channel,Direction.UP,this,clone);
//						regular.go();
						RegularServiceEvent rse = new RegularServiceEvent(channel, Direction.UP, this, msgContainer.event.getMessage());
						rse.go();
					} catch (AppiaEventException e1) {
						e1.printStackTrace();
					}
					G.addLast(orderedMsg);
					
					// ADJUSTING DELAYS
					log.debug(ls.my_rank+": Adjusting delays...");
					long _final = orderedMsg.time;
					long _fast = msgContainer.header.getTime();
					int _sender = msgContainer.header.id;
					if(lastsender != -1){
						log.debug("continueing adjusting the delays!");
						log.debug("_final:"+_final+" | lastfinal:"+lastfinal+" | _fast:"+_fast+" | lastfast:"+lastfast);
						long delta = (_final - lastfinal) - (_fast - lastfast);
						log.debug("DELTA: "+delta);
						if(delta > 0) {
							log.debug("adjust("+lastsender+","+_sender+","+delta+")");
							adjust(lastsender,_sender,delta);
						}
						else if (delta < 0) {
							log.debug("adjust("+_sender+","+lastsender+","+delta+")");
							adjust(_sender,lastsender,-delta);
						}
					}
					lastsender = _sender;
					lastfast = _fast;
					lastfinal = _final;
					localSN++;
				}
			}
			else {
				log.debug("DeliverRegular is finnishing.");
				finnished = true;
			}
		}
	}
	
	/**
	 * Tries to deliver Uniform messages.
	 */
	private void deliverUniform() {
		log.debug("Trying to deliver FINAL messages!");
		ListIterator it = G.listIterator();
		while (it.hasNext()) {
			ListSEQContainer nextMsg = (ListSEQContainer)it.next();
			
			if (isUniform(nextMsg.header)) {
				ListContainer msgContainer = getRemoveMessage(nextMsg.header,R);
				log.debug("Resending message to Appl: "+msgContainer.event);
				log.debug("["+ls.my_rank+"] Delivering final "+msgContainer.header.id+":"+msgContainer.header.sn+" timestamp "+timeProvider.currentTimeMicros());
				
//				delivery(msgContainer.event);
				try {
					// deliver uniform notification
					UniformServiceEvent use = new UniformServiceEvent(channel, Direction.UP, this, msgContainer.event.getMessage());
					use.go();
				} catch (AppiaEventException e) {
					e.printStackTrace();
				}
				it.remove();
			}
		}
	}
	
	/**
	 * Resets all sequence numbers and auxiliary variables
	 */
	private void reset(){
		globalSN = 0;
		localSN = 0;
		sendingLocalSN = 0;
		lastfinal=-1;
		lastfast=-1;
		lastsender=-1;
	}

	/**
	 * Checks if the message is uniform.
	 * 
	 * @param header the header of the message.
	 * @return <tt>true</tt> if the message is uniform, <tt>false</tt> otherwise.
	 */
	private boolean isUniform(SEQHeader header) {
		int seenCount = 0;
		for (int i = 0; i < lastOrderList.length; i++)
			if (lastOrderList[i] >= header.order)
				seenCount++;
		if (seenCount > lastOrderList.length/2)
			return true;
		return false;
	}
	
	/**
	 * In a view change, if there are messages that were not delivered, deliver them
	 * in a deterministic order. This can be done because VSync ensures that when a new View
	 * arrives, all members have the same set of messages.
	 */
	private void dumpPendingMessages() {
		boolean finnished = false;
		while( !finnished){
			ListContainer msgContainer = getNextDeterministic();
			if(log.isDebugEnabled()){
				log.debug("Message in deterministic order with SN="+(localSN+1)+" -> "+msgContainer);
			}
			if(msgContainer != null){
				log.debug("Resending message to Appl: "+msgContainer.event);
				delivery(msgContainer.event);
				getRemoveMessage(msgContainer.header,R);
				localSN++;
			}
			else
				finnished = true;
		}
	}

    /**
     * Removes and returns an event from the buffer in a deterministic way.
     * Used when there are view changes in the group
     */
    private ListContainer getNextDeterministic(){
    	
    	if(R.size() == 0)
    		return null;
		
        ListContainer first = (ListContainer) R.getFirst();
    	
        long nSeqMin=first.header.sn;
        int emissor=first.header.id;
        int pos=0;

        for(int i=1; i<R.size(); i++){
            ListContainer current = (ListContainer) R.get(i);
            if(nSeqMin > current.header.sn){
                pos=i;
                nSeqMin=current.header.sn;
                emissor=current.header.id;
            }
            else if(nSeqMin == current.header.sn){
                if(emissor > current.header.id){
                    pos=i;
                    emissor=current.header.id;
                }
            }
        } 
        return (ListContainer) R.remove(pos);
	}

    /**
     * Get and remove a message from a list
     */
	private ListContainer getRemoveMessage(Header header, LinkedList list){
		ListIterator it = list.listIterator();
		while(it.hasNext()){
			ListContainer cont = (ListContainer) it.next();
			if(cont.header.equals(header)){
				it.remove();
				return cont;
			}
		}
		return null;
	}
	
	/**
     * Get a message from a list
     */
	private ListContainer getMessage(Header header, LinkedList list){
		ListIterator it = list.listIterator();
		while(it.hasNext()){
			ListContainer cont = (ListContainer) it.next();
			if(cont.header.equals(header))
				return cont;
		}
		return null;
	}
	
	/**
	 * Check if the list has the given message.
	 */
	private boolean hasMessage(ListSEQContainer msg, LinkedList list) {
		ListIterator it = list.listIterator();
		while(it.hasNext()){
			ListSEQContainer cont = (ListSEQContainer) it.next();
			if(cont.header.equals(msg.header)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Get the next ordered message.
	 */
	private ListSEQContainer getOrderedMessage(long ord){
		for (ListIterator li=S.listIterator(); li.hasNext();){
			ListSEQContainer cont = (ListSEQContainer) li.next();
			if(cont.header.order == ord){
				li.remove();
				return cont;
			}
		}
		return null;
	}

	/**
	 * List the order.<br>
	 * <b>FOR DEBUGGING PURPOSES ONLY!</b>
	 */
	private ListSEQContainer listOrderedMessage(){
		for (ListIterator li=S.listIterator(); li.hasNext();){
			ListSEQContainer cont = (ListSEQContainer) li.next();
			log.debug("Element: "+cont.header);
		} 
		return null;
	}

	
	/**
	 * Delivers a message to the layer above.
	 */
	private void delivery(GroupSendableEvent event){
		try {
			event.setSource(this);
			event.init();
			event.go();
		} catch (AppiaEventException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Sets a timer to delay a message that came from the network.
	 */
	private void setTimer(ListContainer container, long timeout) {
		try {
			log.debug("TIME Container: "+container.header.getTime());
			SETOTimer timer = new SETOTimer(timeout/1000, channel, 
					Direction.DOWN, this, EventQualifier.ON, container);
				timer.go();
				if(log.isDebugEnabled())
					log.debug("Setting new timer. NOW is "+
							timeProvider.currentTimeMicros()+" timer to "+timer.getTimeout());
		} catch (AppiaEventException e) {
			e.printStackTrace();
		} catch (AppiaException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * MAX of a list of numbers.
	 */
	private long max(long[] a){
		long m=a[0];
		for(int i=1; i< a.length; i++)
			if(a[i]>m)
				m=a[i];
		return m;
	}
	
	/**
	 * Checks if the current process is the coordinator.<br>
	 * The coordinator is also the sequencer. and is the member that has the rank 0
	 */
	private boolean coordinator(){
		return ls != null && ls.my_rank == 0;
	}
	
	/**
	 * Adjust the delays.
	 */
	private void adjust(int i, int j, long d){
		double v = ((delay[i] * alfa) + (delay[i] - d) * (1 - alfa));
		if(v >= 0)
			delay[i] = Math.round(v);
		else{
			delay[i] = 0;
			delay[j] = delay[j] - Math.round(v);
		}
	}
		
}


/*
 * #######################################################
 *  support classes
 * #######################################################
 */
/**
 * Class container to help putting all information into LinkedLists
 * 
 * @author Nuno Carvalho
 */
class ListContainer {
	GroupSendableEvent event;
	DATAHeader header;

	public ListContainer(GroupSendableEvent e, DATAHeader h) {//, long t){
		event = e;
		header = h;
	}
}

/**
 * Class container to help putting all information into LinkedLists
 * 
 * @author Nuno Carvalho
 */
class ListSEQContainer {
	SEQHeader header;
	long time;
	
	public ListSEQContainer(SEQHeader h, long t){
		header = h;
		time = t;
	}
}

/**
 * Header of messages.
 * 
 * @author Nuno Carvalho
 */
class Header {
	
	int id;
	long sn;
	
	public boolean equals(Object o){
		if(o instanceof Header){
			Header h = (Header) o;
			return h.id == this.id && h.sn == this.sn;
		}
		return false;
	}
	
	public String toString(){
		return "Header ID="+id+" SN="+sn; 
	}
}

/**
 * Header of SEQUENCE messages.
 * 
 * @author Nuno Carvalho
 */
class SEQHeader extends Header {
	
	long order;
	
	public SEQHeader(){
		id = -1;
		sn = order = -1;
	}
	
	public SEQHeader(int id, long sn, long order){
		this.id = id;
		this.sn = sn;
		this.order = order;
	}
	
	public String toString(){
		return super.toString()+" ORDER="+order;
	}
	
	/**
	 * Push all parameters of a Header into a Appia Message.
	 * @param header header to push into the message
	 * @param message message to put the header
	 */
	public static void push(SEQHeader header, Message message){
		message.pushInt(header.id);
		message.pushLong(header.sn);
		message.pushLong(header.order);
	}
	
	
	/**
	 * Pops a header from a message. Creates a new Header from the values contained by the message.
	 * @param message message that contains the info to build the header
	 * @return a header builted from the values of contained by the message
	 */
	public static SEQHeader pop(Message message){
		SEQHeader header = new SEQHeader();
		header.order = message.popLong();
		header.sn = message.popLong();
		header.id = message.popInt();
		return header;
	}

}

/**
 * Header of DATA messages.
 * 
 * @author Nuno Carvalho
 */
class DATAHeader extends Header {
	/**
	 * delay of the message
	 */
	private long delay;
	/**
	 * Time when the message was sent.
	 */
	private long time;

	private int stable_id;
	private long stable_seqno;
	
	public DATAHeader(int sender, long sn, long d, long t){
		this.id=sender;
		this.sn=sn;
		delay=d;
		time=t;
	}
	
	public DATAHeader(int sender, long sn, long d){
		this.id=sender;
		this.sn=sn;
		delay=d;
	}

	public DATAHeader(int sender, long sn){
		this.id=sender;
		this.sn=sn;
		delay=0;
	}

	public DATAHeader(DATAHeader obj){
		this.id=obj.sender();
		this.sn=obj.sn();
		delay=obj.get_delay();
	}
	
	/**
	 * gets the sender od the message.
	 * @return sender of the message
	 */
	public int sender(){
		return id;
	}

	/**
	 * Gets the Serial Number of the message.
	 * @return serial number of the message
	 */
	public long sn(){
		return sn;
	}

	/**
	 * sets the delay of the message.
	 * @param i delay of the message
	 */
	public void set_delay(int i){
		delay=i;
	}

	/**
	 * Gets the delay of the message.
	 * @return delay of the message
	 */
	public long get_delay(){
		return delay;
	}

	public void setStableId(int stable_id) {
		this.stable_id = stable_id;
	}
	
	public int getStableId() {
		return stable_id;
	}
	
	public void setStableSeqNo(long seqno) {
		this.stable_seqno = seqno;
	}
	
	public long getStableSeqNo() {
		return stable_seqno;
	}
	
	/**
	 * Push all parameters of a Header into a Appia Message.
	 * @param header header to push into the message
	 * @param message message to put the header
	 */
	public static void push(DATAHeader header, Message message){
		message.pushInt(header.id);
		message.pushLong(header.sn);
		message.pushLong(header.delay);
	}
	
	
	/**
	 * Pops a header from a message. Creates a new Header from the values contained by the message.
	 * @param message message that contains the info to build the header
	 * @return a header builted from the values of contained by the message
	 */
	public static DATAHeader pop(Message message){
		DATAHeader header = new DATAHeader(-1,-1);
		header.delay = message.popLong();
		header.sn = message.popLong();
		header.id = message.popInt();
		return header;
	}
	
	/**
	 * @return Returns the time.
	 */
	public long getTime() {
		return time;
	}
	
	/**
	 * @param time The time to set.
	 */
	public void setTime(long time) {
		this.time = time;
	}
	
}
