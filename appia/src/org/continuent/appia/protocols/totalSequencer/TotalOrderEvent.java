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
 package org.continuent.appia.protocols.totalSequencer;

import org.continuent.appia.core.*;
import org.continuent.appia.protocols.group.*;
import org.continuent.appia.protocols.group.events.*;



/**
 * Event the carries the order given by the sequencer.
 */
public class TotalOrderEvent extends GroupSendableEvent{

    /**
     * Default constructor.
     *
     */
	public TotalOrderEvent(){
        super();
    }
    
    /**
     * Constructor of this class.
     * @param channel the channel
     * @param dir the direction
     * @param source the source session
     * @param group the group
     * @param view_id the view ID
     * @throws AppiaEventException
     */
	public TotalOrderEvent(
                              Channel channel, int dir, Session source,
                              Group group, ViewID view_id)
        throws AppiaEventException {
            super(channel,dir,source,group,view_id);
        }
}
