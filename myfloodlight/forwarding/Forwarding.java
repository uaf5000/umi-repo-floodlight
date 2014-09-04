/**
*    Copyright 2011, Big Switch Networks, Inc. 
*    Originally created by David Erickson, Stanford University
* 
*    Licensed under the Apache License, Version 2.0 (the "License"); you may
*    not use this file except in compliance with the License. You may obtain
*    a copy of the License at
*
*         http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
*    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
*    License for the specific language governing permissions and limitations
*    under the License.
**/

package net.floodlightcontroller.forwarding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.core.annotations.LogMessageCategory;
import net.floodlightcontroller.core.annotations.LogMessageDoc;
import net.floodlightcontroller.core.annotations.LogMessageDocs;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.ITopologyService;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.fiu.openflowresearch.FIUPortStatistics;

@LogMessageCategory("Flow Programming")
public class Forwarding extends ForwardingBase implements IFloodlightModule, SwitchDPIDList {
    protected static Logger log = LoggerFactory.getLogger(Forwarding.class);
    protected FIUPortStatistics portStats;
    List<OFPortStatisticsReply> tempStats;

    @Override
    @LogMessageDoc(level="ERROR",
                   message="Unexpected decision made for this packet-in={}",
                   explanation="An unsupported PacketIn decision has been " +
                   		"passed to the flow programming component",
                   recommendation=LogMessageDoc.REPORT_CONTROLLER_BUG)
    public Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, 
                                          FloodlightContext cntx) {
        Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, 
                                   IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
        
        // If a decision has been made we obey it
        // otherwise we just forward
        if (decision != null) {
            if (log.isTraceEnabled()) {
                log.trace("Forwaring decision={} was made for PacketIn={}",
                        decision.getRoutingAction().toString(),
                        pi);
            }
            
            switch(decision.getRoutingAction()) {
                case NONE:
                    // don't do anything
                    return Command.CONTINUE;
                case FORWARD_OR_FLOOD:
                case FORWARD:
                    doForwardFlow(sw, pi, cntx, false);
                    return Command.CONTINUE;
                case MULTICAST:
                    // treat as broadcast
                    doFlood(sw, pi, cntx);
                    return Command.CONTINUE;
                case DROP:
                    doDropFlow(sw, pi, decision, cntx);
                    return Command.CONTINUE;
                default:
                    log.error("Unexpected decision made for this packet-in={}",
                            pi, decision.getRoutingAction());
                    return Command.CONTINUE;
            }
        } else {
            if (log.isTraceEnabled()) {
                log.trace("No decision was made for PacketIn={}, forwarding",
                        pi);
            }
            
            if (eth.isBroadcast() || eth.isMulticast()) {
                // For now we treat multicast as broadcast
                doFlood(sw, pi, cntx);
            } else {
                doForwardFlow(sw, pi, cntx, false);
            }
        }
        
        return Command.CONTINUE;
    }
    
    @LogMessageDoc(level="ERROR",
            message="Failure writing drop flow mod",
            explanation="An I/O error occured while trying to write a " +
            		"drop flow mod to a switch",
            recommendation=LogMessageDoc.CHECK_SWITCH)
    protected void doDropFlow(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx) {
        // initialize match structure and populate it using the packet
        OFMatch match = new OFMatch();
        match.loadFromPacket(pi.getPacketData(), pi.getInPort());
        if (decision.getWildcards() != null) {
            match.setWildcards(decision.getWildcards());
        }
        
        // Create flow-mod based on packet-in and src-switch
        OFFlowMod fm =
                (OFFlowMod) floodlightProvider.getOFMessageFactory()
                                              .getMessage(OFType.FLOW_MOD);
        List<OFAction> actions = new ArrayList<OFAction>(); // Set no action to
                                                            // drop
        long cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);
        
        fm.setCookie(cookie)
          .setHardTimeout((short) 0)
          .setIdleTimeout((short) 5)
          .setBufferId(OFPacketOut.BUFFER_ID_NONE)
          .setMatch(match)
          .setActions(actions)
          .setLengthU(OFFlowMod.MINIMUM_LENGTH); // +OFActionOutput.MINIMUM_LENGTH);

        try {
            if (log.isDebugEnabled()) {
                log.debug("write drop flow-mod sw={} match={} flow-mod={}",
                          new Object[] { sw, match, fm });
            }
            messageDamper.write(sw, fm, cntx);
        } catch (IOException e) {
            log.error("Failure writing drop flow mod", e);
        }
    }
    
    protected void doForwardFlow(IOFSwitch sw, OFPacketIn pi, 
                                 FloodlightContext cntx,
                                 boolean requestFlowRemovedNotifn) {
    	
    	System.out.println("+++++++++++Entering doforwardflow++++++++++++++");
        OFMatch match = new OFMatch();
        match.loadFromPacket(pi.getPacketData(), pi.getInPort());
        //tempStats =
    		 //  portStats.getSwitchStatistics(sw.getId(), OFStatisticsType.PORT);
     //  System.out.println("---------------------------Creating Port Stats-------------");
      // System.out.println(tempStats);

        // Check if we have the location of the destination
        IDevice dstDevice = 
                IDeviceService.fcStore.
                    get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
        
        if (dstDevice != null) {
            IDevice srcDevice =
                    IDeviceService.fcStore.
                        get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);
            Long srcIsland = topology.getL2DomainId(sw.getId());
            
            
            if (srcDevice == null) {
                log.debug("No device entry found for source device");
                return;
            }
            if (srcIsland == null) {
                log.debug("No openflow island found for source {}/{}", 
                          sw.getStringId(), pi.getInPort());
                return;
            }

            // Validate that we have a destination known on the same island
            // Validate that the source and destination are not on the same switchport
            boolean on_same_island = false;
            boolean on_same_if = false;
            for (SwitchPort dstDap : dstDevice.getAttachmentPoints()) {
                long dstSwDpid = dstDap.getSwitchDPID();
                Long dstIsland = topology.getL2DomainId(dstSwDpid);
                if ((dstIsland != null) && dstIsland.equals(srcIsland)) {
                    on_same_island = true;
                    System.out.println("+++++++Source and destination are on the same island");
                    if ((sw.getId() == dstSwDpid) &&
                        (pi.getInPort() == dstDap.getPort())) {
                        on_same_if = true;
                    }
                    break;
                }
            }
            
            if (!on_same_island) {
                // Flood since we don't know the dst device
                if (log.isTraceEnabled()) {
                    log.trace("No first hop island found for destination " + 
                              "device {}, Action = flooding", dstDevice);
                }
                System.out.println("Going to flood+++++++++++++");
                doFlood(sw, pi, cntx);
                return;
            }            
            
            if (on_same_if) {
                if (log.isTraceEnabled()) {
                    log.trace("Both source and destination are on the same " + 
                              "switch/port {}/{}, Action = NOP", 
                              sw.toString(), pi.getInPort());
                }
                return;
            }

            // Install all the routes where both src and dst have attachment
            // points.  Since the lists are stored in sorted order we can 
            // traverse the attachment points in O(m+n) time
            SwitchPort[] srcDaps = srcDevice.getAttachmentPoints();
            Arrays.sort(srcDaps, clusterIdComparator);
            SwitchPort[] dstDaps = dstDevice.getAttachmentPoints();
            Arrays.sort(dstDaps, clusterIdComparator);

            int iSrcDaps = 0, iDstDaps = 0;

            while ((iSrcDaps < srcDaps.length) && (iDstDaps < dstDaps.length)) {
                SwitchPort srcDap = srcDaps[iSrcDaps];
                SwitchPort dstDap = dstDaps[iDstDaps];
                Long srcCluster = 
                        topology.getL2DomainId(srcDap.getSwitchDPID());
                Long dstCluster = 
                        topology.getL2DomainId(dstDap.getSwitchDPID());
                
                /*************** Determines Path from A to B************
                 * 
                 */

                int srcVsDest = srcCluster.compareTo(dstCluster);
                if (srcVsDest == 0) {
                    if (!srcDap.equals(dstDap) && 
                        (srcCluster != null) && 
                        (dstCluster != null)) 
                    		{
                        Route route = 
                                routingEngine.getRoute(srcDap.getSwitchDPID(),
                                                       (short)srcDap.getPort(),
                                                       dstDap.getSwitchDPID(),
                                                       (short)dstDap.getPort());
                        System.out.print("We have route, so pushing+++++++++");
                        if (route != null) {
                            if (log.isTraceEnabled()) {
                                log.trace("pushRoute match={} route={} " + 
                                          "destination={}:{}",
                                          new Object[] {match, route, 
                                                        dstDap.getSwitchDPID(),
                                                        dstDap.getPort()});
                                
                            }
                            
                            long cookie = 
                                    AppCookie.makeCookie(FORWARDING_APP_ID, 0);
                            
                         // if there is prior routing decision use wildcard                                                     
                            Integer wildcard_hints = null;
                            IRoutingDecision decision = null;
                            if (cntx != null) {
                                decision = IRoutingDecision.rtStore
                                        .get(cntx,
                                                IRoutingDecision.CONTEXT_DECISION);
                            }
                            if (decision != null) {
                                wildcard_hints = decision.getWildcards();
                            } else {
                            	// L2 only wildcard if there is no prior route decision
                                wildcard_hints = ((Integer) sw
                                        .getAttribute(IOFSwitch.PROP_FASTWILDCARDS))
                                        .intValue()
                                        & ~OFMatch.OFPFW_IN_PORT
                                        & ~OFMatch.OFPFW_DL_VLAN
                                        & ~OFMatch.OFPFW_DL_SRC
                                        & ~OFMatch.OFPFW_DL_DST
                                        & ~OFMatch.OFPFW_NW_SRC_MASK
                                        & ~OFMatch.OFPFW_NW_DST_MASK;
                            }
                            System.out.println("+++++++++++Entering pushr++++++++++++++");
                            pushRoute(route, match, wildcard_hints, pi, sw.getId(), cookie, 
                                      cntx, requestFlowRemovedNotifn, false,
                                      OFFlowMod.OFPFC_ADD);
                        }
                    }
                    iSrcDaps++;
                    iDstDaps++;
                } else if (srcVsDest < 0) {
                    iSrcDaps++;
                } else {
                    iDstDaps++;
                }
            }
        } else {
            // Flood since we don't know the dst device
            doFlood(sw, pi, cntx);
        }
    }

    
    /***************************************FIU CODE STARTS HERE**************************
    
    /**
     * Creates a OFPacketOut with the OFPacketIn data that is flooded on all ports unless 
     * the port is blocked, in which case the packet will be dropped.
     * @param sw The switch that receives the OFPacketIn
     * @param pi The OFPacketIn that came to the switch
     * @param cntx The FloodlightContext associated with this OFPacketIn
     */
    @LogMessageDoc(level="ERROR",
                   message="Failure writing PacketOut " +
                   		"switch={switch} packet-in={packet-in} " +
                   		"packet-out={packet-out}",
                   explanation="An I/O error occured while writing a packet " +
                   		"out message to the switch",
                   recommendation=LogMessageDoc.CHECK_SWITCH)
    protected void doFlood(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
        if (topology.isIncomingBroadcastAllowed(sw.getId(),
                                                pi.getInPort()) == false) {
            if (log.isTraceEnabled()) {
                log.trace("doFlood, drop broadcast packet, pi={}, " + 
                          "from a blocked port, srcSwitch=[{},{}], linkInfo={}",
                          new Object[] {pi, sw.getId(),pi.getInPort()});
            }
            return;
        }
        
        OFMatch match = new OFMatch();
        match.loadFromPacket(pi.getPacketData(), pi.getInPort());
       
        // Create flow-mod based on packet-in and src-switch
        OFFlowMod fm =
                (OFFlowMod) floodlightProvider.getOFMessageFactory()
                                              .getMessage(OFType.FLOW_MOD);
        List<OFAction> actions = new ArrayList<OFAction>(); // Set no action to
        if(sw.getId()== FIU_101 && pi.getInPort() == 26){		//26 --> {27,28}
        	actions.add(new OFActionOutput((short)27,(short)0xFFFF));
        	actions.add(new OFActionOutput((short)28,(short)0xFFFF));
               }        
        else if(sw.getId()==FIU_101 && pi.getInPort() == 28){		//28 --> {27,26}
        	actions.add(new OFActionOutput((short)27,(short)0xFFFF));
        	actions.add(new OFActionOutput((short)26,(short)0xFFFF));
        }
        else if(sw.getId()==FIU_101 && pi.getInPort() == 27){		//27 -- > {26,28}
        	actions.add(new OFActionOutput((short)26,(short)0xFFFF));
        	actions.add(new OFActionOutput((short)28,(short)0xFFFF));
        }
        else if(sw.getId()==FIU_101 && pi.getInPort() == 25){		//27 -- > {26,28}
        	actions.add(new OFActionOutput((short)26,(short)0xFFFF));
        	actions.add(new OFActionOutput((short)28,(short)0xFFFF));
          }
        //102
        else if(sw.getId()== FIU_102 && pi.getInPort() == 30){		//30 --> {31,32}
            actions.add(new OFActionOutput((short)31,(short)0xFFFF));
            actions.add(new OFActionOutput((short)32,(short)0xFFFF));
                 }
        else if(sw.getId()==FIU_102 && pi.getInPort() == 32){		//32 --> {30,31}
            actions.add(new OFActionOutput((short)30,(short)0xFFFF));
            actions.add(new OFActionOutput((short)31,(short)0xFFFF));
          }
        else if(sw.getId()==FIU_102 && pi.getInPort() == 29){		//29 -- > {30,32}
            actions.add(new OFActionOutput((short)30,(short)0xFFFF));
            actions.add(new OFActionOutput((short)32,(short)0xFFFF));
          }
        else if(sw.getId()==FIU_102 && pi.getInPort() == 31){		//31 -- > {30,32}
            actions.add(new OFActionOutput((short)30,(short)0xFFFF));
            actions.add(new OFActionOutput((short)32,(short)0xFFFF));
            }
          //103
        else if(sw.getId()== FIU_103 && pi.getInPort() == 34){		//34 --> {36,35}
              actions.add(new OFActionOutput((short)36,(short)0xFFFF));
              actions.add(new OFActionOutput((short)35,(short)0xFFFF));
                   }
        else if(sw.getId()==FIU_103 && pi.getInPort() == 36){		//36 --> {34,35}
              actions.add(new OFActionOutput((short)34,(short)0xFFFF));
              actions.add(new OFActionOutput((short)35,(short)0xFFFF));
            }
        else if(sw.getId()==FIU_103 && pi.getInPort() == 35){		//35 -- > {34,36}
              actions.add(new OFActionOutput((short)34,(short)0xFFFF));
              actions.add(new OFActionOutput((short)36,(short)0xFFFF));
            }
        else if(sw.getId()==FIU_103 && pi.getInPort() == 33){		//33 -- > {34,36}
              actions.add(new OFActionOutput((short)34,(short)0xFFFF));
              actions.add(new OFActionOutput((short)36,(short)0xFFFF));
              }
            //104
        else if(sw.getId()== FIU_104 && pi.getInPort() == 38){		//38 --> {39,40}
                actions.add(new OFActionOutput((short)39,(short)0xFFFF));
                actions.add(new OFActionOutput((short)40,(short)0xFFFF));
                     }
        else if(sw.getId()==FIU_104 && pi.getInPort() == 40){		//40 --> {39,38}
                actions.add(new OFActionOutput((short)39,(short)0xFFFF));
                actions.add(new OFActionOutput((short)38,(short)0xFFFF));
              }
        else if(sw.getId()==FIU_104 && pi.getInPort() == 37){		//37 -- > {38,40}
                actions.add(new OFActionOutput((short)38,(short)0xFFFF));
                actions.add(new OFActionOutput((short)40,(short)0xFFFF));
              }
        else if(sw.getId()==FIU_104 && pi.getInPort() == 39){		//39 -- > {38,40}
                actions.add(new OFActionOutput((short)38,(short)0xFFFF));
                actions.add(new OFActionOutput((short)40,(short)0xFFFF));
                }
              //105
        else if(sw.getId()== FIU_105 && pi.getInPort() == 26){		//26 --> {27,28}
                  actions.add(new OFActionOutput((short)27,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)28,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_105 && pi.getInPort() == 28){		//28 --> {27,26}
                  actions.add(new OFActionOutput((short)27,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)26,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_105 && pi.getInPort() == 27){		//27 -- > {26,28}
                  actions.add(new OFActionOutput((short)26,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)28,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_105 && pi.getInPort() == 25){		//25 -- > {26,28}
                  actions.add(new OFActionOutput((short)26,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)28,(short)0xFFFF));
                  }
              //106
        else if(sw.getId()== FIU_106 && pi.getInPort() == 30){		//30 --> {32,31}
                  actions.add(new OFActionOutput((short)32,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)31,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_106 && pi.getInPort() == 32){		//32 --> {30,31}
                  actions.add(new OFActionOutput((short)30,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)31,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_106 && pi.getInPort() == 29){		//29 -- > {30,32}
                  actions.add(new OFActionOutput((short)30,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)32,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_106 && pi.getInPort() == 31){		//31 -- > {30,32}
                  actions.add(new OFActionOutput((short)30,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)32,(short)0xFFFF));
                  }
              //107
        else if(sw.getId()== FIU_107 && pi.getInPort() == 34){		//34 --> {35,36}
                  actions.add(new OFActionOutput((short)35,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)36,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_107 && pi.getInPort() == 36){		//36 --> {35,34}
                  actions.add(new OFActionOutput((short)35,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)34,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_107 && pi.getInPort() == 35){		//35 -- > {34,36}
                  actions.add(new OFActionOutput((short)34,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)36,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_107 && pi.getInPort() == 33){		//33 -- > {34,36}
                  actions.add(new OFActionOutput((short)34,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)36,(short)0xFFFF));
                  }
              //108
        else if(sw.getId()== FIU_108 && pi.getInPort() == 38){		//38 --> {40,39}
                  actions.add(new OFActionOutput((short)40,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)39,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_108 && pi.getInPort() == 40){		//40 --> {38,39}
                  actions.add(new OFActionOutput((short)38,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)39,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_108 && pi.getInPort() == 39){		//39 -- > {38,40}
                  actions.add(new OFActionOutput((short)38,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)40,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_108 && pi.getInPort() == 37){		//37 -- > {38,40}
                  actions.add(new OFActionOutput((short)38,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)40,(short)0xFFFF));
                  }
              
              /**********************AGGR LAYER*********************************
               * 
               */
              
             //201
        
        else if(sw.getId()== FIU_201 && pi.getInPort() == 43){		//43 --> {42,41}
                  actions.add(new OFActionOutput((short)42,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_201 && pi.getInPort() == 42){		//42 --> {43,41}
                  actions.add(new OFActionOutput((short)43,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_201 && pi.getInPort() == 44){		//44 -- > {43,41}
                  actions.add(new OFActionOutput((short)43,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                }
              //202
        else if(sw.getId()== FIU_202 && pi.getInPort() == 45){		//45 --> {46,47}
                  actions.add(new OFActionOutput((short)46,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_202 && pi.getInPort() == 46){		//46 --> {47,45}
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)45,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_202 && pi.getInPort() == 48){		//48 -- > {47,45}
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)45,(short)0xFFFF));
                }
              //203
        else if(sw.getId()== FIU_203 && pi.getInPort() == 51){		//51 --> {52,49}
                  actions.add(new OFActionOutput((short)52,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 20333333333333+++++++++++++");
                       }
        else if(sw.getId()==FIU_203 && pi.getInPort() == 50){		//50 --> {51,49}
                  actions.add(new OFActionOutput((short)51,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 20333333333333+++++++++++++");
                }
        else if(sw.getId()==FIU_203 && pi.getInPort() == 52){		//52 -- > {51,49}
                  actions.add(new OFActionOutput((short)51,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 20333333333333+++++++++++++");
        }
              //204
        else if(sw.getId()== FIU_204 && pi.getInPort() == 53){		//53 --> {56,55}
                  actions.add(new OFActionOutput((short)56,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_204 && pi.getInPort() == 54){		//54 --> {55,53}
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)53,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_204 && pi.getInPort() == 56){		//56 -- > {55,53}
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)53,(short)0xFFFF));
                }
              //205
        else if(sw.getId()== FIU_205 && pi.getInPort() == 43){		//43 --> {44,41}
                  actions.add(new OFActionOutput((short)44,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_205 && pi.getInPort() == 44){		//44 --> {43,41}
                  actions.add(new OFActionOutput((short)43,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_205 && pi.getInPort() == 42){		//42 -- > {43,41}
                  actions.add(new OFActionOutput((short)43,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                }
              //206
        else if(sw.getId()== FIU_206 && pi.getInPort() == 45){		//45 --> {47,48}
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)48,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_206 && pi.getInPort() == 46){		//46 --> {47,45}
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)45,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_206 && pi.getInPort() == 48){		//48 -- > {47,45}
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)45,(short)0xFFFF));
                }
              //207
        else if(sw.getId()== FIU_207 && pi.getInPort() == 51){		//51 --> {50,49}
                  actions.add(new OFActionOutput((short)50,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_207 && pi.getInPort() == 52){		//52 --> {51,49}
                  actions.add(new OFActionOutput((short)51,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_207 && pi.getInPort() == 50){		//50 -- > {51,49}
                  actions.add(new OFActionOutput((short)51,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                }
              //208
        else if(sw.getId()== FIU_202 && pi.getInPort() == 53){		//53 --> {55,56}
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)56,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_202 && pi.getInPort() == 54){		//54 --> {55,53}
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)53,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_202 && pi.getInPort() == 56){		//56 -- > {55,53}
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)53,(short)0xFFFF));
                }
              
              /**********************CORE LAYER*********************************
               * 
               */
              
             //301
        
        else if(sw.getId()== FIU_301 && pi.getInPort() == 58){		//58 --> {57,60,59}
                  actions.add(new OFActionOutput((short)57,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)60,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)59,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 30111111111+++++++++++++\n");
                       }
        else if(sw.getId()==FIU_301 && pi.getInPort() == 57){		//57 --> {58,60,59}
                  actions.add(new OFActionOutput((short)58,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)60,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)59,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 3011111111111111+++++++++++++\n");
                }
              
            //302
      
        else if(sw.getId()== FIU_302 && pi.getInPort() == 64){		//64 --> {62,61,63}
                  actions.add(new OFActionOutput((short)62,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)61,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)63,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 3022222222222222222222222+++++++++++++\n");
                       }
        else if(sw.getId()==FIU_302 && pi.getInPort() == 63){		//63 --> {62,64,61}
                  actions.add(new OFActionOutput((short)62,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)64,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)61,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 3022222222222222222222222+++++++++++++\n");
                }
              
            //303
        else if(sw.getId()== FIU_303 && pi.getInPort() == 59){		//59 --> {57,58,60}
                  actions.add(new OFActionOutput((short)57,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)58,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)60,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 30333333333333+++++++++++++\n");
                       }
        else if(sw.getId()==FIU_303 && pi.getInPort() == 60){		//60 --> {57,58,59}
                  actions.add(new OFActionOutput((short)57,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)58,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)59,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 30333333333333+++++++++++++\n");
                }
            //304
        else if(sw.getId()== FIU_304 && pi.getInPort() == 63){		//63 --> {62,61,64}
                  actions.add(new OFActionOutput((short)62,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)61,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)64,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 30444444444444+++++++++++++\n");
                       }
        else if(sw.getId()==FIU_304 && pi.getInPort() == 62){		//62 --> {63,61,64}
                  actions.add(new OFActionOutput((short)63,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)61,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)64,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 304444444444444444+++++++++++++\n");
                }                                                   // drop
        long cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);
        
        fm.setCookie(cookie)
          .setHardTimeout((short) 0)
          .setIdleTimeout((short) 0)
          .setBufferId(pi.getBufferId())
          .setMatch(match)
          .setActions(actions)
          .setLengthU(OFFlowMod.MINIMUM_LENGTH+OFActionOutput.MINIMUM_LENGTH*actions.size()); // +OFActionOutput.MINIMUM_LENGTH);

        try {
            if (log.isDebugEnabled()) {
                log.debug("write FIU flow-mod sw={} match={} flow-mod={}",
                          new Object[] { sw, match, fm });
            }
            messageDamper.write(sw, fm, cntx);
        } catch (IOException e) {
            log.error("Failure writing FIU flow mod", e);
        }
  
        // Set Action to flood
      /*  OFPacketOut po = 
            (OFPacketOut) floodlightProvider.getOFMessageFactory().getMessage(OFType.PACKET_OUT);
        List<OFAction> actions = new ArrayList<OFAction>();
        

         
       
        if (sw.hasAttribute(IOFSwitch.PROP_SUPPORTS_OFPP_FLOOD)) {
            actions.add(new OFActionOutput(OFPort.OFPP_FLOOD.getValue(), 
                                           (short)0xFFFF));
        } else {
            actions.add(new OFActionOutput(OFPort.OFPP_ALL.getValue(), 
                                           (short)0xFFFF));
        }
        
        //System.out.print("\n++++Switch ID"+sw.getId()+" In Port:"+pi.getInPort());

       /***************************************TOR LAYER**************************
        * 
        */
        //101
        
        /*
        if(sw.getId()== FIU_101 && pi.getInPort() == 26){		//26 --> {27,28}
        	actions.add(new OFActionOutput((short)27,(short)0xFFFF));
        	actions.add(new OFActionOutput((short)28,(short)0xFFFF));
               }
        
        else if(sw.getId()==FIU_101 && pi.getInPort() == 28){		//28 --> {27,26}
        	actions.add(new OFActionOutput((short)27,(short)0xFFFF));
        	actions.add(new OFActionOutput((short)26,(short)0xFFFF));
        }
        else if(sw.getId()==FIU_101 && pi.getInPort() == 27){		//27 -- > {26,28}
        	actions.add(new OFActionOutput((short)26,(short)0xFFFF));
        	actions.add(new OFActionOutput((short)28,(short)0xFFFF));
        }
        else if(sw.getId()==FIU_101 && pi.getInPort() == 25){		//27 -- > {26,28}
        	actions.add(new OFActionOutput((short)26,(short)0xFFFF));
        	actions.add(new OFActionOutput((short)28,(short)0xFFFF));
          }
        //102
        else if(sw.getId()== FIU_102 && pi.getInPort() == 30){		//30 --> {31,32}
            actions.add(new OFActionOutput((short)31,(short)0xFFFF));
            actions.add(new OFActionOutput((short)32,(short)0xFFFF));
                 }
        else if(sw.getId()==FIU_102 && pi.getInPort() == 32){		//32 --> {30,31}
            actions.add(new OFActionOutput((short)30,(short)0xFFFF));
            actions.add(new OFActionOutput((short)31,(short)0xFFFF));
          }
        else if(sw.getId()==FIU_102 && pi.getInPort() == 29){		//29 -- > {30,32}
            actions.add(new OFActionOutput((short)30,(short)0xFFFF));
            actions.add(new OFActionOutput((short)32,(short)0xFFFF));
          }
        else if(sw.getId()==FIU_102 && pi.getInPort() == 31){		//31 -- > {30,32}
            actions.add(new OFActionOutput((short)30,(short)0xFFFF));
            actions.add(new OFActionOutput((short)32,(short)0xFFFF));
            }
          //103
        else if(sw.getId()== FIU_103 && pi.getInPort() == 34){		//34 --> {36,35}
              actions.add(new OFActionOutput((short)36,(short)0xFFFF));
              actions.add(new OFActionOutput((short)35,(short)0xFFFF));
                   }
        else if(sw.getId()==FIU_103 && pi.getInPort() == 36){		//36 --> {34,35}
              actions.add(new OFActionOutput((short)34,(short)0xFFFF));
              actions.add(new OFActionOutput((short)35,(short)0xFFFF));
            }
        else if(sw.getId()==FIU_103 && pi.getInPort() == 35){		//35 -- > {34,36}
              actions.add(new OFActionOutput((short)34,(short)0xFFFF));
              actions.add(new OFActionOutput((short)36,(short)0xFFFF));
            }
        else if(sw.getId()==FIU_103 && pi.getInPort() == 33){		//33 -- > {34,36}
              actions.add(new OFActionOutput((short)34,(short)0xFFFF));
              actions.add(new OFActionOutput((short)36,(short)0xFFFF));
              }
            //104
        else if(sw.getId()== FIU_104 && pi.getInPort() == 38){		//38 --> {39,40}
                actions.add(new OFActionOutput((short)39,(short)0xFFFF));
                actions.add(new OFActionOutput((short)40,(short)0xFFFF));
                     }
        else if(sw.getId()==FIU_104 && pi.getInPort() == 40){		//40 --> {39,38}
                actions.add(new OFActionOutput((short)39,(short)0xFFFF));
                actions.add(new OFActionOutput((short)38,(short)0xFFFF));
              }
        else if(sw.getId()==FIU_104 && pi.getInPort() == 37){		//37 -- > {38,40}
                actions.add(new OFActionOutput((short)38,(short)0xFFFF));
                actions.add(new OFActionOutput((short)40,(short)0xFFFF));
              }
        else if(sw.getId()==FIU_104 && pi.getInPort() == 39){		//39 -- > {38,40}
                actions.add(new OFActionOutput((short)38,(short)0xFFFF));
                actions.add(new OFActionOutput((short)40,(short)0xFFFF));
                }
              //105
        else if(sw.getId()== FIU_105 && pi.getInPort() == 26){		//26 --> {27,28}
                  actions.add(new OFActionOutput((short)27,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)28,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_105 && pi.getInPort() == 28){		//28 --> {27,26}
                  actions.add(new OFActionOutput((short)27,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)26,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_105 && pi.getInPort() == 27){		//27 -- > {26,28}
                  actions.add(new OFActionOutput((short)26,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)28,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_105 && pi.getInPort() == 25){		//25 -- > {26,28}
                  actions.add(new OFActionOutput((short)26,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)28,(short)0xFFFF));
                  }
              //106
        else if(sw.getId()== FIU_106 && pi.getInPort() == 30){		//30 --> {32,31}
                  actions.add(new OFActionOutput((short)32,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)31,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_106 && pi.getInPort() == 32){		//32 --> {30,31}
                  actions.add(new OFActionOutput((short)30,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)31,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_106 && pi.getInPort() == 29){		//29 -- > {30,32}
                  actions.add(new OFActionOutput((short)30,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)32,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_106 && pi.getInPort() == 31){		//31 -- > {30,32}
                  actions.add(new OFActionOutput((short)30,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)32,(short)0xFFFF));
                  }
              //107
        else if(sw.getId()== FIU_107 && pi.getInPort() == 34){		//34 --> {35,36}
                  actions.add(new OFActionOutput((short)35,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)36,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_107 && pi.getInPort() == 36){		//36 --> {35,34}
                  actions.add(new OFActionOutput((short)35,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)34,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_107 && pi.getInPort() == 35){		//35 -- > {34,36}
                  actions.add(new OFActionOutput((short)34,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)36,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_107 && pi.getInPort() == 33){		//33 -- > {34,36}
                  actions.add(new OFActionOutput((short)34,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)36,(short)0xFFFF));
                  }
              //108
        else if(sw.getId()== FIU_108 && pi.getInPort() == 38){		//38 --> {40,39}
                  actions.add(new OFActionOutput((short)40,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)39,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_108 && pi.getInPort() == 40){		//40 --> {38,39}
                  actions.add(new OFActionOutput((short)38,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)39,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_108 && pi.getInPort() == 39){		//39 -- > {38,40}
                  actions.add(new OFActionOutput((short)38,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)40,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_108 && pi.getInPort() == 37){		//37 -- > {38,40}
                  actions.add(new OFActionOutput((short)38,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)40,(short)0xFFFF));
                  }
              
              /**********************AGGR LAYER*********************************
               * 
               */
              
             //201
        /*
        else if(sw.getId()== FIU_201 && pi.getInPort() == 43){		//43 --> {42,41}
                  actions.add(new OFActionOutput((short)42,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_201 && pi.getInPort() == 42){		//42 --> {43,41}
                  actions.add(new OFActionOutput((short)43,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_201 && pi.getInPort() == 44){		//44 -- > {43,41}
                  actions.add(new OFActionOutput((short)43,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                }
              //202
        else if(sw.getId()== FIU_202 && pi.getInPort() == 45){		//45 --> {46,47}
                  actions.add(new OFActionOutput((short)46,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_202 && pi.getInPort() == 46){		//46 --> {47,45}
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)45,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_202 && pi.getInPort() == 48){		//48 -- > {47,45}
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)45,(short)0xFFFF));
                }
              //203
        else if(sw.getId()== FIU_203 && pi.getInPort() == 51){		//51 --> {52,49}
                  actions.add(new OFActionOutput((short)52,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 20333333333333+++++++++++++");
                       }
        else if(sw.getId()==FIU_203 && pi.getInPort() == 50){		//50 --> {51,49}
                  actions.add(new OFActionOutput((short)51,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 20333333333333+++++++++++++");
                }
        else if(sw.getId()==FIU_203 && pi.getInPort() == 52){		//52 -- > {51,49}
                  actions.add(new OFActionOutput((short)51,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 20333333333333+++++++++++++");
        }
              //204
        else if(sw.getId()== FIU_204 && pi.getInPort() == 53){		//53 --> {56,55}
                  actions.add(new OFActionOutput((short)56,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_204 && pi.getInPort() == 54){		//54 --> {55,53}
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)53,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_204 && pi.getInPort() == 56){		//56 -- > {55,53}
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)53,(short)0xFFFF));
                }
              //205
        else if(sw.getId()== FIU_205 && pi.getInPort() == 43){		//43 --> {44,41}
                  actions.add(new OFActionOutput((short)44,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_205 && pi.getInPort() == 44){		//44 --> {43,41}
                  actions.add(new OFActionOutput((short)43,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_205 && pi.getInPort() == 42){		//42 -- > {43,41}
                  actions.add(new OFActionOutput((short)43,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)41,(short)0xFFFF));
                }
              //206
        else if(sw.getId()== FIU_206 && pi.getInPort() == 45){		//45 --> {47,48}
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)48,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_206 && pi.getInPort() == 46){		//46 --> {47,45}
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)45,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_206 && pi.getInPort() == 48){		//48 -- > {47,45}
                  actions.add(new OFActionOutput((short)47,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)45,(short)0xFFFF));
                }
              //207
        else if(sw.getId()== FIU_207 && pi.getInPort() == 51){		//51 --> {50,49}
                  actions.add(new OFActionOutput((short)50,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_207 && pi.getInPort() == 52){		//52 --> {51,49}
                  actions.add(new OFActionOutput((short)51,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_207 && pi.getInPort() == 50){		//50 -- > {51,49}
                  actions.add(new OFActionOutput((short)51,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)49,(short)0xFFFF));
                }
              //208
        else if(sw.getId()== FIU_202 && pi.getInPort() == 53){		//53 --> {55,56}
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)56,(short)0xFFFF));
                       }
        else if(sw.getId()==FIU_202 && pi.getInPort() == 54){		//54 --> {55,53}
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)53,(short)0xFFFF));
                }
        else if(sw.getId()==FIU_202 && pi.getInPort() == 56){		//56 -- > {55,53}
                  actions.add(new OFActionOutput((short)55,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)53,(short)0xFFFF));
                }
              
              /**********************CORE LAYER*********************************
               * 
               */
              
             //301
        /*
        else if(sw.getId()== FIU_301 && pi.getInPort() == 58){		//58 --> {57,60,59}
                  actions.add(new OFActionOutput((short)57,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)60,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)59,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 30111111111+++++++++++++\n");
                       }
        else if(sw.getId()==FIU_301 && pi.getInPort() == 57){		//57 --> {58,60,59}
                  actions.add(new OFActionOutput((short)58,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)60,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)59,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 3011111111111111+++++++++++++\n");
                }
              
            //302
      
        else if(sw.getId()== FIU_302 && pi.getInPort() == 64){		//64 --> {62,61,63}
                  actions.add(new OFActionOutput((short)62,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)61,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)63,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 3022222222222222222222222+++++++++++++\n");
                       }
        else if(sw.getId()==FIU_302 && pi.getInPort() == 63){		//63 --> {62,64,61}
                  actions.add(new OFActionOutput((short)62,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)64,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)61,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 3022222222222222222222222+++++++++++++\n");
                }
              
            //303
        else if(sw.getId()== FIU_303 && pi.getInPort() == 59){		//59 --> {57,58,60}
                  actions.add(new OFActionOutput((short)57,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)58,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)60,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 30333333333333+++++++++++++\n");
                       }
        else if(sw.getId()==FIU_303 && pi.getInPort() == 60){		//60 --> {57,58,59}
                  actions.add(new OFActionOutput((short)57,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)58,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)59,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 30333333333333+++++++++++++\n");
                }
            //304
        else if(sw.getId()== FIU_304 && pi.getInPort() == 63){		//63 --> {62,61,64}
                  actions.add(new OFActionOutput((short)62,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)61,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)64,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 30444444444444+++++++++++++\n");
                       }
        else if(sw.getId()==FIU_304 && pi.getInPort() == 62){		//62 --> {63,61,64}
                  actions.add(new OFActionOutput((short)63,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)61,(short)0xFFFF));
                  actions.add(new OFActionOutput((short)64,(short)0xFFFF));
                  System.out.print("+++++++++++++++++++Adding Actions for 304444444444444444+++++++++++++\n");
                }

        //ORIGINAL 
       // po.setActions(actions);
      //  po.setActionsLength((short)(OFActionOutput.MINIMUM_LENGTH));
        
        
        /*
        po.setActions(actions);
        po.setActionsLength((short)(OFActionOutput.MINIMUM_LENGTH*actions.size()));
        System.out.print("++++++ACTION SIZE IS++++++++++++: "+actions.size());

        // set buffer-id, in-port and packet-data based on packet-in
        short poLength = (short)(po.getActionsLength() + OFPacketOut.MINIMUM_LENGTH);
        po.setBufferId(pi.getBufferId());
        po.setInPort(pi.getInPort());
        if (pi.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
            byte[] packetData = pi.getPacketData();
            poLength += packetData.length;
            po.setPacketData(packetData);
        }
        po.setLength(poLength);
        
        try {
            if (log.isTraceEnabled()) {
                log.trace("Writing flood PacketOut switch={} packet-in={} packet-out={}",
                          new Object[] {sw, pi, po});
            }
            messageDamper.write(sw, po, cntx);
        } catch (IOException e) {
            log.error("Failure writing PacketOut switch={} packet-in={} packet-out={}",
                    new Object[] {sw, pi, po}, e);
        }   */         

        return;
    } //doFlood() ends here
    
    // IFloodlightModule methods
    
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        // We don't export any services
        return null;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService>
            getServiceImpls() {
        // We don't have any services
        return null;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l = 
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        l.add(IDeviceService.class);
        l.add(IRoutingService.class);
        l.add(ITopologyService.class);
        l.add(ICounterStoreService.class);
        return l;
    }

    @Override
    @LogMessageDocs({
        @LogMessageDoc(level="WARN",
                message="Error parsing flow idle timeout, " +
                        "using default of {number} seconds",
                explanation="The properties file contains an invalid " +
                        "flow idle timeout",
                recommendation="Correct the idle timeout in the " +
                        "properties file."),
        @LogMessageDoc(level="WARN",
                message="Error parsing flow hard timeout, " +
                        "using default of {number} seconds",
                explanation="The properties file contains an invalid " +
                            "flow hard timeout",
                recommendation="Correct the hard timeout in the " +
                                "properties file.")
    })
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        super.init();
        this.floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        this.deviceManager = context.getServiceImpl(IDeviceService.class);
        this.routingEngine = context.getServiceImpl(IRoutingService.class);
        this.topology = context.getServiceImpl(ITopologyService.class);
        this.counterStore = context.getServiceImpl(ICounterStoreService.class);
        
        // read our config options
        Map<String, String> configOptions = context.getConfigParams(this);
        try {
            String idleTimeout = configOptions.get("idletimeout");
            if (idleTimeout != null) {
                FLOWMOD_DEFAULT_IDLE_TIMEOUT = Short.parseShort(idleTimeout);
            }
        } catch (NumberFormatException e) {
            log.warn("Error parsing flow idle timeout, " +
            		 "using default of {} seconds",
                     FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        }
        try {
            String hardTimeout = configOptions.get("hardtimeout");
            if (hardTimeout != null) {
                FLOWMOD_DEFAULT_HARD_TIMEOUT = Short.parseShort(hardTimeout);
            }
        } catch (NumberFormatException e) {
            log.warn("Error parsing flow hard timeout, " +
            		 "using default of {} seconds",
                     FLOWMOD_DEFAULT_HARD_TIMEOUT);
        }
        log.debug("FlowMod idle timeout set to {} seconds", 
                  FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        log.debug("FlowMod hard timeout set to {} seconds", 
                  FLOWMOD_DEFAULT_HARD_TIMEOUT);
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        super.startUp();
    }
}
