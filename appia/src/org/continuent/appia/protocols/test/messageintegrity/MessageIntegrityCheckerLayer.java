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

package org.continuent.appia.protocols.test.messageintegrity;

import org.continuent.appia.core.Layer;
import org.continuent.appia.core.Session;
import org.continuent.appia.core.events.SendableEvent;

/**
 * This class defines a MessageIntegrityCheckerLayer
 * 
 * @author <a href="mailto:nunomrc@di.fc.ul.pt">Nuno Carvalho</a>
 * @version 1.0
 */
public class MessageIntegrityCheckerLayer extends Layer {

    /**
     * Creates a new MessageIntegrityCheckerLayer.
     */
    public MessageIntegrityCheckerLayer() {
        super();
        // Events that the protocol needs.
        evRequire=new Class[]{
                SendableEvent.class,
        };
        
        // Events that the protocol accepts.
        evAccept=new Class[]{
                evRequire[0],
        };
        
        // Events provided by this layer
        evProvide=new Class[]{
                evRequire[0],
        };
    }

    /**
     * Creates a new Session.
     * @see org.continuent.appia.core.Layer#createSession()
     */
    public Session createSession() {
        return new MessageIntegrityCheckerSession(this);
    }

}
