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
 * Initial developer(s): Alexandre Pinto and Hugo Miranda.
 * Contributor(s): See Appia web page for a list of contributors.
 */
 package org.continuent.appia.protocols.frag;

//////////////////////////////////////////////////////////////////////
//                                                                  //
// Appia: protocol development and composition framework            //
//                                                                  //
// Version: 1.0/J                                                   //
//                                                                  //
// Copyright, 2000, Universidade de Lisboa                          //
// All rights reserved                                              //
// See license.txt for further information                          //
//                                                                  //
// Class: FragSession: Fragmentation of messages                    //
//                                                                  //
// Author: Hugo Miranda, 05/2000                                    //
//                                                                  //
// Change Log:                                                      //
//  16/Oct/2001: Enriched debugging output                          //
//  11/Jul/2001: The debugOn variable was changed to the            //
//               FragConfig interface                               //
//   9/Feb/2001: The switch tests on exceptions are made against    //
//               the static class attribute instead of the          //
//               static attribute in the exception instance.        //
//////////////////////////////////////////////////////////////////////


import java.io.PrintStream;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Iterator;

import org.continuent.appia.core.*;
import org.continuent.appia.core.events.SendableEvent;
import org.continuent.appia.core.events.channel.ChannelClose;
import org.continuent.appia.core.events.channel.ChannelInit;
import org.continuent.appia.core.events.channel.Debug;
import org.continuent.appia.core.message.Message;
import org.continuent.appia.xml.interfaces.InitializableSession;
import org.continuent.appia.xml.utils.SessionProperties;

/**
 * This is the Session class of the fragmentation protocol.
 *
 *
 * @author Hugo Miranda
 * @see FragLayer
 * @see Session
 * @see org.continuent.appia.protocols.fifo.FifoSession
 */

public class FragSession extends Session implements InitializableSession {
  
  private int msgSeq = 0;
  private HashMap pdus;
  private HashMap sources;
  
  private final int fragHeaderSize = 8;
  
  private int param_FRAG_SIZE=-1;
  public static final int TIMER_PERIOD=30000; // 30 secs
  private Channel timerChannel=null;
  

  private PrintStream debugOutput = System.err;
  
  /* Helper classes */
  
  private class PDUSize {
    
    public boolean def;
    public int size;
    
    // Messages waiting for the maxpdusize to be known wait here
    public LinkedList holding;
    
    public PDUSize() {
      def = false;
      holding = new LinkedList();
    }
  }
  
  private class FragHolder {
    public ArrayList frags;
    public Object source;
    public int msgId;
    public SendableEvent e=null;
    public boolean second_chance=false;
    
    public FragHolder(Object source, int msgId, int nfrags) {
      this.source = source;
      this.msgId = msgId;
      frags=new ArrayList(nfrags);
    }
    
    public void ensureSize(int size) {
      while (frags.size() < size)
        frags.add(null);
    }
  }
  
  /**
   * Standard constructor
   *
   * @param layer The FragLayer instance
   * @see Session
   */
  public FragSession(Layer layer) {
    super(layer);
    pdus = new HashMap(19);
    sources=new HashMap();
  }

  /**
   * Possible parameters:
   * <ul>
   * <li><b>frag_size</b> the maximum payload per fragment
   * <ul>
   */
  public void init(SessionProperties params) {
    if (params.containsKey("frag_size"))
      param_FRAG_SIZE=params.getInt("frag_size");
  }
  
  private FragHolder findFragHolder(Object who, int msgId) {
    ArrayList msgs=(ArrayList)sources.get(who);
    if (msgs == null)
      return null;
    for (int i=0 ; i < msgs.size() ; i++) {
      FragHolder f = (FragHolder)msgs.get(i);
      if (f.msgId == msgId)
        return f;
    }
    return null;
  }
  
  private void addFragHolder(FragHolder fh) {
    ArrayList msgs=(ArrayList)sources.get(fh.source);
    if (msgs == null) {
      msgs=new ArrayList();
      sources.put(fh.source, msgs);
    }
    msgs.add(fh);
  }
  
  private void removeFragHolder(FragHolder fh) {
    ArrayList msgs=(ArrayList)sources.get(fh.source);
    if (msgs == null)
      return;
    msgs.remove(fh);
  }
  
  /**
   * The protocol's event handler.
   *
   * @param e The received event.
   */
  public void handle(Event e) {
    /* Main switch function */
    if (e instanceof Debug)
      handleDebug((Debug) e);
    else if (e instanceof ChannelInit)
      //queryPDUSize((ChannelInit) e);
      initChannel((ChannelInit) e);
    else if (e instanceof ChannelClose)
      closedChannel((ChannelClose) e);
    else if (e instanceof MaxPDUSizeEvent)
      setPDUSize((MaxPDUSizeEvent) e);
    else if (e instanceof FragTimer)
      updateHolding();
    else if (e instanceof SendableEvent) {
      if (e.getDir() == Direction.UP)
        reassembly((SendableEvent) e);
      else
        split((SendableEvent) e);
    } else {
      /* For performance measures only */
      try {
        e.go();
      } catch (AppiaEventException ex) {
      }
    }
  }
  
  private void initChannel(ChannelInit ev) {
    if (timerChannel == null)
      sendTimer(ev.getChannel());
    queryPDUSize(ev);
  }
  
  private void closedChannel(ChannelClose ev) {
    if (ev.getChannel() == timerChannel)
      timerChannel=null;
    try { ev.go(); } catch (AppiaEventException ex) { ex.printStackTrace(); }
  }
  
  private void sendTimer(Channel channel) {
    try {
      FragTimer timer=new FragTimer(TIMER_PERIOD,channel,this,EventQualifier.ON);
      timer.go();
      timerChannel=channel;
      
      if (FragConfig.debugOn && debugOutput != null)
        debugOutput.println("Frag: Sent Timer.");
      
    } catch (AppiaEventException ex) {
      ex.printStackTrace();
      timerChannel=null;
    } catch (AppiaException ex) {
      ex.printStackTrace();
      timerChannel=null;
    }
  }
  
  private void updateHolding() {
    
    if (FragConfig.debugOn && debugOutput != null) {
      debugOutput.println("Frag: Received Timer");
      printState(debugOutput);
    }
    
    Iterator iter=sources.values().iterator();
    while (iter.hasNext()) {
      ArrayList msgs=(ArrayList)iter.next();
      Iterator i=msgs.iterator();
      while (i.hasNext()) {
        FragHolder f = (FragHolder)i.next();
        if (f.second_chance) {
          i.remove();
          if (FragConfig.debugOn && debugOutput != null)
            debugOutput.println("Frag: Discarded message from "+f.source.toString()+" with id "+f.msgId);
        } else {
          f.second_chance=true;
        }
      }
      if (msgs.size() == 0)
        iter.remove();
    }
  }
  
  private void queryPDUSize(ChannelInit e) {
    try {
                        /* Create the HashTable entry for this channel. The entry is
                           marked as undefined because we still don't know the max
                           pdu size
                         */
      if (FragConfig.debugOn && debugOutput != null)
        debugOutput.println(
        "Frag: new channel opened. "
        + "Sending query to retrieve PDU size.");
      pdus.put(e.getChannel(), new PDUSize());
      MaxPDUSizeEvent max = new MaxPDUSizeEvent(e.getChannel(),Direction.DOWN,this);
      max.init();
      
      e.go();
      max.go();
    } catch (AppiaEventException ex) {
      switch (ex.type) {
        case AppiaEventException.UNKNOWNSESSION :
          System.err.println(
          "An exception stating that this "
          + "class does not belong to the "
          + "channel was raised in "
          + "Frag");
          break;
        case AppiaEventException.ATTRIBUTEMISSING :
          System.err.println(
          "Missing attribute exception in "
          + "Frag");
          break;
        case AppiaEventException.UNWANTEDEVENT :
          System.err.println(
          "The peer send an Unwanted event in "
          + "Frag");
          break;
        case AppiaEventException.NOTINITIALIZED :
          System.err.println(
          "Event not initialized exception "
          + "ocurred after initialization in "
          + "Frag");
          break;
      }
    }
  }
  
  private void setPDUSize(MaxPDUSizeEvent e) {
    PDUSize p = (PDUSize) pdus.get(e.getChannel());
    p.size = e.pduSize - fragHeaderSize;
    p.def = true;
    if (FragConfig.debugOn && debugOutput != null)
      debugOutput.println(
      "Frag: PDU size for channel "
      + e.getChannel().getChannelID()
      + " is "
      + p.size
      + " bytes");
    while (!p.holding.isEmpty()) {
      if (FragConfig.debugOn && debugOutput != null)
        debugOutput.println(
        "Frag: forwarding messages waiting to "
        + "know the PDU size");
      split((SendableEvent) p.holding.removeFirst());
    }
    p.holding = null;
  }
  
  private void split(SendableEvent e) {
    if (param_FRAG_SIZE > 0)
      splitMessage(e, param_FRAG_SIZE);
    else {
      PDUSize p = (PDUSize) pdus.get(e.getChannel());
      if (p.def)
        splitMessage(e, p.size);
      else {
        if (FragConfig.debugOn && debugOutput != null)
          debugOutput.println(
              "Received message to unknown PDU size "
              + "channel. Enqueing it.");
        p.holding.add(e);
      }   
    }
  }
  
  private void splitMessage(SendableEvent e, int fragSize) {
    Message orig = e.getMessage();
    try {
      int maxLength = fragSize;
      int nFrags=getFrags(orig.length(),maxLength);
      
      if (FragConfig.debugOn && debugOutput != null) {
        //debugOutput.println("Frag: Message with "+ orig.length()+ " bytes received for channel "+
        //e.getChannel().getChannelID()+ " ("+ maxLength+ " bytes max). It will be devided in "+nFrags+" frags. MsgId: "+msgSeq);
        debugOutput.println("Frag: Message with "+ orig.length()+ " bytes ("+ maxLength+ " bytes max). MsgId: "+msgSeq+" Num.Frags: "+nFrags);
      }
      
      if (orig.length() > maxLength) {
        //does not fit in one packet
        //send original event with fist part of payload
        //System.out.println("fragging the message. Size:: "+orig.length());
        Message m = new Message();
        orig.frag(m, maxLength);
        orig.pushInt(nFrags);
        orig.pushInt(msgSeq);
        e.go();
        
        orig = m;
        
        int fragNumber=1;
        //more fragments
        while (fragNumber < nFrags) {
          //create frags
          //send frags
          FragEvent f = new FragEvent(e, this);
          
          if (orig.length() > maxLength) {
            m = new Message();
            orig.frag(m, maxLength);
          } else {
            m=null;
          }

          orig.pushInt(fragNumber++);
          orig.pushInt(msgSeq);
          f.setMessage(orig);
          f.go();
          orig = m;
        }        
      } else {
        //fits in one event
        orig.pushInt(nFrags);
        orig.pushInt(msgSeq);
        e.go();        
      }
      msgSeq++;
      
    } catch (AppiaEventException ex) {
      msgSeq++;
      
      System.err.println(
      "Unexpected event exception while "
      + "fragmenting message");
    }
  }
  
  private void reassembly(SendableEvent e) {
    if (timerChannel == null)
      sendTimer(e.getChannel());
    
    try {
      /* Extract headers */
      int msgId = e.getMessage().popInt();
      int nFrags=e.getMessage().popInt();
      
      if (FragConfig.debugOn && debugOutput != null)
        debugOutput.println("Frag: message received with id: "+msgId+". Fragment: "+nFrags+" (is FragEvent = "+(e instanceof FragEvent)+")");

      FragHolder fHold=null;
      if (e instanceof FragEvent) {
        fHold = findFragHolder(e.source, msgId);
        if (fHold == null) {
          fHold=new FragHolder(e.source, msgId, 10);
          addFragHolder(fHold);
        }

        fHold.ensureSize(nFrags+1);
        fHold.frags.set(nFrags, e.getMessage());
      } else {
        if (nFrags <= 1) {
          e.go();
          return;
        }

        fHold = findFragHolder(e.source, msgId);
        if (fHold == null) {
          fHold=new FragHolder(e.source, msgId, nFrags);
          addFragHolder(fHold);
        }
        
        fHold.e=e;
        fHold.ensureSize(nFrags);
        fHold.frags.set(0, e.getMessage());
      }
      
      
      if (receivedAll(fHold.frags)) {
        Message msg=(Message)fHold.frags.get(0);
        for (int i=1 ; i < fHold.frags.size() ; i++)
          msg.join((Message)fHold.frags.get(i));
        
        fHold.e.setMessage(msg);
        fHold.e.go();
        if (FragConfig.debugOn && debugOutput != null)
            debugOutput.println("Frag: message reasembled : "+fHold.e);        
        removeFragHolder(fHold);
      } else {
        fHold.second_chance=false;
      }
    } catch (AppiaEventException ex) {
      System.err.println(
      "Unexpected event exception while "
      + "reassemblying message");
    }
  }
  
  private void handleDebug(Debug e) {
    
    int q = e.getQualifierMode();
    
    if (q == EventQualifier.ON) {
      debugOutput = new PrintStream(e.getOutput());
      debugOutput.println("Frag: Debugging started");
    } else if (q == EventQualifier.OFF) {
      debugOutput = null;
    } else if (q == EventQualifier.NOTIFY) {
      printState(new PrintStream(e.getOutput()));
    }
    
    try {
      e.go();
    } catch (AppiaEventException ex) {
    	ex.printStackTrace();
    }
  }
  
  private void printState(PrintStream out) {
    out.println("Frag Session state dumping:");
    
    Iterator ipdus=pdus.keySet().iterator();
    
    if (ipdus.hasNext())
      out.println("List of channels:");
    else
      out.println("No channels available");
    
    while (ipdus.hasNext()) {
      Channel c = (Channel) ipdus.next();
      out.print(
      "Channel name: "
      + c.getChannelID()
      + " Defined: ");
      PDUSize p = (PDUSize) pdus.get(c);
      if (p.def)
        out.println("yes. Max PDU size: " + p.size);
      else
        out.println("no.");
    }
    
    Iterator iter=sources.values().iterator();
    
    if (iter.hasNext())
      out.println("Pending messages:");
    else
      out.println("No pending messages.");
    
    while (iter.hasNext()) {
      ArrayList msgs=(ArrayList)iter.next();
      for (int i=0 ; i < msgs.size() ; i++) {
        FragHolder f = (FragHolder)msgs.get(i);
        int r=0;
        for (int j=0 ; j < f.frags.size() ; j++)
          if (f.frags.get(j) != null)
            r++;
        
        out.println(
        "Current message number of frags: "
        + f.frags.size()
        + " Fragments received: "
        + r
        + " SecondChance: "
        + f.second_chance);
      }
    }
    
    out.println(
    "Debug output is currently "
    + (debugOutput == null ? "off" : "on"));
  }
  
  private int getFrags(int length,int maxsize){
    int frags = length / maxsize;
    if(length % maxsize > 0)
      frags++;
    
    return frags;
  }
  
  private boolean receivedAll(ArrayList a) {
    return (a.indexOf(null) < 0);
  }
}