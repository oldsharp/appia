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
 
/**
 * Title:        Apia<p>
 * Description:  Protocol development and composition framework<p>
 * Copyright:    Copyright (c) Alexandre Pinto & Hugo Miranda & Luis Rodrigues<p>
 * Company:      F.C.U.L.<p>
 * @author Alexandre Pinto & Hugo Miranda & Luis Rodrigues
 * @version 1.0
 */
package org.continuent.appia.protocols.gossipServer;


import java.util.Vector;

import java.io.PrintStream;

import org.continuent.appia.core.*;
import org.continuent.appia.core.events.SendableEvent;
import org.continuent.appia.core.events.channel.ChannelInit;
import org.continuent.appia.core.events.channel.Debug;
import org.continuent.appia.core.message.Message;
import org.continuent.appia.protocols.common.FIFOUndeliveredEvent;
import org.continuent.appia.protocols.common.InetWithPort;
import org.continuent.appia.protocols.common.RegisterSocketEvent;
import org.continuent.appia.protocols.group.AppiaGroupException;
import org.continuent.appia.protocols.group.Endpt;
import org.continuent.appia.protocols.group.Group;
import org.continuent.appia.protocols.group.LocalState;
import org.continuent.appia.protocols.group.ViewID;
import org.continuent.appia.protocols.group.ViewState;
import org.continuent.appia.protocols.group.events.GroupInit;
import org.continuent.appia.protocols.group.heal.GossipOutEvent;
import org.continuent.appia.protocols.group.intra.View;
import org.continuent.appia.protocols.group.sync.BlockOk;
import org.continuent.appia.protocols.utils.ParseUtils;
import org.continuent.appia.xml.interfaces.InitializableSession;
import org.continuent.appia.xml.utils.SessionProperties;

public class GossipServerSession extends Session implements InitializableSession {

  public static final int DEFAULT_PORT=10000;
  public static final long DEFAULT_REMOVE_TIME=20000; // 20 secs
  public static final long DEFAULT_TIMER=1000; // 1 sec
    
  public static final String GROUP_NAME="Gossip Group";
  
  public GossipServerSession(Layer layer) {
    super(layer);

    if (debug != null) {
      if (debug instanceof PrintStream)
         this.debug=(PrintStream)debug;
      else
        this.debug=new PrintStream(debug);
    }
  }

  /**
   * Supported parameters:
   * <ul>
   * <li><b>port</b> the port listening for clients.
   * <li><b>remove_time</b> time of inactivity of a client before it is removed.
   * <li><b>timer</b> the internal timer duration, ie, the heartbeat of the server.
   * <li><b>gossip</b> other known gossip servers in the format "[host][:port][,[host][:port]]...".
   * <li><b>debug</b> boolean indicating whether debug messages should be printed to stderr.
   * </ul>
   */
  public void init(SessionProperties params) {
    if (params.containsKey("port"))
      my_port=params.getInt("port");
    if (params.containsKey("remove_time"))
      remove_time=params.getLong("remove_time");
    if (params.containsKey("timer"))
      timer=params.getLong("timer");
    if (params.containsKey("gossip")) {
      try {
        gossips=ParseUtils.parseInetWithPortArray(params.getString("gossip"),null,DEFAULT_PORT);
      } catch (Exception ex) {
        ex.printStackTrace();
        System.exit(1);
      }
    }
    if (params.containsKey("debug")) {
      if (params.getBoolean("debug"))
        debug=System.err;
    }
    
    if (remove_time < timer)
      max_idle_count=1;
    else
      max_idle_count=(int)(remove_time / timer + ((remove_time % timer) > 0 ? 1 : 0));
    
    debug("Initiated:"+params);
  }

  public void handle(Event event) {

    // ChannelInit
    if (event instanceof ChannelInit)
      handleChannelInit((ChannelInit)event);
    // GossipOutEvent
    else if (event instanceof GossipOutEvent)
      handleGossipOutEvent((GossipOutEvent)event);
    // FIFOUndeliveredEvent
    else if (event instanceof FIFOUndeliveredEvent)
      handleUndelivered((FIFOUndeliveredEvent)event);
    // GossipServerTimer
    else if (event instanceof GossipServerTimer)
      handleTimer((GossipServerTimer)event);
    // GossipGroupEvent
    else if (event instanceof GossipGroupEvent)
      handleGossipGroupEvent((GossipGroupEvent)event);
    // BlockOk
    else if (event instanceof BlockOk)
      handleBlockOk((BlockOk)event);
    // View
    else if (event instanceof View) 
      handleView((View)event);
    // RegisterSocketEvent
    else if (event instanceof RegisterSocketEvent)
      handleRegisterSocketEvent((RegisterSocketEvent)event);
    else {
      debug("Unwanted event (\""+event.getClass().getName()+"\") received. Continued...");
      try { event.go(); } catch (AppiaEventException ex) { ex.printStackTrace(); }
    }
  }

  private int my_port=DEFAULT_PORT;
  private InetWithPort[] gossips=null;
  private long remove_time=DEFAULT_REMOVE_TIME;
  private long timer=DEFAULT_TIMER;
  private int max_idle_count=-1;
  private Vector clients=new Vector();
  // Auxiliar, so we don't need to create one for every search
  private Client aux=new Client(null);

  private Channel groupChannel=null;
  private ViewState vs;
  private LocalState ls;

  private void handleChannelInit(ChannelInit ev) {      
    try { ev.go(); } catch (AppiaEventException ex) { ex.printStackTrace(); }

    if (ev.getChannel().getChannelID().equals(GROUP_NAME+" Channel")) {
      try {
        RegisterSocketEvent rse=new RegisterSocketEvent(ev.getChannel(),Direction.DOWN,this);
        rse.go();
      } catch (AppiaEventException ex) {
        ex.printStackTrace();
        throw new AppiaError("GossipServerSession: impossible to register socket for group");
      }

      System.out.println("GossipServer (group) running");
    } else {
      try {
        RegisterSocketEvent rse=new RegisterSocketEvent(ev.getChannel(),Direction.DOWN,this,my_port);
        rse.go();
      } catch (AppiaEventException ex) {
        ex.printStackTrace();
        throw new AppiaError("GossipServerSession: impossible to register socket for clients");
      }
      
      try {
        GossipServerTimer gst=new GossipServerTimer(timer,ev.getChannel(),this,EventQualifier.ON);
        gst.go();
      } catch (AppiaException ex) {
        ex.printStackTrace();
        throw new AppiaError("GossipServerSession: impossible to set timer");
      }
      
      if (debug != null) {
        try {
          Debug d=new Debug(debug,ev.getChannel(),Direction.DOWN,this,EventQualifier.ON);
          d.go();
        } catch (AppiaEventException ex) {
          ex.printStackTrace();
          debug("impossible to send Debug");
        }
      }
      
      System.out.println("GossipServer (client) running");
    }
  }

  private void handleGossipOutEvent(GossipOutEvent ev) {
    aux.addr=ev.source;
    int i;
    if ((i=clients.indexOf(aux)) >= 0) {
      Client c=(Client)clients.get(i);
      c.idle_count=0;
      sendAll(c,ev);
      sendGroup(c);
    } else {
      Client c=new Client(aux.addr);
      clients.add(c);
      sendAll(c,ev);
      sendGroup(c);
    }
  }

  private void handleUndelivered(FIFOUndeliveredEvent ev) {
    aux.addr=((SendableEvent)ev.what).dest;
    clients.removeElement(aux);
    try { ev.go(); } catch (AppiaEventException ex) { ex.printStackTrace(); }
  }

  private void handleTimer(GossipServerTimer ev) {

    try { ev.go(); } catch (AppiaEventException ex) { ex.printStackTrace(); }

    int i;
    for (i=0 ; i < clients.size() ; i++) {
      Client c=(Client)clients.get(i);
      if (c.idle_count >= max_idle_count)
         clients.removeElementAt(i);
      c.idle_count++;
    }

    debugClients("handleTimer");
  }
  
  private void handleRegisterSocketEvent(RegisterSocketEvent ev) {
    if (!ev.getChannel().getChannelID().equals(GROUP_NAME+" Channel")) {
      try { ev.go(); } catch (AppiaEventException ex) { ex.printStackTrace(); }
      return;
    }
      
    if (ev.error)
      throw new AppiaError("GossipServerSession: error registering socket for group");

    try {
      if (gossips == null) {
        gossips=new InetWithPort[] { new InetWithPort(ev.localHost,my_port) };
      } else {
        int i;
        for (i=0 ; i < gossips.length ; i++)
          if (gossips[i].host.equals(ev.localHost) && (gossips[i].port == my_port))
            break;
        if (i >= gossips.length) {
          InetWithPort[] aux=new InetWithPort[gossips.length+1];
          System.arraycopy(gossips,0,aux,0,gossips.length);
          aux[gossips.length]=new InetWithPort(ev.localHost,my_port);
          gossips=aux;
        }
      }

      if (debug != null) {
        for (int i=0 ; i < gossips.length ; i++) 
          debug("gossips["+i+"]: "+gossips[i]);
      } 
      
      Endpt[] view=new Endpt[] { new Endpt() };
      InetWithPort[] addrs=new InetWithPort[] { new InetWithPort(ev.localHost, ev.port) };
      ViewState vs=new ViewState("1",new Group(GROUP_NAME),new ViewID(0,view[0]),new ViewID[0], view, addrs);
    
      GroupInit ginit=new GroupInit(vs,view[0],null,gossips,ev.getChannel(), Direction.DOWN, this);
      ginit.go();
    } catch (AppiaEventException e) {
      e.printStackTrace();
    } catch (NullPointerException e) {
      e.printStackTrace();
    } catch (AppiaGroupException e) {
      e.printStackTrace();
    }
  }
  private void handleGossipGroupEvent(GossipGroupEvent ev) {
    int naddrs=((Message)ev.getMessage()).popInt();
    
    while (naddrs > 0) {
      aux.addr=InetWithPort.pop(ev.getMessage());
      int i;
      if ((i=clients.indexOf(aux)) >= 0) {
        Client c=(Client)clients.get(i);
        c.idle_count=0;
      } else {
        Client c=new Client(aux.addr);
        clients.add(c);
      }
      naddrs--;
    }
  }

  private void handleBlockOk(BlockOk ev) {
    groupChannel=null;
    
    try { ev.go(); } catch (AppiaEventException ex) { ex.printStackTrace(); }
  }
  
  private void handleView(View ev) {
    
    try { ev.go(); } catch (AppiaEventException ex) { ex.printStackTrace(); }
    
    if ((vs != null) && ls.am_coord && (ev.vs.getNewMembers(vs).length > 0)) {
      try {
        GossipGroupEvent e=new GossipGroupEvent(ev.getChannel(), Direction.DOWN, this, ev.vs.group, ev.vs.id);
        Message emsg=(Message)e.getMessage();
        
        int i;
        for (i=0 ; i < clients.size() ; i++) {
          InetWithPort.push((InetWithPort)((Client)clients.get(i)).addr, emsg);
        }
        emsg.pushInt(clients.size());
        
        e.go();
      } catch (AppiaEventException ex) {
        ex.printStackTrace();
      }
    }
    
    vs=ev.vs;
    ls=ev.ls;
    groupChannel=ev.getChannel();
  }
  
  private void sendAll(Client sender, SendableEvent event) {
    int i;
    for (i=0 ; i < clients.size() ; i++) {
      Client c=(Client)clients.get(i);
      if (!sender.equals(c)) {
        try {
          SendableEvent ev=(SendableEvent)event.cloneEvent();
          ev.setDir(Direction.DOWN);
          ev.setSource(this);
          ev.init();
          ev.dest=c.addr;
          ev.go();
          debug("sending to "+((InetWithPort)ev.dest).host.toString()+":"+((InetWithPort)ev.dest).port);
          debug("\t from "+((InetWithPort)ev.source).host.toString()+":"+((InetWithPort)ev.source).port);

        } catch (AppiaEventException ex) {
          ex.printStackTrace();
          debug("Unable to send to a client");
        } catch (CloneNotSupportedException ex) {
          ex.printStackTrace();
          debug("Unable to send to a client");
        }
      }
    }
  }
  
  private void sendGroup(Client sender) {
    if (groupChannel == null) {
      debug("Not sending to group because there isn't one.");
      return;
    }
    
    try {
      GossipGroupEvent ev=new GossipGroupEvent(groupChannel, Direction.DOWN, this, vs.group, vs.id);
      InetWithPort.push((InetWithPort)sender.addr, ev.getMessage());
      (ev.getMessage()).pushInt(1);
      ev.go();
    } catch (AppiaEventException ex) {
      ex.printStackTrace();
    }
  }

  // DEBUG
  private PrintStream debug=null;

  private void debug(String s) {
    if (debug != null)
       debug.println("appia:gossipServer:GossipServerSession: "+s);
  }

  private void debugClients(String s) {
    if (debug != null) {
      debug.println("appia:gossipServer:GossipServerSession: "+s);
      debug.print("clients={");
      int i;
      for (i=0 ; i < clients.size() ; i++) {
        Client c=(Client)clients.get(i);
        debug.print("[("+((InetWithPort)c.addr).host.toString());
        debug.print(":"+((InetWithPort)c.addr).port);
        debug.print("),"+c.idle_count);
        debug.print("] , ");
      }
      debug.println("}");
    }
  }

  private class Client {
    public Object addr;
    public int idle_count;

    public Client(Object addr) {
      this.addr=addr;
      idle_count=0;
    }

    public boolean equals(Object obj) {
      return (obj instanceof Client) && addr.equals(((Client)obj).addr);
    }
    
    public int hashCode() {
      return addr.hashCode();
    }
  }
}