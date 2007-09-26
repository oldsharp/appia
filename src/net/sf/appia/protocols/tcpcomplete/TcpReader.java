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
 package net.sf.appia.protocols.tcpcomplete;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import net.sf.appia.core.*;
import net.sf.appia.core.events.SendableEvent;
import net.sf.appia.protocols.tcpcomplete.TcpUndeliveredEvent;
import net.sf.appia.protocols.utils.ParseUtils;

import org.apache.log4j.Logger;

/**
 * @author pedrofrv
 *
 */
public class TcpReader implements Runnable {
	
	private static Logger log = Logger.getLogger(TcpReader.class);
	
	private Socket s;
	private InputStream is=null;
	private TcpCompleteSession parentSession;
	private int remotePort ;
	private int originalPort; //port of the accept socket
	private Channel channel;
	private Benchmark bench;
	
	private int inactiveCounter=0;
	
	private boolean running;
	
	public TcpReader(Socket socket,TcpCompleteSession session, int originalPort, int remotePort, Channel channel){
		super();
		s = socket;
		try {
			socket.setSoTimeout(session.param_SOTIMEOUT);
		} catch (SocketException e) {
			e.printStackTrace();
		}
		parentSession = session;
		this.originalPort = originalPort;
		this.remotePort = remotePort;
		this.channel = channel;
		setRunning(true);
	}


	public void run(){
		SendableEvent event=null;
		
		try {
			is = s.getInputStream();
		} catch (IOException ex) {
			InetSocketAddress iwp = new InetSocketAddress(s.getInetAddress(),remotePort);

			if(TcpCompleteConfig.debugOn){
				debug("message reception from "+iwp+" failed. Sending Undelivered event back. Exception:");
                ex.printStackTrace();
            }            
	
			try {
				TcpUndeliveredEvent undelivered = new TcpUndeliveredEvent(iwp);    
				undelivered.asyncGo(channel,Direction.UP);
				parentSession.removeSocket(iwp);
			} catch (AppiaEventException exception) {
				log.debug("Could not insert event: "+exception);
			}					
			return;						
		}
//		bench = Benchmark.getInstance(channel.getTimeProvider());	
		while(isRunning()){
// if (bench != null) bench.startBench("receiving event");
				try {
// if (bench != null) bench.startBench("receive and format");
					event = receiveAndFormat();
					clearInactiveCounter();
// if (bench != null) bench.stopBench("receive and format");										
					
					if(event != null){
						if(TcpCompleteConfig.debugOn)	
							debug("received an event sending it to the appia stack: "+event+" Channel: "+event.getChannel());
						event.asyncGo(event.getChannel(), Direction.UP);						
					}
				} catch (AppiaEventException ex) {
					log.debug("Could not insert event: "+ex);
		        } catch(SocketTimeoutException ste){
				} catch (IOException ex) {
					//send_undelivered :(
					try {
						InetSocketAddress iwp = new InetSocketAddress(s.getInetAddress(),remotePort);
		
						if(TcpCompleteConfig.debugOn)	
							debug("Message reception from "+iwp+" failed. Send undelivered event up.");
							
						TcpUndeliveredEvent undelivered = new TcpUndeliveredEvent(iwp);    
						undelivered.asyncGo(channel,Direction.UP);						
						parentSession.removeSocket(iwp);
						return;						
					} catch (AppiaEventException e) {
						e.printStackTrace();
					}
				}
				//if (bench != null) bench.stopBench("receiving event");
		}
	}
	
	
	private int receive_n(byte[] b,int length) throws IOException {
		//if (bench != null) bench.startBench("receive_n");
		int n=0,i=0,x=0;
		while(n!=length && i!=-1) {
    		i=is.read(b,n,length-n);
			n+=i;
			x++;
		}
		//		if (bench != null) bench.stopBench("receive_n");
		//if (bench != null) bench.indepBench("iterations_on read",x);
		if(i==-1)
	    		throw new IOException();

		return n;
        }
        
    /* Event deserialization. Returns the event or null if something
     * happened.
     */
	private SendableEvent receiveAndFormat() throws IOException {
		SendableEvent e=null;
		try {
			byte bTotal[] = new byte[4];
			int total ;
			//if (bench != null) bench.startBench("read msg size");
			receive_n(bTotal,4);
			//if (bench != null) bench.stopBench("read msg size");			
			total =ParseUtils.byteArrayToInt(bTotal,0);
			
			byte data[] = new byte[total];
			
			if (bench != null) bench.startBench("read msg");
			receive_n(data,total);
			if (bench != null) bench.stopBench("read msg");

			if (bench != null) bench.startBench("readandformat");			
			int curPos = 0;
			
			/* Extract event class name */
			//size of class name
			int sLength=ParseUtils.byteArrayToInt(data, curPos);
			//the class name
		    String className=new String(data,curPos+4,sLength);

		        //updating curPos
		        curPos += sLength + 4;

		        /* Create event */

		        Class c = Class.forName(className);
		
		        e=(SendableEvent)c.newInstance();

		        /* Extract channel name and put event in it*/
		        sLength = ParseUtils.byteArrayToInt(data, curPos);
		        String channelName=new String(data,curPos+4,sLength);

			Channel msgChannel = parentSession.getChannel(channelName);
			
			if(msgChannel == null)
				return null;
			
			e.setChannel(msgChannel);
		        curPos += sLength + 4;

		        /* Extract the addresses and put them on the event */

		        //msg's source
		        e.source=new InetSocketAddress(s.getInetAddress(),remotePort);
			
			e.dest=new InetSocketAddress(s.getLocalAddress(),originalPort);
			
			e.getMessage().setByteArray(data,curPos,total-curPos);

			if (bench != null) bench.stopBench("readandformat");			
			
        } catch(IOException ste){
        	throw ste;
		}
		catch(Exception ex) {
			
                  if (TcpCompleteConfig.debugOn) {
                    ex.printStackTrace();
                    System.err.println("Exception catched while processing message from "+s.getInetAddress().getHostName()+":"+remotePort+". Continuing operation.");
                  }
                  throw new IOException();
		}

		return e;
    	}
	
	public synchronized void setRunning(boolean r){
		running = r;
	}
	
	private synchronized boolean isRunning(){
		return running;
	}
    	
	private void debug(String msg){
		System.out.println("[TCPREADER]:: "+msg);
	}


	public Socket getSocket() {
		return s;
	}


	public synchronized int getInactiveCounter() {
		return inactiveCounter;
	}


	public synchronized int sumInactiveCounter() {
		return (++this.inactiveCounter);
	}

	public synchronized void clearInactiveCounter() {
		this.inactiveCounter = 0;
	}

}
