package org.fog.placement;


import java.util.*;
import java.util.stream.Collectors;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.application.AppEdge;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.LocationHandler;
import org.fog.mobilitydata.References;
import org.fog.utils.Config;
import org.fog.utils.FogEvents;
import org.fog.utils.FogUtils;
import org.fog.utils.MigrationDelayMonitor;
import org.fog.utils.MobilityStats;
import org.fog.utils.NetworkUsageMonitor;
import org.fog.utils.TimeKeeper;
import org.json.simple.JSONObject;
import org.fog.placement.ModulePlacement;



public class MobilityController extends SimEntity{
			/** Returns the list of all fog devices (excluding cloud if needed). */
			private List<FogDevice> getFogDevicesList() {
				return getFogDevices();
			}

			/** Returns the total CPU capacity (MIPS) of the given fog device. */
			private double getFogCpuCapacity(FogDevice fog) {
				if (fog != null && fog.getHost() != null) {
					return fog.getHost().getTotalMips();
				}
				return 0.0;
			}

			/** Returns the total memory capacity (RAM in MB) of the given fog device. */
			private double getFogMemCapacity(FogDevice fog) {
				if (fog != null && fog.getHost() != null && fog.getHost().getRamProvisioner() != null) {
					return fog.getHost().getRamProvisioner().getRam();
				}
				return 0.0;
			}

			/** Returns the total bandwidth capacity (uplink BW in Mbps) of the given fog device. */
			private double getFogBwCapacity(FogDevice fog) {
				if (fog != null) {
					return fog.getUplinkBandwidth();
				}
				return 0.0;
			}
		/** Returns the total CPU capacity (MIPS) of the cloud device. Returns 0 if not found. */
		private double getCloudDeviceCpuCapacity() {
			FogDevice cloud = getCloud();
			if (cloud != null && cloud.getHost() != null) {
				return cloud.getHost().getTotalMips();
			}
			return 0.0;
		}
	
	public static boolean ONLY_CLOUD = false;
		
	private List<FogDevice> fogDevices;
	private List<Sensor> sensors;
	private List<Actuator> actuators;
	private LocationHandler locator;
	private Map<Integer, Integer> parentReference;


	private Map<String, Application> applications;
	private Map<String, Integer> appLaunchDelays;
	

	private Map<String, ModulePlacement> appModulePlacementPolicy;

    //GPT
    private static final int HMAX = 2;               // keep same semantics as your Hmax (tune as needed)
    private static final double LAMBDA_CLOUD = 50.0;  // cloud penalty lambda (tune)
    private static final double PRICE_STEP_ALPHA = 1e-4; // subgradient step (tune)
	
	public MobilityController(String name, List<FogDevice> fogDevices, List<Sensor> sensors, List<Actuator> actuators, LocationHandler locator) {
		super(name);
		this.applications = new HashMap<String, Application>();
		setLocator(locator);
		setAppLaunchDelays(new HashMap<String, Integer>());
		setParentReference(new HashMap<Integer, Integer>());
		setAppModulePlacementPolicy(new HashMap<String, ModulePlacement>());
		for(FogDevice fogDevice : fogDevices){
			fogDevice.setControllerId(getId());
		}
		setFogDevices(fogDevices);
		setActuators(actuators);
		setSensors(sensors);
		connectWithLatencies();
	}

	private void setParentReference(HashMap<Integer, Integer> parentReference) {
		// TODO Auto-generated method stub
		this.parentReference = parentReference;
	}

	private FogDevice getFogDeviceById(int id){
		for(FogDevice fogDevice : getFogDevices()){
			if(id==fogDevice.getId())
				return fogDevice;
		}
		return null;
	}
	
	private void connectWithLatencies(){
		
		for (String dataId: locator.getDataIdsLevelReferences().keySet())
		{
			for(int instenceId: locator.getInstenceDataIdReferences().keySet())
			{
				if(locator.getInstenceDataIdReferences().get(instenceId).equals(dataId))
				{
					FogDevice fogDevice = getFogDeviceById(instenceId);
					if(locator.getDataIdsLevelReferences().get(dataId)==locator.getLevelID("User") && fogDevice.getParentId()==References.NOT_SET){
						int parentID = locator.determineParent(fogDevice.getId(),References.INIT_TIME);
						parentReference.put(fogDevice.getId(),parentID);
						fogDevice.setParentId(parentID);
					}
					else
						parentReference.put(fogDevice.getId(),fogDevice.getParentId());
				}
			}
		}
		
		
		FogDevice cloud = getCloud();
		parentReference.put(cloud.getId(),cloud.getParentId());
		
		for(FogDevice fogDevice : getFogDevices()){
			FogDevice parent = getFogDeviceById(parentReference.get(fogDevice.getId()));
			if(parent == null)
				continue;
			double latency = fogDevice.getUplinkLatency();
			parent.getChildToLatencyMap().put(fogDevice.getId(), latency);
			parent.getChildrenIds().add(fogDevice.getId());
			System.out.println("Child "+fogDevice.getName()+"\t----->\tParent "+parent.getName());
		}
	}
	
	@Override
	public void startEntity() {
		for(String appId : applications.keySet()){
			if(getAppLaunchDelays().get(appId)==0)
				processAppSubmit(applications.get(appId));
			else
				send(getId(), getAppLaunchDelays().get(appId), FogEvents.APP_SUBMIT, applications.get(appId));
		}

		send(getId(), Config.RESOURCE_MANAGE_INTERVAL, FogEvents.CONTROLLER_RESOURCE_MANAGE);
		
		send(getId(), Config.MAX_SIMULATION_TIME, FogEvents.STOP_SIMULATION);
		
		sendNow(getId(), FogEvents.MOBILITY_SUBMIT);
		
		for(FogDevice dev : getFogDevices())
			sendNow(dev.getId(), FogEvents.RESOURCE_MGMT);

	}

	@Override
	public void processEvent(SimEvent ev) {
		switch(ev.getTag()){
		case FogEvents.APP_SUBMIT:
			processAppSubmit(ev);
			break;
		case FogEvents.MOBILITY_SUBMIT:
			processMobilityData();
			break;
		case FogEvents.MOBILITY_MANAGEMENT:
			processMobility(ev);
			break;
		case FogEvents.TUPLE_FINISHED:
			processTupleFinished(ev);
			break;
		case FogEvents.CONTROLLER_RESOURCE_MANAGE:
			manageResources();
			break;
		case FogEvents.STOP_SIMULATION:
			CloudSim.stopSimulation();
			printTimeDetails();
			printPowerDetails();
			printCostDetails();
			printNetworkUsageDetails();
			printMigrationDelayDetails();
			printMobilityStatistics();
			System.exit(0);
			break;

            default:
                throw new IllegalStateException("Unexpected value: " + ev.getTag());
        }
	}
	
	private void printMigrationDelayDetails() {
		// TODO Auto-generated method stub
		System.out.println("Total time required for module migration = "+MigrationDelayMonitor.getMigrationDelay());
	}

	private void printMobilityStatistics() {
		System.out.println("=========================================");
		System.out.println("====== MOBILITY / PLACEMENT METRICS =====");
		System.out.println("=========================================");
		System.out.println("Average end-to-end latency (Li)        = " + MobilityStats.getAverageLatency());
		System.out.println("Deadline satisfaction ratio             = " + MobilityStats.getDeadlineSatisfactionRatio());
		System.out.println("Fog-to-fog offload count                = " + MobilityStats.getFogToFogOffloadCount());
		System.out.println("Cloud offload count                     = " + MobilityStats.getCloudOffloadCount());
		System.out.println("Cloud usage ratio (cloud / all offloads)= " + MobilityStats.getCloudUsageRatio());
		System.out.println("Total completed tasks                   = " + MobilityStats.getCompletedTasks());
		System.out.println("=========================================");
	}

	/*private void printFogDeviceChildren(int deviceID) {
		// TODO Auto-generated method stub
		System.out.println("Childs of "+getFogDeviceById(deviceID).getName());
		for(Integer childId:getFogDeviceById(deviceID).getChildrenIds())
			System.out.println(getFogDeviceById(childId).getName()+"("+childId+")");
		
	}*/


	@SuppressWarnings("unchecked")
//	private void processMobility(SimEvent ev) {
//		// TODO Auto-generated method stub
//		FogDevice fogDevice = (FogDevice) ev.getData();
//		FogDevice prevParent = getFogDeviceById(parentReference.get(fogDevice.getId()));
//        System.out.println(prevParent);
//        int pid = locator.determineParent(fogDevice.getId(),CloudSim.clock());
//		FogDevice newParent = getFogDeviceById(pid);
//		System.out.println(CloudSim.clock()+" Starting Mobility Management for "+fogDevice.getName());
//		parentReference.put(fogDevice.getId(),newParent.getId());
//		List<String>migratingModules = new ArrayList<String>();
//		if(prevParent.getId()!=newParent.getId()) {
//			//printFogDeviceChildren(newParent.getId());
//			//printFogDeviceChildren(prevParent.getId());
//
//			//common ancestor policy
//			List<Integer>newParentPath = getPathsToCloud(newParent.getId());
//			List<Integer>prevParentPath = getPathsToCloud(prevParent.getId());
//			int commonAncestor = determineAncestor(newParentPath,prevParentPath);
//
//
//			fogDevice.setParentId(newParent.getId());
//			System.out.println("Child "+fogDevice.getName()+"\t----->\tParent "+newParent.getName());
//			newParent.getChildToLatencyMap().put(fogDevice.getId(), fogDevice.getUplinkLatency());
//			newParent.addChild(fogDevice.getId());
//			prevParent.removeChild(fogDevice.getId());
//			for(String applicationName:fogDevice.getActiveApplications()){
//				migratingModules = getAppModulePlacementPolicy().get(applicationName).getModulesOnPath().get(fogDevice.getId()).get(prevParent.getId());
//				getAppModulePlacementPolicy().get(applicationName).getModulesOnPath().get(fogDevice.getId()).remove(prevParent.getId());
//				getAppModulePlacementPolicy().get(applicationName).getModulesOnPath().get(fogDevice.getId()).put(newParent.getId(),migratingModules);
//				for(String moduleName:migratingModules){
//					double upDelay = getUpDelay(prevParent.getId(),commonAncestor,getApplications().get(applicationName).getModuleByName(moduleName));
//					double downDelay = getDownDelay(newParent.getId(),commonAncestor,getApplications().get(applicationName).getModuleByName(moduleName));
//					JSONObject jsonSend = new JSONObject();
//					jsonSend.put("module", getApplications().get(applicationName).getModuleByName(moduleName));
//					jsonSend.put("delay", upDelay);
//
//					JSONObject jsonReceive = new JSONObject();
//					jsonReceive.put("module", getApplications().get(applicationName).getModuleByName(moduleName));
//					jsonReceive.put("delay", downDelay);
//					jsonReceive.put("application", getApplications().get(applicationName));
//
//					send(prevParent.getId(),upDelay, FogEvents.MODULE_SEND, jsonSend);
//					send(newParent.getId(),downDelay, FogEvents.MODULE_RECEIVE, jsonReceive);
//					System.out.println("Migrating "+moduleName+" from "+prevParent.getName()+" to "+newParent.getName());
//				}
//			}
//
//			// = get
//			//printFogDeviceChildren(newParent.getId());
//			//printFogDeviceChildren(prevParent.getId());
//		}
//
//
//
//	}

	/**
	 * Returns the ID of the cloud FogDevice instance in the topology.
	 * If not found, returns -1.
	 */
	public int getCloudInstanceId() {
		for (FogDevice dev : getFogDevices()) {
			if (dev.getName().equalsIgnoreCase("cloud")) {
				return dev.getId();
			}
		}
		return -1;
	}


    private Map<Integer, Double> muCpuMap = new HashMap<>();
    private Map<Integer, Double> muMemMap = new HashMap<>();
    private Map<Integer, Double> muBwMap  = new HashMap<>();

    private void processMobility(SimEvent ev) {
		FogDevice fogDevice = (FogDevice) ev.getData();
		int childId = fogDevice.getId();
		FogDevice prevParent = getFogDeviceById(parentReference.get(childId));
		System.out.println("[" + CloudSim.clock() + "] Starting Mobility Management for " + fogDevice.getName());

		// Send mobility event to fog node for decentralized placement
		// Wrap fogDevice and locator in a Map to pass both objects
		Map<String, Object> eventData = new HashMap<>();
		eventData.put("fogDevice", fogDevice);
		eventData.put("locator", locator);
        eventData.put("fogs",getFogDevicesList());
		send(fogDevice.getId(), 0, FogEvents.DECENTRALIZED_MOBILITY_PLACEMENT, eventData);
	}

	private double getDownDelay(int deviceID, int commonAncestorID, AppModule module) {
		// TODO Auto-generated method stub
		double networkDelay = 0.0;
		while(deviceID!=commonAncestorID){	
			networkDelay = networkDelay + module.getSize()/getFogDeviceById(deviceID).getDownlinkBandwidth();
			deviceID = getFogDeviceById(deviceID).getParentId();
		}
		return networkDelay;
	}

	private double getUpDelay(int deviceID, int commonAncestorID, AppModule module) {
		// TODO Auto-generated method stub
		double networkDelay = 0.0;
		while(deviceID!=commonAncestorID){	
			networkDelay = networkDelay + module.getSize()/getFogDeviceById(deviceID).getUplinkBandwidth();
			deviceID = getFogDeviceById(deviceID).getParentId();
		}
		return networkDelay;
	}

	private int determineAncestor(List<Integer> newParentPath, List<Integer> prevParentPath) {
		// TODO Auto-generated method stub
		List<Integer> common = newParentPath.stream().filter(prevParentPath::contains).collect(Collectors.toList());
		return common.get(0);
	}

	private List<Integer> getPathsToCloud(int deviceID) {
		// TODO Auto-generated method stub
		List<Integer>path = new ArrayList<Integer>();
		while(!locator.isCloud(deviceID)){
			path.add(deviceID);
			deviceID = getFogDeviceById(deviceID).getParentId();
		}
		path.add(getCloud().getId());
		return path;
	}

	private void processMobilityData() {
		// TODO Auto-generated method stub
		List<Double>timeSheet = new ArrayList<Double>();
		for(FogDevice fogDevice : getFogDevices()){
			if(locator.isAMobileDevice(fogDevice.getId())) {
				timeSheet = locator.getTimeSheet(fogDevice.getId());
				for(double timeEntry:timeSheet)
					send(getId(), timeEntry, FogEvents.MOBILITY_MANAGEMENT,fogDevice);
			}
		}
	}

	private void printNetworkUsageDetails() {
		System.out.println("Total network usage = "+NetworkUsageMonitor.getNetworkUsage()/Config.MAX_SIMULATION_TIME);		
	}

	private FogDevice getCloud(){
		for(FogDevice dev : getFogDevices())
			if(dev.getName().equals("cloud"))
				return dev;
		return null;
	}
	
	private void printCostDetails(){
		System.out.println("Cost of execution in cloud = "+getCloud().getTotalCost());
	}
	
	private void printPowerDetails() {
		for(FogDevice fogDevice : getFogDevices()){
			System.out.println(fogDevice.getName() + " : Energy Consumed = "+fogDevice.getEnergyConsumption());
		}
	}

	/*
	private String getStringForLoopId(int loopId){
		for(String appId : getApplications().keySet()){
			Application app = getApplications().get(appId);
			for(AppLoop loop : app.getLoops()){
				if(loop.getLoopId() == loopId)
					return loop.getModules().toString();
			}
		}
		return null;
	}
	*/
	private void printTimeDetails() {
		System.out.println("=========================================");
		System.out.println("============== RESULTS ==================");
		System.out.println("=========================================");
		System.out.println("EXECUTION TIME : "+ (Calendar.getInstance().getTimeInMillis() - TimeKeeper.getInstance().getSimulationStartTime()));
		System.out.println("=========================================");
		//System.out.println("APPLICATION LOOP DELAYS");
		//System.out.println("=========================================");
		//for(Integer loopId : TimeKeeper.getInstance().getLoopIdToTupleIds().keySet()){
			/*double average = 0, count = 0;
			for(int tupleId : TimeKeeper.getInstance().getLoopIdToTupleIds().get(loopId)){
				Double startTime = 	TimeKeeper.getInstance().getEmitTimes().get(tupleId);
				Double endTime = 	TimeKeeper.getInstance().getEndTimes().get(tupleId);
				if(startTime == null || endTime == null)
					break;
				average += endTime-startTime;
				count += 1;
			}
			System.out.println(getStringForLoopId(loopId) + " ---> "+(average/count));*/
			//System.out.println(getStringForLoopId(loopId) + " ---> "+TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(loopId));
		//}
		System.out.println("=========================================");
		System.out.println("TUPLE CPU EXECUTION DELAY");
		System.out.println("=========================================");
		
		for(String tupleType : TimeKeeper.getInstance().getTupleTypeToAverageCpuTime().keySet()){
			System.out.println(tupleType + " ---> "+TimeKeeper.getInstance().getTupleTypeToAverageCpuTime().get(tupleType));
		}
		
		System.out.println("=========================================");
	}

	protected void manageResources(){
		send(getId(), Config.RESOURCE_MANAGE_INTERVAL, FogEvents.CONTROLLER_RESOURCE_MANAGE);
	}
	
	private void processTupleFinished(SimEvent ev) {
	}
	
	@Override
	public void shutdownEntity() {	
	}
	
	public void submitApplication(Application application, int delay, ModulePlacement modulePlacement){
		FogUtils.appIdToGeoCoverageMap.put(application.getAppId(), application.getGeoCoverage());
		getApplications().put(application.getAppId(), application);
		getAppLaunchDelays().put(application.getAppId(), delay);
		getAppModulePlacementPolicy().put(application.getAppId(), modulePlacement);
		
		for(Sensor sensor : sensors){
			sensor.setApp(getApplications().get(sensor.getAppId()));
		}
		for(Actuator ac : actuators){
			ac.setApp(getApplications().get(ac.getAppId()));
		}
		
		for(AppEdge edge : application.getEdges()){
			if(edge.getEdgeType() == AppEdge.ACTUATOR){
				String moduleName = edge.getSource();
				for(Actuator actuator : getActuators()){
					if(actuator.getActuatorType().equalsIgnoreCase(edge.getDestination()))
						application.getModuleByName(moduleName).subscribeActuator(actuator.getId(), edge.getTupleType());
				}
			}
		}	
	}
	
	public void submitApplication(Application application, ModulePlacement modulePlacement){
		submitApplication(application, 0, modulePlacement);
	}
	
	
	private void processAppSubmit(SimEvent ev){
		Application app = (Application) ev.getData();
		processAppSubmit(app);
	}
	
	private void processAppSubmit(Application application){
		System.out.println(CloudSim.clock()+" Submitted application "+ application.getAppId());
		FogUtils.appIdToGeoCoverageMap.put(application.getAppId(), application.getGeoCoverage());
		getApplications().put(application.getAppId(), application);
		
		ModulePlacement modulePlacement = getAppModulePlacementPolicy().get(application.getAppId());
		for(FogDevice fogDevice : fogDevices){
			sendNow(fogDevice.getId(), FogEvents.ACTIVE_APP_UPDATE, application);
		}
		
		Map<Integer, List<AppModule>> deviceToModuleMap = modulePlacement.getDeviceToModuleMap();
		for(Integer deviceId : deviceToModuleMap.keySet()){
			for(AppModule module : deviceToModuleMap.get(deviceId)){
				sendNow(deviceId, FogEvents.APP_SUBMIT, application);
				sendNow(deviceId, FogEvents.LAUNCH_MODULE, module);
			}
		}
	}

	public List<FogDevice> getFogDevices() {
		return fogDevices;
	}

	public void setFogDevices(List<FogDevice> fogDevices) {
		this.fogDevices = fogDevices;
	}

	public Map<String, Integer> getAppLaunchDelays() {
		return appLaunchDelays;
	}

	public void setAppLaunchDelays(Map<String, Integer> appLaunchDelays) {
		this.appLaunchDelays = appLaunchDelays;
	}

	public Map<String, Application> getApplications() {
		return applications;
	}

	public void setApplications(Map<String, Application> applications) {
		this.applications = applications;
	}

	public List<Sensor> getSensors() {
		return sensors;
	}

	public void setSensors(List<Sensor> sensors) {
		for(Sensor sensor : sensors)
			sensor.setControllerId(getId());
		this.sensors = sensors;
	}

	public List<Actuator> getActuators() {
		return actuators;
	}

	public void setActuators(List<Actuator> actuators) {
		this.actuators = actuators;
	}

	public Map<String, ModulePlacement> getAppModulePlacementPolicy() {
		return appModulePlacementPolicy;
	}

	public void setAppModulePlacementPolicy(Map<String, ModulePlacement> appModulePlacementPolicy) {
		this.appModulePlacementPolicy = appModulePlacementPolicy;
	}
	
	public LocationHandler getLocator() {
		return locator;
	}

	public void setLocator(LocationHandler locator) {
		this.locator = locator;
	}

    /** Helper: gathers candidate fog IDs within H hops of the prev/new parent.
     * We walk the paths-to-cloud returned by getPathsToCloud to estimate hop distances.
     */
    private Set<Integer> getCandidateFogsWithinHops(int prevParentId, int newParentId, int H) {
        Set<Integer> candidates = new HashSet<>();
        // Add prev and new parent and their ancestors up to H hops
        List<Integer> prevPath = getPathsToCloud(prevParentId);
        List<Integer> newPath = getPathsToCloud(newParentId);
        // Add nodes within H distance along each path
        for (int i = 0; i < prevPath.size() && i <= H; i++) candidates.add(prevPath.get(i));
        for (int i = 0; i < newPath.size() && i <= H; i++) candidates.add(newPath.get(i));
        // Also add siblings: children of those ancestors within H hops (if you have such mapping)
        // (Optional) Add other one-hop neighbor fogs if you maintain a neighbor graph.
        return candidates;
    }

    /** Estimate queueing delay at a fog for the given module.
     * If you have instantaneous queue-length / service rate info in FogDevice, use that.
     * Here we fallback to a small default.
     */
    private double estimateQueueingDelay(FogDevice fog, AppModule module) {
        // TODO: adapt to your fog device API (eg fog.getCurrentQueueLength(), fog.getServiceRate())
        if (fog.getChildToLatencyMap() != null) {
            // placeholder: use a small constant or computed M/M/1 estimate
            return 1.0;
        }
        return 1.0;
    }

    /** Estimate processing time of the module at the candidate fog */
    private double estimateProcessingTimeAtFog(AppModule module, FogDevice fog) {
        // TODO: map module processing demand to wi and fog CPU capacity to Ccpu.
        // If module has getRequiredMips() and fog has getTotalMips(), use
        // p_if = module.getRequiredMips() / fog.getCpuCapacity()
        double moduleMi = estimateCpuDemand(module);
        double fogCpu = fog.getHost().getTotalMips(); // adapt as necessary
        if (fogCpu <= 0) return Double.POSITIVE_INFINITY;
        return moduleMi / fogCpu;
    }

    /** Estimate processing time at cloud for module */
    private double estimateProcessingTimeAtCloud(AppModule module) {
        // TODO: implement or use a configured cloud CPU capacity
        double moduleMi = estimateCpuDemand(module);
        double cloudCpu = getCloudDeviceCpuCapacity();
        if (cloudCpu <= 0) return Double.POSITIVE_INFINITY;
        return moduleMi / cloudCpu;
    }

    /** Mobility indicator detection: use RSSI or predicted sojourn if available, otherwise fallback to 0. */
    private int determineMobilityIndicatorFor(FogDevice fog, FogDevice movingDevice, AppModule module) {
        // If you have RSSI: if (rssi < tau_rssi || sojourn < tau_stay) return 1; else 0.
        // For now fallback to 0 (no handoff). You should adapt to use your actual RSSI / sojourn API.
        return 0;
    }

    /** Estimate expected handoff/migration delay for module at fog. */
    private double estimateHandoffDelay(FogDevice fog, AppModule module) {
        // TODO: return measured migration/handoff delay for module, or a configured constant
        return 10.0;
    }

    /** Try reading CPU demand (MI) from module */
    private double estimateCpuDemand(AppModule module) {
        // TODO: adapt to your module model. Example: module.getSize() or module.getRequiredMips()
        try {
            return module.getSize(); // or getRequiredMips, whichever represents the amount of work
        } catch (Exception e) {
            return 1000.0;
        }
    }

    /** Try reading memory demand (MB) from module */
    private double estimateMemDemand(AppModule module) {
        // TODO
        return module.getRam();
    }

    /** Try reading bandwidth demand from module (for state transfer/result return) */
    private double estimateBwDemand(AppModule module) {
        // TODO
        return 50.0;
    }

    /** Aggregate current assignments and update local Lagrange multipliers (mu maps) */
    private void updateFogPricesAfterAssignments() {
        // Compute aggregated usage for each fog from current placement maps (getAppModulePlacementPolicy())
        Map<Integer, Double> usageCpu = new HashMap<>();
        Map<Integer, Double> usageMem = new HashMap<>();
        Map<Integer, Double> usageBw  = new HashMap<>();

        // Walk over all apps and module placements to accumulate demand per fog
		for (String appName : getAppModulePlacementPolicy().keySet()) {
			ModulePlacement modulePlacement = getAppModulePlacementPolicy().get(appName);
			// modulePlacement.getModulesOnPath() returns mapping: deviceId -> (fogId -> List<moduleNames>)
			for (Integer deviceId : modulePlacement.getModulesOnPath().keySet()) {
				Map<Integer, List<String>> fogMap = modulePlacement.getModulesOnPath().get(deviceId);
				for (Integer fogId : fogMap.keySet()) {
					if (!usageCpu.containsKey(fogId)) {
						usageCpu.put(fogId, 0.0);
						usageMem.put(fogId, 0.0);
						usageBw.put(fogId, 0.0);
					}
					for (String modName : fogMap.get(fogId)) {
						AppModule module = getApplications().get(appName).getModuleByName(modName);
						double cpu = estimateCpuDemand(module);
						double mem = estimateMemDemand(module);
						double bw  = estimateBwDemand(module);
						usageCpu.put(fogId, usageCpu.get(fogId) + cpu);
						usageMem.put(fogId, usageMem.get(fogId) + mem);
						usageBw.put(fogId, usageBw.get(fogId) + bw);
					}
				}
			}
		}

        // Update mu maps: mu <- max(0, mu + alpha * (usage - capacity))
        for (FogDevice fog : getFogDevicesList()) {
            int id = fog.getId();
            double usageC = usageCpu.getOrDefault(id, 0.0);
            double usageM = usageMem.getOrDefault(id, 0.0);
            double usageB = usageBw.getOrDefault(id, 0.0);

            double capC = getFogCpuCapacity(fog); // implement helper to read capacity in same units as cpu demand
            double capM = getFogMemCapacity(fog);
            double capB = getFogBwCapacity(fog);

            double muC = muCpuMap.getOrDefault(id, 0.0);
            double muM = muMemMap.getOrDefault(id, 0.0);
            double muB = muBwMap.getOrDefault(id, 0.0);

            muC = Math.max(0.0, muC + PRICE_STEP_ALPHA * (usageC - capC));
            muM = Math.max(0.0, muM + PRICE_STEP_ALPHA * (usageM - capM));
            muB = Math.max(0.0, muB + PRICE_STEP_ALPHA * (usageB - capB));

            muCpuMap.put(id, muC);
            muMemMap.put(id, muM);
            muBwMap.put(id, muB);
        }
    }

}