package org.fog.test.perfeval;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.*;
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

public class TestSim {
    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor> sensors = new ArrayList<>();
    static List<Actuator> actuators = new ArrayList<>();
    static Map<Integer, Integer> userMobilityPattern = new HashMap<>();
    static LocationHandler locator;

    static double SENSOR_TRANSMISSION_TIME = 10;
    static int numberOfMobileUser = 1; // 50 smartphones
    static int numberOfProxy = 12;
    static int numberOfGateway = 10;
    static int numberOfCloud = 1;

    static boolean randomMobility_generator = true; // To use random datasets
    static boolean renewDataset = false; // To overwrite existing random datasets

    public static void main(String[] args) {
        Log.printLine("Starting Mobility Test Simulation...");

        try {
            Log.disable();
            int num_user = numberOfMobileUser;
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false;

            CloudSim.init(num_user, calendar, trace_flag);

            String appId = "MobilityTestApp";
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

            MobilityController controller = new MobilityController("mobility-controller", fogDevices, sensors, actuators, locator);
            controller.submitApplication(application, 0, (new ModulePlacementMobileEdgewards(fogDevices, sensors, actuators, application, moduleMapping)));

            TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());

            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            Log.printLine("Mobility Test Simulation finished!");
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

        if (locator.getLevelWiseResources(locator.getLevelID("Cloud")).size() == 1) {

            FogDevice cloud = createFogDevice("cloud", 44800, 16384, 100, 100, 1468, 1332); // creates the fog device Cloud at the apex of the hierarchy with level=0
            cloud.setParentId(References.NOT_SET);
            locator.linkDataWithInstance(cloud.getId(), locator.getLevelWiseResources(locator.getLevelID("Cloud")).get(0));
            fogDevices.add(cloud);

            for (int i = 0; i < locator.getLevelWiseResources(locator.getLevelID("Proxy")).size(); i++) {

                FogDevice proxy = createFogDevice("proxy-server_" + i, 3600, 16384, 10, 20, 428, 333); // creates the fog device Proxy Server (level=1)
                locator.linkDataWithInstance(proxy.getId(), locator.getLevelWiseResources(locator.getLevelID("Proxy")).get(i));
                proxy.setParentId(cloud.getId()); // setting Cloud as parent of the Proxy Server
                proxy.setUplinkLatency(100); // latency of connection from Proxy Server to the Cloud is 100 ms
                fogDevices.add(proxy);

            }

            for (int i = 0; i < locator.getLevelWiseResources(locator.getLevelID("Gateway")).size(); i++) {

                FogDevice gateway = createFogDevice("gateway_" + i, 2800, 8192, 50, 20, 206, 170);
                locator.linkDataWithInstance(gateway.getId(), locator.getLevelWiseResources(locator.getLevelID("Gateway")).get(i));
                gateway.setParentId(locator.determineParent(gateway.getId(), References.SETUP_TIME));
                gateway.setUplinkLatency(4);
                fogDevices.add(gateway);
            }

        }
    }

    private static FogDevice addMobile(String name, int userId, String appId, int parentId) {
        FogDevice mobile = createFogDevice(name, 500, 1024, 100, 200, 60, 35);
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

    private static FogDevice createFogDevice(String nodeName, long mips, int ramMB, long upBw, long downBw, double busyPower, double idlePower) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));
        int hostId = FogUtils.generateEntityId();
        long storage = 1000000;
        int bw = 10000;
        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ramMB),
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
                    new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, 0.01);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return fogdevice;
    }

    private static Application createApplication(String appId, int userId) {
        Application application = Application.createApplication(appId, userId);

        // Module attributes from table
        application.addAppModule("clientModule", 10);
        application.addAppModule("processingModule", 10);
        application.addAppModule("storageModule", 10);

        // Edges and tuple mappings (from table)
        application.addAppEdge("M-SENSOR", "clientModule", 500, 2, "M-SENSOR", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("clientModule", "processingModule", 2500, 2.5, "RAW_DATA", Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("processingModule", "storageModule", 1000, 1, "PROCESSED_DATA", Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("processingModule", "clientModule", 1000, 1.5, "ACTION_COMMAND", Tuple.DOWN, AppEdge.MODULE);
        application.addAppEdge("clientModule", "M-DISPLAY", 500, 2.5, "ACTUATION_SIGNAL", Tuple.DOWN, AppEdge.ACTUATOR);

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