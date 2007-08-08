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
 * Title:        Appia<p>
 * Description:  Protocol development and composition framework<p>
 * Copyright:    Copyright (c) Nuno Carvalho and Luis Rodrigues<p>
 * Company:      F.C.U.L.<p>
 * @author Nuno Carvalho and Luis Rodrigues
 * @version 1.0
 */

package net.sf.appia.demo.jmx;

import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import net.sf.appia.core.Channel;
import net.sf.appia.management.jmx.ChannelManagerMBean;
import net.sf.appia.protocols.group.primary.PrimaryViewSession;

import org.apache.log4j.Logger;

/**
 * This example shows one way to connect to a JSR 160 connector server in Appia.
 * To connect, the user needs the address of the Appia connector server. 
 * This address is generated by Appia, and must be known to this client.
 * When using JSR 160's RMI connector server, this information is often in form of a
 * JNDI name where the RMI stub has been registered; in this case the client needs
 * to know the host and port of the JNDI server and the JNDI path where the stub is
 * registered.
 *
 * @version 1.0
 * @author <a href="mailto:nunomrc@di.fc.ul.pt">Nuno Carvalho</a>
 */    
public class SetPrimaryProcess {
    
    private static Logger log = Logger.getLogger(SetPrimaryProcess.class);
    
    private SetPrimaryProcess(){}
    
    public static void main(String[] args) throws Exception {
        
        final String channelName =args[0];
        // The RMI server's host: this is actually ignored by JSR 160
        // since this information is stored in the RMI stub.
        final String serverHost = "host";
        // The host, port and path where the rmiregistry runs.
        final String namingHost = "localhost";
        final int namingPort = 1099;
        final String strURL = "service:jmx:rmi://" + serverHost + "/jndi/rmi://" + namingHost + ":" + namingPort + "/appia";
        final JMXServiceURL url = new JMXServiceURL(strURL);

        log.info("Connecting to URL: "+strURL);
        // Connect a JSR 160 JMXConnector to the server side
        final JMXConnector connector = JMXConnectorFactory.connect(url);        
        
        log.info("Retrieving MBean server connection...");
        // Retrieve an MBeanServerConnection that represent the MBeanServer the remote
        // connector server is bound to
        final MBeanServerConnection connection = connector.getMBeanServerConnection();
        
        log.info("Getting instance of MBean for channel: "+channelName);
        final ObjectName delegateName = ObjectName.getInstance(Channel.class.getName()+":"+"name="+channelName);
        final Object proxy = MBeanServerInvocationHandler.newProxyInstance(connection, delegateName, 
                        ChannelManagerMBean.class, true);
        final ChannelManagerMBean bean = (ChannelManagerMBean) proxy;
        final String sessionID = PrimaryViewSession.class.getName()+":"+channelName;
        
        log.info("Setting process to primary...");
        bean.setParameter("setPrimary","",sessionID);
    }
}
