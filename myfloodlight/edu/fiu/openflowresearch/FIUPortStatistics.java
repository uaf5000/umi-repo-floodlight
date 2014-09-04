package edu.fiu.openflowresearch;

	
	import net.floodlightcontroller.core.IFloodlightProviderService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.Set;
import java.util.Collections;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.topology.ITopologyListener;
import net.floodlightcontroller.topology.ITopologyService;

import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsReply;
import org.openflow.protocol.OFStatisticsRequest;
import org.openflow.protocol.OFType;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;

import net.floodlightcontroller.linkdiscovery.ILinkDiscovery.LDUpdate;
import net.floodlightcontroller.linkdiscovery.ILinkDiscovery;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.forwarding.SwitchDPIDList;
import edu.fiu.tools.StaticDPIDConverter;

	public class FIUPortStatistics implements SwitchDPIDList, ITopologyListener, IOFMessageListener, IFloodlightModule {
		
		protected IFloodlightProviderService floodlightProvider;
		protected Set macAddresses;
		protected static Logger logger;
		protected static boolean waiting = false;
		protected int statsQueryXId;
		protected static List<OFStatistics> statsReply;
		protected IOFSwitch sw;
		protected ITopologyService topology;
		protected StaticDPIDConverter converter = new StaticDPIDConverter(false);
		
        public List<Long> p1 = new ArrayList<Long>();
        public List<Long> p2 = new ArrayList<Long>();
        public List<Long> p3 = new ArrayList<Long>();
        public List<Long> p4 = new ArrayList<Long>();
        public List<Long> core = new ArrayList<Long>();
        public List<Long> tors = new ArrayList<Long>();
        public List<Long> aggs = new ArrayList<Long>();


        public Map<Integer, List<Long>> swPodMap = new HashMap<Integer, List<Long>>();

		
		

		  public List<OFPortStatisticsReply> getSwitchStatistics(long switchId, OFStatisticsType statType)  {
		        
			   	 /* //not working, left here to be fixed for future implementation
			  	  IFloodlightProviderService floodlightProvider = 
			                (IFloodlightProviderService)getContext().getAttributes().
			                    get(IFloodlightProviderService.class.getCanonicalName());*/
			  	  
			        
			        //Future<List<OFStatistics>> future;
			        List<OFPortStatisticsReply> values = new ArrayList<OFPortStatisticsReply>();
			        statsReply = new ArrayList<OFStatistics>();
			       sw = floodlightProvider.getSwitches().get(switchId);
			        //System.out.print("Value of "+sw);
			        System.out.println("+++++++++ENTERED FIU+++++++++");
			        if (sw != null) {
			        	System.out.println("+++++++++ENTERED FIU IF CONDITION+++++++++");
			            OFStatisticsRequest req = new OFStatisticsRequest();
		            	System.out.println("Switch ID IS++++++++++++++++"+switchId);
			            req.setStatisticType(statType);
			            int requestLength = req.getLengthU();
			            if (statType == OFStatisticsType.PORT) {
			                OFPortStatisticsRequest specificReq = new OFPortStatisticsRequest();
			                specificReq.setPortNumber((short)OFPort.OFPP_ALL.getValue());
			            	System.out.println("Values of port are++++++++++++"+(short)OFPort.OFPP_ALL.getValue());
			                req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
			                requestLength += specificReq.getLength();
			            }
			            req.setLengthU(requestLength);
			            
			            
			            statsQueryXId = sw.getNextTransactionId();
			            System.out.println("++++ID IS++++"+statsQueryXId);
			            try {
			                //future = sw.getStatistics(req);
			               // values = future.get(10, TimeUnit.SECONDS);
			            	sw.sendStatsQuery(req, statsQueryXId, this);
			            	System.out.println("JUST SENT STATS QUERY++++++++++++++++++++"+req+"---TO---"+converter.convertDPID(sw.getId()));

			            	waiting = true;
			            	// Wait for the reply, sleep for 10ms then check
			            	while(waiting){
			            		try {
			            		Thread.sleep(100);
				            	System.out.println("We are waiting++++++++++++");

			            		} catch (InterruptedException e) {
			            		// TODO Auto-generated catch block
			            		e.printStackTrace();
			            		}
			            		}
 	
			            // Cast OFPortStatistics to OFPortStatisticsReply
			            for(OFStatistics stat : statsReply){
			            	values.add((OFPortStatisticsReply) stat);
			            	}
			            	System.out.println("Values are++++++++++++"+values);
			            	return values;
			            	} catch (IOException e) {
			            	// TODO Auto-generated catch block
			            	e.printStackTrace();
			            	}
			        }
			            	// Return empty list
			            	return values;
			            	}

			        

			    
			   
		
		@Override
		public String getName() {
		    return FIUPortStatistics.class.getSimpleName();
		}


		public int getId() {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public boolean isCallbackOrderingPrereq(OFType type, String name) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean isCallbackOrderingPostreq(OFType type, String name) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public Collection<Class<? extends IFloodlightService>> getModuleServices() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		    Collection<Class<? extends IFloodlightService>> l =
		        new ArrayList<Class<? extends IFloodlightService>>();
		    l.add(IFloodlightProviderService.class);
		    l.add(ITopologyService.class);
		    return l;
		}


		@Override
		public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		    floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		    macAddresses = new ConcurrentSkipListSet<Long>();
		    logger = LoggerFactory.getLogger(FIUPortStatistics.class);
		    topology = context.getServiceImpl(ITopologyService.class);
		}

		@Override
		public void startUp(FloodlightModuleContext context) {
			topology.addListener((ITopologyListener) this);
		    floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		    floodlightProvider.addOFMessageListener(OFType.STATS_REPLY, this);
		    assignSwitchesToPods();
		}

		@Override
		   public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		        Ethernet eth =
		                IFloodlightProviderService.bcStore.get(cntx,
		                                            IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		       // System.out.println("+++SWITCH ID IN RECEIVE IS+++"+sw.getId());
		    	switch (msg.getType()) {
		    	case STATS_REPLY:
		    	System.out.println("STATS REPLY IN");
		    	if(msg.getXid() == statsQueryXId && waiting){
		    	OFStatisticsReply reply = (OFStatisticsReply) msg;
		    	statsReply = reply.getStatistics();
		    	waiting = false;
		    	}
		    	break;
		    	default:
		    	break;
		    }

		        Long sourceMACHash = Ethernet.toLong(eth.getSourceMACAddress());
		        		        
		        if (!macAddresses.contains(sourceMACHash)) {
		            macAddresses.add(sourceMACHash);
		            logger.info("MAC Address: {} seen on switch: {}",
		                    HexString.toHexString(sourceMACHash),
		                    sw.getId());
		        }
		        return Command.CONTINUE;
		    }






		@Override
		public void topologyChanged() {
			for (LDUpdate ldu : topology.getLastLinkUpdates()) {
				if (ldu.getOperation().equals(
				ILinkDiscovery.UpdateOperation.SWITCH_UPDATED)) {
				System.out.println("inside topology changed");
				
				// Get the switch ID for the OFMatchWithSwDpid object
				long updatedSwitch = floodlightProvider.getSwitches().get(ldu.getSrc()).getId();
				List<OFPortStatisticsReply> tempStats = getSwitchStatistics(updatedSwitch, OFStatisticsType.PORT);
				System.out.println("+++++++++STATS OF SWITCH#"+updatedSwitch+"------"+tempStats);
			
		}
	}
		}
		
		public void assignSwitchesToPods() {
			
			
			//core
	        core.add(FIU_301);
	        core.add(FIU_302);
	        core.add(FIU_303);
	        core.add(FIU_304);
			
			//pod 1
	        p1.add(FIU_101);
	        p1.add(FIU_102);
	        p1.add(FIU_201);
	        p1.add(FIU_202);
	        
	        //pod 2
	        p2.add(FIU_103);
	        p2.add(FIU_104);
	        p2.add(FIU_203);
	        p2.add(FIU_204);
	        
	        //pod 3
	        p3.add(FIU_105);
	        p3.add(FIU_106);
	        p3.add(FIU_205);
	        p3.add(FIU_206);
	        
	        //pod 4
	        p4.add(FIU_107);
	        p4.add(FIU_108);
	        p4.add(FIU_207);
	        p4.add(FIU_208);
	        
			//ToRs
	        tors.add(FIU_101);
	        tors.add(FIU_102);
	        tors.add(FIU_103);
	        tors.add(FIU_104);
	        tors.add(FIU_105);
	        tors.add(FIU_106);
	        tors.add(FIU_107);
	        tors.add(FIU_108);
	        
			//Aggs
	        aggs.add(FIU_201);
	        aggs.add(FIU_202);
	        aggs.add(FIU_203);
	        aggs.add(FIU_204);
	        aggs.add(FIU_207);
	        aggs.add(FIU_205);
	        aggs.add(FIU_206);
	        aggs.add(FIU_208);
	        
	        //pod to switch mappings
	        swPodMap.put(0, core);
	        swPodMap.put(1, p1);
	        swPodMap.put(2, p2);
	        swPodMap.put(3, p3);
	        swPodMap.put(4, p4);
	        

  
			
		}
	}
			


