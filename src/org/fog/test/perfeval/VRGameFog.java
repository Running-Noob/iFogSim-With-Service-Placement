package org.fog.test.perfeval;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

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
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.*;
import org.fog.utils.distribution.DeterministicDistribution;

/**
 * Simulation setup for case study 1 - EEG Beam Tractor Game
 *
 * @author Harshit Gupta
 */
public class VRGameFog {
    static List<FogDevice> fogDevices = new ArrayList<FogDevice>(); // 雾设备
    static List<Sensor> sensors = new ArrayList<Sensor>(); // 传感器
    static List<Actuator> actuators = new ArrayList<Actuator>(); // 执行器

    static boolean CLOUD = false;

    // 网关的数量
    static int numOfDepts = 2;
    // 每个网关拥有的移动设备的数量
    static int numOfMobilesPerDept = 5;
    static double EEG_TRANSMISSION_TIME = 5;

    public static void main(String[] args) {

        Log.printLine("Starting VRGame...");

        try {
            Log.disable(); // Log 是 CloudSim 里面的日志类
            int num_user = 1;
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false;

            // 这里是初始化CloudSim
            CloudSim.init(num_user, calendar, trace_flag);

            String appId = "vr_game"; // identifier of the application

            Logger.disabled(); // Logger 是 iFogSim 里面的日志类
            // FogBroker的作用类似于WorkflowScheduler、WorkflowPlanner
            FogBroker broker = new FogBroker("broker"); // broker的id是2

            // Application类就是要进行部署的应用程序，该应用程序由一系列应用程序模块组成
            Application application = createApplication(appId, broker.getId());

            // FogDevices就是用来放置应用程序模块的雾设备，最终就是由雾设备来执行应用程序模块的服务的
            createFogDevices(broker.getId(), appId);

            // ModuleMapping用于定义应用程序模块和雾设备之间的映射关系，即服务放置的确定
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping(); // initializing a module mapping

            if (CLOUD) {
                // if the mode of deployment is cloud-based
				/*moduleMapping.addModuleToDevice("connector", "cloud", numOfDepts*numOfMobilesPerDept); // fixing all instances of the Connector module to the Cloud
				moduleMapping.addModuleToDevice("concentration_calculator", "cloud", numOfDepts*numOfMobilesPerDept); // fixing all instances of the Concentration Calculator module to the Cloud*/

                moduleMapping.addModuleToDevice("connector", "cloud"); // fixing all instances of the Connector module to the Cloud
                moduleMapping.addModuleToDevice("concentration_calculator", "cloud"); // fixing all instances of the Concentration Calculator module to the Cloud
                for (FogDevice device : fogDevices) {
                    if (device.getName().startsWith("m")) {
                        //moduleMapping.addModuleToDevice("client", device.getName(), 1);  // fixing all instances of the Client module to the Smartphones
                        moduleMapping.addModuleToDevice("client", device.getName());  // fixing all instances of the Client module to the Smartphones
                    }
                }
            } else {
                // if the mode of deployment is edge-ward
                //moduleMapping.addModuleToDevice("connector", "cloud", numOfDepts*numOfMobilesPerDept); // fixing all instances of the Connector module to the Cloud
                moduleMapping.addModuleToDevice("connector", "cloud"); // fixing all instances of the Connector module to the Cloud
                // 只把 connector 模块放在云端，剩下的模块根据 edge-ward 放置策略来决定放置在哪一级雾设备上
                // rest of the modules will be placed by the Edge-ward placement policy
            }

            // Controller根据应用程序和雾设备之间的放置关系进行执行，并得到执行后的结果
            Controller controller = new Controller("master-controller", fogDevices, sensors, actuators);

            controller.submitApplication(application, 0,
                    (CLOUD) ? (new ModulePlacementMapping(fogDevices, application, moduleMapping))
                            : (new ModulePlacementEdgewards(fogDevices, sensors, actuators, application, moduleMapping)));

            TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());

            CloudSim.startSimulation();

            CloudSim.stopSimulation();

            Log.printLine("VRGame finished!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Unwanted errors happen");
        }
    }

    /**
     * Creates the fog devices in the physical topology of the simulation.
     *
     * @param userId
     * @param appId
     */
    // 创建雾环境，雾环境中的设备具有不同的层级等级
    // 第 0 级：云
    // 第 1 级：代理服务器
    // 第 2 级：网关
    // 第 3 级：移动设备
    private static void createFogDevices(int userId, String appId) {
        FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16 * 103, 16 * 83.25); // creates the fog device Cloud at the apex of the hierarchy with level=0
        cloud.setParentId(-1);
        FogDevice proxy = createFogDevice("proxy-server", 2800, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333); // creates the fog device Proxy Server (level=1)
        proxy.setParentId(cloud.getId()); // setting Cloud as parent of the Proxy Server
        proxy.setUplinkLatency(Parameters.proxyServerToCloudUplinkLatency); // latency of connection from Proxy Server to the Cloud is 100 ms

        fogDevices.add(cloud);
        fogDevices.add(proxy);

        for (int i = 0; i < numOfDepts; i++) {
            addGw(i + "", userId, appId, proxy.getId()); // adding a fog device for every Gateway in physical topology. The parent of each gateway is the Proxy Server
        }
    }

    private static FogDevice addGw(String id, int userId, String appId, int parentId) {
        FogDevice dept = createFogDevice("d-" + id, 2800, 4000, 10000, 10000, 2, 0.0, 107.339, 83.4333);
        fogDevices.add(dept);
        dept.setParentId(parentId);
        dept.setUplinkLatency(Parameters.deptToproxyServerUplinkLatency); // latency of connection between gateways and proxy server is 4 ms
        for (int i = 0; i < numOfMobilesPerDept; i++) {
            String mobileId = id + "-" + i;
            FogDevice mobile = addMobile(mobileId, userId, appId, dept.getId()); // adding mobiles to the physical topology. Smartphones have been modeled as fog devices as well.
            mobile.setUplinkLatency(Parameters.mobileToDeptUplinkLatency); // latency of connection between the smartphone and gateway is 2 ms
            fogDevices.add(mobile);
        }
        return dept;
    }

    private static FogDevice addMobile(String id, int userId, String appId, int parentId) {
        FogDevice mobile = createFogDevice("m-" + id, 1000, 1000, 10000, 270, 3, 0, 87.53, 82.44);
        mobile.setParentId(parentId);
        Sensor eegSensor = new Sensor("s-" + id, "EEG", userId, appId, new DeterministicDistribution(EEG_TRANSMISSION_TIME)); // inter-transmission time of EEG sensor follows a deterministic distribution
        sensors.add(eegSensor);
        Actuator display = new Actuator("a-" + id, userId, appId, "DISPLAY");
        actuators.add(display);
        eegSensor.setGatewayDeviceId(mobile.getId());
        eegSensor.setLatency(Parameters.sensorToMobileUplinkLatency);  // latency of connection between EEG sensors and the parent Smartphone is 6 ms
        display.setGatewayDeviceId(mobile.getId());
        display.setLatency(Parameters.mobileToActuatorUplinkLatency);  // latency of connection between Display actuator and the parent Smartphone is 1 ms
        return mobile;
    }

    /**
     * Creates a vanilla fog device
     *
     * @param nodeName    name of the device to be used in simulation
     * @param mips        MIPS
     * @param ram         RAM
     * @param upBw        uplink bandwidth
     * @param downBw      downlink bandwidth
     * @param level       hierarchy level of the device
     * @param ratePerMips cost rate per MIPS used
     * @param busyPower
     * @param idlePower
     * @return
     */
    // 创建雾设备，雾设备的作用就类似于datacenter
    private static FogDevice createFogDevice(String nodeName, long mips,
                                             int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower) {

        List<Pe> peList = new ArrayList<Pe>();

        // 3. Create PEs and add these into a list.
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips))); // need to store Pe id and MIPS Rating

        int hostId = FogUtils.generateEntityId();
        long storage = 1000000; // host storage
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

        List<Host> hostList = new ArrayList<Host>();
        hostList.add(host);

        String arch = "x86"; // system architecture
        String os = "Linux"; // operating system
        String vmm = "Xen";
        double time_zone = 10.0; // time zone this resource located
        double cost = 3.0; // the cost of using processing in this resource
        double costPerMem = 0.05; // the cost of using memory in this resource
        double costPerStorage = 0.001; // the cost of using storage in this
        // resource
        double costPerBw = 0.0; // the cost of using bw in this resource
        LinkedList<Storage> storageList = new LinkedList<Storage>(); // we are not adding SAN
        // devices by now

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

        fogdevice.setLevel(level);
        return fogdevice;
    }

    /**
     * Function to create the EEG Tractor Beam game application in the DDF model.
     *
     * @param appId  unique identifier of the application
     * @param userId identifier of the user of the application
     * @return
     */
    // 这个方法创建了一个完整的应用程序，包括App Module、App Edge、App Loop、Tuple等
    @SuppressWarnings({"serial"})
    private static Application createApplication(String appId, int userId) {
        // 这里相当于把应用基本参数初始化，创建了一个应用空壳
        Application application = Application.createApplication(appId, userId); // creates an empty application model (empty directed graph)

        /*
         * Adding modules (vertices) to the application model (directed graph)
         */
        // 创建App模块并将其添加到Application中
        application.addAppModule("client", 10); // adding module Client to the application model
        application.addAppModule("concentration_calculator", 10); // adding module Concentration Calculator to the application model
        application.addAppModule("connector", 10); // adding module Connector to the application model

        /*
         * Connecting the application modules (vertices) in the application model (directed graph) with edges
         */
        // 将App模块之间的关系以边的形式添加到Application中
        if (EEG_TRANSMISSION_TIME == 10)
            application.addAppEdge("EEG", "client", 2000, 500, "EEG", Tuple.UP, AppEdge.SENSOR); // adding edge from EEG (sensor) to Client module carrying tuples of type EEG
        else
            application.addAppEdge("EEG", "client", 3000, 500, "EEG", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("client", "concentration_calculator", 3500, 500, "_SENSOR", Tuple.UP, AppEdge.MODULE); // adding edge from Client to Concentration Calculator module carrying tuples of type _SENSOR
        application.addAppEdge("concentration_calculator", "connector", 100, 1000, 1000, "PLAYER_GAME_STATE", Tuple.UP, AppEdge.MODULE); // adding periodic edge (period=1000ms) from Concentration Calculator to Connector module carrying tuples of type PLAYER_GAME_STATE
        application.addAppEdge("concentration_calculator", "client", 14, 500, "CONCENTRATION", Tuple.DOWN, AppEdge.MODULE);  // adding edge from Concentration Calculator to Client module carrying tuples of type CONCENTRATION
        application.addAppEdge("connector", "client", 100, 28, 1000, "GLOBAL_GAME_STATE", Tuple.DOWN, AppEdge.MODULE); // adding periodic edge (period=1000ms) from Connector to Client module carrying tuples of type GLOBAL_GAME_STATE
        application.addAppEdge("client", "DISPLAY", 1000, 500, "SELF_STATE_UPDATE", Tuple.DOWN, AppEdge.ACTUATOR);  // adding edge from Client module to Display (actuator) carrying tuples of type SELF_STATE_UPDATE
        application.addAppEdge("client", "DISPLAY", 1000, 500, "GLOBAL_STATE_UPDATE", Tuple.DOWN, AppEdge.ACTUATOR);  // adding edge from Client module to Display (actuator) carrying tuples of type GLOBAL_STATE_UPDATE

        /*
         * Defining the input-output relationships (represented by selectivity) of the application modules.
         */
        // 将App模块之间传输的数据关系添加到Application中
        // 通过 FractionalSelectivity 定义数据的选择性传输关系。比如 client 模块每接收到一个 EEG 类型的数据，会发出 0.9 个 _SENSOR 类型的数据。
        application.addTupleMapping("client", "EEG", "_SENSOR", new FractionalSelectivity(0.9)); // 0.9 tuples of type _SENSOR are emitted by Client module per incoming tuple of type EEG
        application.addTupleMapping("client", "CONCENTRATION", "SELF_STATE_UPDATE", new FractionalSelectivity(1.0)); // 1.0 tuples of type SELF_STATE_UPDATE are emitted by Client module per incoming tuple of type CONCENTRATION
        application.addTupleMapping("concentration_calculator", "_SENSOR", "CONCENTRATION", new FractionalSelectivity(1.0)); // 1.0 tuples of type CONCENTRATION are emitted by Concentration Calculator module per incoming tuple of type _SENSOR
        application.addTupleMapping("client", "GLOBAL_GAME_STATE", "GLOBAL_STATE_UPDATE", new FractionalSelectivity(1.0)); // 1.0 tuples of type GLOBAL_STATE_UPDATE are emitted by Client module per incoming tuple of type GLOBAL_GAME_STATE

        /*
         * Defining application loops to monitor the latency of.
         * Here, we add only one loop for monitoring : EEG(sensor) -> Client -> Concentration Calculator -> Client -> DISPLAY (actuator)
         */
        // 将 传感器 -> 应用程序模块 -> 执行器 这一轮循环的 loop 添加到 Application 中，用来监控延迟
        final AppLoop loop1 = new AppLoop(new ArrayList<String>() {{
            add("EEG");
            add("client");
            add("concentration_calculator");
            add("client");
            add("DISPLAY");
        }});
        List<AppLoop> loops = new ArrayList<AppLoop>() {{
            add(loop1);
        }};
        application.setLoops(loops);

        return application;
    }
}