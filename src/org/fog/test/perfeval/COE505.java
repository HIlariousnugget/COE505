package org.fog.test.perfeval;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.*;
import org.fog.mobilitydata.DataParser;
import org.fog.mobilitydata.RandomMobilityGenerator;
import org.fog.mobilitydata.References;
import org.fog.placement.LocationHandler;
import org.fog.placement.MobilityController;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementMobileEdgewards;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.util.*;

public class COE505 {
	static List<FogDevice> fogDevices = new ArrayList<>();
	static List<Sensor> sensors = new ArrayList<>();
	static List<Actuator> actuators = new ArrayList<>();
	static Map<Integer, Integer> userMobilityPattern = new HashMap<>();
	static LocationHandler locator;

	static double SENSOR_TRANSMISSION_TIME = 10;
	static int numberOfMobileUser = 2;
	static int numberOfFogServers = 2;

	static boolean randomMobility_generator = true;
	static boolean renewDataset = false;

	public static void main(String[] args) {
		Log.printLine("Starting COE505 Simulation...");
		try {
			Log.disable();
			int num_user = 2;
			Calendar calendar = Calendar.getInstance();
			boolean trace_flag = false;

			CloudSim.init(num_user, calendar, trace_flag);

			String appId = "COE505_App";
			FogBroker broker = new FogBroker("broker");

			Application application = createApplication(appId, broker.getId());
			application.setUserId(broker.getId());

			DataParser dataObject = new DataParser();
			locator = new LocationHandler(dataObject);

			String datasetReference = References.dataset_reference;
			if (randomMobility_generator) {
				datasetReference = References.dataset_random;
				createRandomMobilityDatasets(References.random_walk_mobility_model, datasetReference, renewDataset);
			}

            createMobileUser(broker.getId(), appId, datasetReference);
			createFogDevices(broker.getId(), appId);


			ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
			moduleMapping.addModuleToDevice("storageModule", "cloud");

			MobilityController controller = new MobilityController("master-controller", fogDevices, sensors, actuators, locator);
			controller.submitApplication(application, 0, (new ModulePlacementMobileEdgewards(fogDevices, sensors, actuators, application, moduleMapping)));

			TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());

			CloudSim.startSimulation();
			CloudSim.stopSimulation();

			Log.printLine("COE505 Simulation finished!");
		} catch (Exception e) {
			e.printStackTrace();
			Log.printLine("Unwanted errors happen");
		}
	}

	private static void createRandomMobilityDatasets(int mobilityModel, String datasetReference, boolean renewDataset) throws IOException, ParseException {
		RandomMobilityGenerator randMobilityGenerator = new RandomMobilityGenerator();
		for (int i = 0; i < numberOfMobileUser; i++) {
			randMobilityGenerator.createRandomData(mobilityModel, i + 1, datasetReference, renewDataset);
		}
	}

	private static void createMobileUser(int userId, String appId, String datasetReference) throws IOException {
		for (int id = 1; id <= numberOfMobileUser; id++)
			userMobilityPattern.put(id, References.DIRECTIONAL_MOBILITY);

		locator.parseUserInfo(userMobilityPattern, datasetReference);
		List<String> mobileUserDataIds = locator.getMobileUserDataId();

		for (int i = 0; i < numberOfMobileUser; i++) {
			FogDevice mobile = addMobile("mobile_" + i, userId, appId, References.NOT_SET);
			mobile.setUplinkLatency(2);
			locator.linkDataWithInstance(mobile.getId(), mobileUserDataIds.get(i));
			fogDevices.add(mobile);
		}
	}

	private static void createFogDevices(int userId, String appId) throws NumberFormatException, IOException {
		locator.parseResourceInfo();

		// Create cloud server
		FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0.01, 16 * 103, 16 * 83.25);
		cloud.setParentId(References.NOT_SET);
		locator.linkDataWithInstance(cloud.getId(), locator.getLevelWiseResources(locator.getLevelID("Cloud")).get(0));
		fogDevices.add(cloud);

		// Create 2 fog servers
		for (int i = 0; i < numberOfFogServers; i++) {
			FogDevice fogServer = createFogDevice("fog-server_" + i, 2800, 4000, 10000, 10000, 0.0, 107.339, 83.4333);
			String dataID = locator.getLevelWiseResources(locator.getLevelID("Gateway")).get(i);
            locator.linkDataWithInstance(fogServer.getId(), dataID);
			fogServer.setParentId(cloud.getId());
			fogServer.setUplinkLatency(100);
			fogDevices.add(fogServer);
		}

//        for (int i = 0; i < locator.getLevelWiseResources(locator.getLevelID("Gateway")).size(); i++) {
//
//            FogDevice gateway = createFogDevice("gateway_" + i, 2800, 4000, 10000, 10000, 0.0, 107.339, 83.4333);
//            locator.linkDataWithInstance(gateway.getId(), locator.getLevelWiseResources(locator.getLevelID("Gateway")).get(i));
//            gateway.setParentId(locator.determineParent(gateway.getId(), References.SETUP_TIME));
//            gateway.setUplinkLatency(4);
//            fogDevices.add(gateway);
//        }
	}

	private static FogDevice addMobile(String name, int userId, String appId, int parentId) {
		FogDevice mobile = createFogDevice(name, 500, 20, 1000, 270, 0, 87.53, 82.44);
		mobile.setParentId(parentId);
		Sensor mobileSensor = new Sensor("sensor-" + name, "M-SENSOR", userId, appId, new DeterministicDistribution(SENSOR_TRANSMISSION_TIME));
		sensors.add(mobileSensor);
		Actuator mobileDisplay = new Actuator("actuator-" + name, userId, appId, "M-DISPLAY");
		actuators.add(mobileDisplay);
		mobileSensor.setGatewayDeviceId(mobile.getId());
		mobileSensor.setLatency(6.0);
		mobileDisplay.setGatewayDeviceId(mobile.getId());
		mobileDisplay.setLatency(1.0);
		return mobile;
	}

	private static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw, double ratePerMips, double busyPower, double idlePower) {
		List<Pe> peList = new ArrayList<>();
		peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));
		int hostId = FogUtils.generateEntityId();
		long storage = 1000000;
		int bw = 10000;
		PowerHost host = new PowerHost(
				hostId,
				new RamProvisionerSimple(ram),
				new BwProvisionerOverbooking(bw),
				storage,
				peList,
				new StreamOperatorScheduler(peList),
				new FogLinearPowerModel(busyPower, idlePower)
		);
		List<Host> hostList = new ArrayList<>();
		hostList.add(host);
		String arch = "x86";
		String os = "Linux";
		String vmm = "Xen";
		double time_zone = 10.0;
		double cost = 3.0;
		double costPerMem = 0.05;
		double costPerStorage = 0.001;
		double costPerBw = 0.0;
		LinkedList<Storage> storageList = new LinkedList<>();
		FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
				arch, os, vmm, host, time_zone, cost, costPerMem,
				costPerStorage, costPerBw);
		FogDevice fogdevice = null;
		try {
			fogdevice = new FogDevice(nodeName, characteristics,
					new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return fogdevice;
	}

	private static Application createApplication(String appId, int userId) {
		Application application = Application.createApplication(appId, userId);
		application.addAppModule("clientModule", 10);
		application.addAppModule("processingModule", 10);
		application.addAppModule("storageModule", 10);
		if (SENSOR_TRANSMISSION_TIME == 5.1)
			application.addAppEdge("M-SENSOR", "clientModule", 2000, 500, "M-SENSOR", Tuple.UP, AppEdge.SENSOR);
		else
			application.addAppEdge("M-SENSOR", "clientModule", 3000, 500, "M-SENSOR", Tuple.UP, AppEdge.SENSOR);
		application.addAppEdge("clientModule", "processingModule", 3500, 500, "RAW_DATA", Tuple.UP, AppEdge.MODULE);
		application.addAppEdge("processingModule", "storageModule", 1000, 1000, "PROCESSED_DATA", Tuple.UP, AppEdge.MODULE);
		application.addAppEdge("processingModule", "clientModule", 14, 500, "ACTION_COMMAND", Tuple.DOWN, AppEdge.MODULE);
		application.addAppEdge("clientModule", "M-DISPLAY", 1000, 500, "ACTUATION_SIGNAL", Tuple.DOWN, AppEdge.ACTUATOR);
		application.addTupleMapping("clientModule", "M-SENSOR", "RAW_DATA", new FractionalSelectivity(1.0));
		application.addTupleMapping("processingModule", "RAW_DATA", "PROCESSED_DATA", new FractionalSelectivity(1.0));
		application.addTupleMapping("processingModule", "RAW_DATA", "ACTION_COMMAND", new FractionalSelectivity(1.0));
		application.addTupleMapping("clientModule", "ACTION_COMMAND", "ACTUATION_SIGNAL", new FractionalSelectivity(1.0));
		final AppLoop loop1 = new AppLoop(new ArrayList<String>() {{
			add("M-SENSOR");
			add("clientModule");
			add("processingModule");
			add("clientModule");
			add("M-DISPLAY");
		}});
		List<AppLoop> loops = new ArrayList<AppLoop>() {{
			add(loop1);
		}};
		application.setLoops(loops);
		return application;
	}
}
