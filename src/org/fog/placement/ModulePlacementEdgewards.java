package org.fog.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.util.Pair;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.application.AppEdge;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.application.selectivity.SelectivityModel;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.utils.Logger;

public class ModulePlacementEdgewards extends ModulePlacement {

    protected ModuleMapping moduleMapping;
    protected List<Sensor> sensors;
    protected List<Actuator> actuators;

    // Map<雾设备的id, 雾设备当前已经占用的CPU负载>
    protected Map<Integer, Double> currentCpuLoad;
    // Map<雾设备的id, 放置在该雾设备上的应用程序模块集合>
    protected Map<Integer, List<String>> currentModuleMap;
    // Map<雾设备的id, Map<放置在该雾设备上的应用程序模块, 放置在该雾设备上的应用程序模块所占用的雾设备的CPU资源>>
    protected Map<Integer, Map<String, Double>> currentModuleLoadMap;
    // Map<雾设备的id, Map<放置在该雾设备上的应用程序模块, 放置在该雾设备上的应用程序模块的实例个数>>
    protected Map<Integer, Map<String, Integer>> currentModuleInstanceNum;

    public ModulePlacementEdgewards(List<FogDevice> fogDevices, List<Sensor> sensors, List<Actuator> actuators,
                                    Application application, ModuleMapping moduleMapping) {
        this.setFogDevices(fogDevices);
        this.setApplication(application);
        this.setModuleMapping(moduleMapping);
        this.setModuleToDeviceMap(new HashMap<String, List<Integer>>());
        this.setDeviceToModuleMap(new HashMap<Integer, List<AppModule>>());
        setSensors(sensors);
        setActuators(actuators);
        setCurrentCpuLoad(new HashMap<Integer, Double>());
        setCurrentModuleMap(new HashMap<Integer, List<String>>());
        setCurrentModuleLoadMap(new HashMap<Integer, Map<String, Double>>());
        setCurrentModuleInstanceNum(new HashMap<Integer, Map<String, Integer>>());
        for (FogDevice dev : getFogDevices()) {
            currentCpuLoad.put(dev.getId(), 0.0);
            currentModuleLoadMap.put(dev.getId(), new HashMap<String, Double>());
            currentModuleMap.put(dev.getId(), new ArrayList<String>());
            currentModuleInstanceNum.put(dev.getId(), new HashMap<String, Integer>());
        }

        mapModules();
        setModuleInstanceCountMap(currentModuleInstanceNum);
    }

    @Override
    protected void mapModules() {
        // 根据ModuleMapping中已经设置好的映射关系进行初始化
        for (String deviceName : getModuleMapping().getModuleMapping().keySet()) {
            // 第一个getModuleMapping是得到ModuleMapping实例，第二个getModuleMapping是得到ModuleMapping实例中的moduleMapping属性
            for (String moduleName : getModuleMapping().getModuleMapping().get(deviceName)) {
                int deviceId = CloudSim.getEntityId(deviceName);
                currentModuleMap.get(deviceId).add(moduleName);
                currentModuleLoadMap.get(deviceId).put(moduleName, 0.0);
                currentModuleInstanceNum.get(deviceId).put(moduleName, 0);
            }
        }

        // 得到雾设备层级中从每个叶子节点到根的所有路径
        // 例如, 从 m-0-0 到 d-0 到 proxy-server 到 cloud，其中一条路径就是 6 - 5 - 4 - 3
        //      从 m-0-1 到 d-0 到 proxy-server 到 cloud，其中一条路径就是 9 - 5 - 4 - 3 （7 是 s-0-0, 8 是 a-0-0）
        List<List<Integer>> leafToRootPaths = getLeafToRootPaths();

        for (List<Integer> path : leafToRootPaths) {
            placeModulesInPath(path);
        }

        for (int deviceId : currentModuleMap.keySet()) {
            for (String module : currentModuleMap.get(deviceId)) {
                createModuleInstanceOnDevice(getApplication().getModuleByName(module), getFogDeviceById(deviceId));
            }
        }
    }

    /**
     * Get the list of modules that are ready to be placed
     *
     * @param placedModules Modules that have already been placed in current path
     * @return list of modules ready to be placed
     */
    private List<String> getModulesToPlace(List<String> placedModules) {
        Application app = getApplication();
        List<String> modulesToPlace_1 = new ArrayList<String>(); // 还未放置的模块
        List<String> modulesToPlace = new ArrayList<String>(); // 确定能放置的模块
        for (AppModule module : app.getModules()) {
            if (!placedModules.contains(module.getName())) {
                modulesToPlace_1.add(module.getName());
            }
        }
        /*
         * Filtering based on whether modules (to be placed) lower in physical topology are already placed
         */
        for (String moduleName : modulesToPlace_1) {
            boolean toBePlaced = true;

            for (AppEdge edge : app.getEdges()) {
                // 如果边的数据流向是down, 说明在源模块放置之前, 目标模块就需要已经被放置了,
                // 如果目标模块没有被放置(!placedModules.contains(edge.getDestination())), 那源模块就不能被放置
                // edge.getSource().equals(moduleName) 就说明当前要处理的模块是某条应用程序边中的源模块
                if (edge.getSource().equals(moduleName) && edge.getDirection() == Tuple.DOWN && !placedModules.contains(edge.getDestination()))
                    toBePlaced = false;
                // 如果边的数据流向是up, 说明在目标模块放置之前, 源模块就需要已经被放置了,
                // 如果源模块没有被放置(!placedModules.contains(edge.getSource())), 那目标模块就不能被放置
                // edge.getDestination().equals(moduleName) 就说明当前要处理的模块是某条应用程序边中的目标模块
                if (edge.getDestination().equals(moduleName) && edge.getDirection() == Tuple.UP && !placedModules.contains(edge.getSource()))
                    toBePlaced = false;
            }
            if (toBePlaced)
                modulesToPlace.add(moduleName);
        }

        return modulesToPlace;
    }

    protected double getRateOfSensor(String sensorType) {
        for (Sensor sensor : getSensors()) {
            if (sensor.getTupleType().equals(sensorType))
                return 1 / sensor.getTransmitDistribution().getMeanInterTransmitTime();
        }
        return 0;
    }

    // 对于某条雾设备的叶子节点到根的路径进行应用程序模块的放置
    private void placeModulesInPath(List<Integer> path) {
        if (path.size() == 0) return;
        List<String> placedModules = new ArrayList<String>(); // 已经放置的应用模块
        // Map<应用程序边, 应用程序边的数据传输速率>
        Map<AppEdge, Double> appEdgeToRate = new HashMap<AppEdge, Double>();

        /**
         * Periodic edges have a fixed periodicity of tuples, so setting the tuple rate beforehand
         */
        for (AppEdge edge : getApplication().getEdges()) {
            if (edge.isPeriodic()) {
                appEdgeToRate.put(edge, 1 / edge.getPeriodicity());
            }
        }

        for (Integer deviceId : path) {
            FogDevice device = getFogDeviceById(deviceId);
            // 得到和当前雾设备直接关联的所有传感器
            Map<String, Integer> sensorsAssociated = getAssociatedSensors(device);
            // 得到和当前雾设备直接关联的所有执行器
            Map<String, Integer> actuatorsAssociated = getAssociatedActuators(device);
            placedModules.addAll(sensorsAssociated.keySet()); // ADDING ALL SENSORS TO PLACED LIST
            placedModules.addAll(actuatorsAssociated.keySet()); // ADDING ALL ACTUATORS TO PLACED LIST

            /*
             * Setting the rates of application edges emanating from sensors
             */
            // 这里有点问题，sensorsAssociated 是 Map<传感器传输的任务类型名称tupleType, 对应的传输该任务类型的传感器个数>
            // AppEdge 的 source 和 destination 表示的是应用程序边的源模块和目标模块名称
            // 不知道为什么这里就把任务类型名称和模块名称做相等的判断了，虽然在创建Sensor时，tupleType和sensorName设置为相同（在VRGameFog例子中）
            for (String sensor : sensorsAssociated.keySet()) {
                for (AppEdge edge : getApplication().getEdges()) {
                    if (edge.getSource().equals(sensor)) {
                        appEdgeToRate.put(edge, sensorsAssociated.get(sensor) * getRateOfSensor(sensor));
                    }
                }
            }

            // 根据已有的信息，计算出每条应用边的数据传输速率
            boolean changed = true;
            while (changed) {        //Loop runs as long as some new information is added
                changed = false;
                Map<AppEdge, Double> rateMap = new HashMap<AppEdge, Double>(appEdgeToRate);
                for (AppEdge edge : rateMap.keySet()) {
                    // 得到应用程序边的目标模块
                    AppModule destModule = getApplication().getModuleByName(edge.getDestination());
                    if (destModule == null) continue;
                    Map<Pair<String, String>, SelectivityModel> selectivityMap = destModule.getSelectivityMap();
                    for (Pair<String, String> pair : selectivityMap.keySet()) {
                        // 这里就是要根据数据的输入输出关系对appEdgeToRate进行填充
                        if (pair.getFirst().equals(edge.getTupleType())) {
                            double outputRate = appEdgeToRate.get(edge) * selectivityMap.get(pair).getMeanRate(); // getting mean rate from SelectivityModel
                            AppEdge outputEdge = getApplication().getEdgeMap().get(pair.getSecond());
                            if (!appEdgeToRate.containsKey(outputEdge) || appEdgeToRate.get(outputEdge) != outputRate) {
                                // if some new information is available
                                changed = true;
                            }
                            appEdgeToRate.put(outputEdge, outputRate);
                        }
                    }
                }
            }

            /*
             * Getting the list of modules ready to be placed on current device on path
             */
            List<String> modulesToPlace = getModulesToPlace(placedModules);

            while (modulesToPlace.size() > 0) { // Loop runs until all modules in modulesToPlace are deployed in the path
                String moduleName = modulesToPlace.get(0);
                double totalCpuLoad = 0;

                // 判断要放置的模块是否已经被放置了，若是，则返回模块所在的设备的id, 否则返回-1
                // 要放置的模块已经被放置的情况：在ModuleMapping类中就已经预设好了
                int upsteamDeviceId = isPlacedUpstream(moduleName, path);
                if (upsteamDeviceId > 0) {
                    if (upsteamDeviceId == deviceId) {
                        placedModules.add(moduleName);
                        modulesToPlace = getModulesToPlace(placedModules);

                        // NOW THE MODULE TO PLACE IS IN THE CURRENT DEVICE. CHECK IF THE NODE CAN SUSTAIN THE MODULE
                        for (AppEdge edge : getApplication().getEdges()) {        // take all incoming edges
                            if (edge.getDestination().equals(moduleName)) {
                                double rate = appEdgeToRate.get(edge);
                                totalCpuLoad += rate * edge.getTupleCpuLength();
                            }
                        }
                        if (totalCpuLoad + currentCpuLoad.get(deviceId) > device.getHost().getTotalMips()) {
                            Logger.debug("ModulePlacementEdgeward", "Need to shift module " + moduleName + " upstream from device " + device.getName());
                            List<String> _placedOperators = shiftModuleNorth(moduleName, totalCpuLoad, deviceId, modulesToPlace);
                            for (String placedOperator : _placedOperators) {
                                if (!placedModules.contains(placedOperator))
                                    placedModules.add(placedOperator);
                            }
                        } else {
                            placedModules.add(moduleName);
                            currentCpuLoad.put(deviceId, currentCpuLoad.get(deviceId) + totalCpuLoad);
                            currentModuleInstanceNum.get(deviceId).put(moduleName, currentModuleInstanceNum.get(deviceId).get(moduleName) + 1);
                            Logger.debug("ModulePlacementEdgeward", "AppModule " + moduleName + " can be created on device " + device.getName());
                        }
                    }
                } else {
                    // 判断当前设备的资源是否足够放置该应用模块
                    for (AppEdge edge : getApplication().getEdges()) {        // take all incoming edges
                        if (edge.getDestination().equals(moduleName)) {
                            double rate = appEdgeToRate.get(edge);
                            totalCpuLoad += rate * edge.getTupleCpuLength(); // 计算流向这个应用模块的数据量大小（数据量=速率*任务大小）
                        }
                    }

                    if (totalCpuLoad + currentCpuLoad.get(deviceId) > device.getHost().getTotalMips()) {
                        Logger.debug("ModulePlacementEdgeward", "Placement of operator " + moduleName + " NOT POSSIBLE on device " + device.getName());
                    } else {
                        Logger.debug("ModulePlacementEdgeward", "Placement of operator " + moduleName + " on device " + device.getName() + " successful.");
                        currentCpuLoad.put(deviceId, totalCpuLoad + currentCpuLoad.get(deviceId));

                        if (!currentModuleMap.containsKey(deviceId))
                            currentModuleMap.put(deviceId, new ArrayList<String>());
                        currentModuleMap.get(deviceId).add(moduleName);
                        placedModules.add(moduleName);
                        modulesToPlace = getModulesToPlace(placedModules);
                        currentModuleLoadMap.get(device.getId()).put(moduleName, totalCpuLoad);

                        int max = 1;
                        for (AppEdge edge : getApplication().getEdges()) {
                            if (edge.getSource().equals(moduleName) && actuatorsAssociated.containsKey(edge.getDestination()))
                                max = Math.max(actuatorsAssociated.get(edge.getDestination()), max);
                            if (edge.getDestination().equals(moduleName) && sensorsAssociated.containsKey(edge.getSource()))
                                max = Math.max(sensorsAssociated.get(edge.getSource()), max);
                        }
                        currentModuleInstanceNum.get(deviceId).put(moduleName, max);
                    }
                }
                modulesToPlace.remove(moduleName);
            }
        }
    }

    /**
     * Shifts a module moduleName from device deviceId northwards. This involves other modules that depend on it to be shifted north as well.
     *
     * @param moduleName
     * @param cpuLoad    cpuLoad of the module
     * @param deviceId
     */
    private List<String> shiftModuleNorth(String moduleName, double cpuLoad, Integer deviceId, List<String> operatorsToPlace) {
        Logger.debug("ModulePlacementEdgeward", CloudSim.getEntityName(deviceId) + " is shifting " + moduleName + " north.");
        List<String> modulesToShift = findModulesToShift(moduleName, deviceId);

        Map<String, Integer> moduleToNumInstances = new HashMap<String, Integer>(); // Map of number of instances of modules that need to be shifted
        double totalCpuLoad = 0;
        Map<String, Double> loadMap = new HashMap<String, Double>();
        for (String module : modulesToShift) {
            loadMap.put(module, currentModuleLoadMap.get(deviceId).get(module));
            moduleToNumInstances.put(module, currentModuleInstanceNum.get(deviceId).get(module) + 1);
            totalCpuLoad += currentModuleLoadMap.get(deviceId).get(module);
            currentModuleLoadMap.get(deviceId).remove(module);
            currentModuleMap.get(deviceId).remove(module);
            currentModuleInstanceNum.get(deviceId).remove(module);
        }

        currentCpuLoad.put(deviceId, currentCpuLoad.get(deviceId) - totalCpuLoad); // change info of current CPU load on device
        loadMap.put(moduleName, loadMap.get(moduleName) + cpuLoad);
        totalCpuLoad += cpuLoad;

        int id = getParentDevice(deviceId);
        while (true) { // Loop iterates over all devices in path upstream from current device. Tries to place modules (to be shifted northwards) on each of them.
            if (id == -1) {
                // Loop has reached the apex fog device in hierarchy, and still could not place modules.
                Logger.debug("ModulePlacementEdgeward", "Could not place modules " + modulesToShift + " northwards.");
                break;
            }
            FogDevice fogDevice = getFogDeviceById(id);
            if (currentCpuLoad.get(id) + totalCpuLoad > fogDevice.getHost().getTotalMips()) {
                // Device cannot take up CPU load of incoming modules. Keep searching for device further north.
                List<String> _modulesToShift = findModulesToShift(modulesToShift, id);    // All modules in _modulesToShift are currently placed on device id
                double cpuLoadShifted = 0;        // the total CPU load shifted from device id to its parent
                for (String module : _modulesToShift) {
                    if (!modulesToShift.contains(module)) {
                        // Add information of all newly added modules (to be shifted)
                        moduleToNumInstances.put(module, currentModuleInstanceNum.get(id).get(module) + moduleToNumInstances.get(module));
                        loadMap.put(module, currentModuleLoadMap.get(id).get(module));
                        cpuLoadShifted += currentModuleLoadMap.get(id).get(module);
                        totalCpuLoad += currentModuleLoadMap.get(id).get(module);
                        // Removing information of all modules (to be shifted north) in device with ID id
                        currentModuleLoadMap.get(id).remove(module);
                        currentModuleMap.get(id).remove(module);
                        currentModuleInstanceNum.get(id).remove(module);
                    }
                }
                currentCpuLoad.put(id, currentCpuLoad.get(id) - cpuLoadShifted); // CPU load on device id gets reduced due to modules shifting northwards

                modulesToShift = _modulesToShift;
                id = getParentDevice(id); // iterating to parent device
            } else {
                // Device (@ id) can accommodate modules. Placing them here.
                double totalLoad = 0;
                for (String module : loadMap.keySet()) {
                    totalLoad += loadMap.get(module);
                    currentModuleLoadMap.get(id).put(module, loadMap.get(module));
                    currentModuleMap.get(id).add(module);
                    String module_ = module;
                    int initialNumInstances = 0;
                    if (currentModuleInstanceNum.get(id).containsKey(module_))
                        initialNumInstances = currentModuleInstanceNum.get(id).get(module_);
                    int finalNumInstances = initialNumInstances + moduleToNumInstances.get(module_);
                    currentModuleInstanceNum.get(id).put(module_, finalNumInstances);
                }
                currentCpuLoad.put(id, totalLoad);
                operatorsToPlace.removeAll(loadMap.keySet());
                List<String> placedOperators = new ArrayList<String>();
                for (String op : loadMap.keySet()) placedOperators.add(op);
                return placedOperators;
            }
        }
        return new ArrayList<String>();
    }

    /**
     * Get all modules that need to be shifted northwards along with <b>module</b>.
     * Typically, these other modules are those that are hosted on device with ID <b>deviceId</b> and lie upstream of <b>module</b> in application model.
     *
     * @param module   the module that needs to be shifted northwards
     * @param deviceId the fog device ID that it is currently on
     * @return list of all modules that need to be shifted north along with <b>module</b>
     */
    private List<String> findModulesToShift(String module, Integer deviceId) {
        List<String> modules = new ArrayList<String>();
        modules.add(module);
        return findModulesToShift(modules, deviceId);
		/*List<String> upstreamModules = new ArrayList<String>();
		upstreamModules.add(module);
		boolean changed = true;
		while(changed){ // Keep loop running as long as new information is added.
			changed = false;
			for(AppEdge edge : getApplication().getEdges()){

				 * If there is an application edge UP from the module to be shifted to another module in the same device

				if(upstreamModules.contains(edge.getSource()) && edge.getDirection()==Tuple.UP &&
						currentModuleMap.get(deviceId).contains(edge.getDestination())
						&& !upstreamModules.contains(edge.getDestination())){
					upstreamModules.add(edge.getDestination());
					changed = true;
				}
			}
		}
		return upstreamModules;	*/
    }

    /**
     * Get all modules that need to be shifted northwards along with <b>modules</b>.
     * Typically, these other modules are those that are hosted on device with ID <b>deviceId</b> and lie upstream of modules in <b>modules</b> in application model.
     *
     * @param module   the module that needs to be shifted northwards
     * @param deviceId the fog device ID that it is currently on
     * @return list of all modules that need to be shifted north along with <b>modules</b>
     */
    private List<String> findModulesToShift(List<String> modules, Integer deviceId) {
        List<String> upstreamModules = new ArrayList<String>();
        upstreamModules.addAll(modules);
        boolean changed = true;
        while (changed) { // Keep loop running as long as new information is added.
            changed = false;
            /*
             * If there is an application edge UP from the module to be shifted to another module in the same device
             */
            for (AppEdge edge : getApplication().getEdges()) {
                if (upstreamModules.contains(edge.getSource()) && edge.getDirection() == Tuple.UP &&
                        currentModuleMap.get(deviceId).contains(edge.getDestination())
                        && !upstreamModules.contains(edge.getDestination())) {
                    upstreamModules.add(edge.getDestination());
                    changed = true;
                }
            }
        }
        return upstreamModules;
    }

    private int isPlacedUpstream(String operatorName, List<Integer> path) {
        for (int deviceId : path) {
            if (currentModuleMap.containsKey(deviceId) && currentModuleMap.get(deviceId).contains(operatorName))
                return deviceId;
        }
        return -1;
    }

    /**
     * Gets all sensors associated with fog-device <b>device</b>
     *
     * @param device
     * @return map from sensor type to number of such sensors
     */
    private Map<String, Integer> getAssociatedSensors(FogDevice device) {
        // Map<传感器传输的任务类型名称tupleType, 对应的传输该任务类型的传感器个数>
        Map<String, Integer> endpoints = new HashMap<String, Integer>();
        for (Sensor sensor : getSensors()) {
            if (sensor.getGatewayDeviceId() == device.getId()) {
                String tupleType = sensor.getTupleType(); // 任务类型名称
                if (!endpoints.containsKey(tupleType)) {
                    endpoints.put(tupleType, 0);
                }
                endpoints.put(tupleType, endpoints.get(tupleType) + 1);
            }
        }
        return endpoints;
    }

    /**
     * Gets all actuators associated with fog-device <b>device</b>
     *
     * @param device
     * @return map from actuator type to number of such sensors
     */
    private Map<String, Integer> getAssociatedActuators(FogDevice device) {
        // Map<传输给执行器的数据类型名称actuatorType, 对应的得到该数据类型的执行器个数>
        Map<String, Integer> endpoints = new HashMap<String, Integer>();
        for (Actuator actuator : getActuators()) {
            if (actuator.getGatewayDeviceId() == device.getId()) {
                String actuatorType = actuator.getActuatorType();
                if (!endpoints.containsKey(actuatorType)) {
                    endpoints.put(actuatorType, 0);
                }
                endpoints.put(actuatorType, endpoints.get(actuatorType) + 1);
            }
        }
        return endpoints;
    }

    @SuppressWarnings("serial")
    protected List<List<Integer>> getPaths(final int fogDeviceId) {
        FogDevice device = (FogDevice) CloudSim.getEntity(fogDeviceId);
        if (device.getChildrenIds().size() == 0) {
            final List<Integer> path = (new ArrayList<Integer>() {{
                add(fogDeviceId);
            }});
            List<List<Integer>> paths = (new ArrayList<List<Integer>>() {{
                add(path);
            }});
            return paths;
        }
        List<List<Integer>> paths = new ArrayList<List<Integer>>();
        for (int childId : device.getChildrenIds()) {
            List<List<Integer>> childPaths = getPaths(childId);
            for (List<Integer> childPath : childPaths)
                childPath.add(fogDeviceId);
            paths.addAll(childPaths);
        }
        return paths;
    }

    protected List<List<Integer>> getLeafToRootPaths() {
        FogDevice cloud = null;
        for (FogDevice device : getFogDevices()) {
            if (device.getName().equals("cloud")) {
                cloud = device;
                break;
            }
        }
        return getPaths(cloud.getId());
    }

    public ModuleMapping getModuleMapping() {
        return moduleMapping;
    }

    public void setModuleMapping(ModuleMapping moduleMapping) {
        this.moduleMapping = moduleMapping;
    }

    public Map<Integer, List<String>> getCurrentModuleMap() {
        return currentModuleMap;
    }

    public void setCurrentModuleMap(Map<Integer, List<String>> currentModuleMap) {
        this.currentModuleMap = currentModuleMap;
    }

    public List<Sensor> getSensors() {
        return sensors;
    }

    public void setSensors(List<Sensor> sensors) {
        this.sensors = sensors;
    }

    public List<Actuator> getActuators() {
        return actuators;
    }

    public void setActuators(List<Actuator> actuators) {
        this.actuators = actuators;
    }

    public Map<Integer, Double> getCurrentCpuLoad() {
        return currentCpuLoad;
    }

    public void setCurrentCpuLoad(Map<Integer, Double> currentCpuLoad) {
        this.currentCpuLoad = currentCpuLoad;
    }

    public Map<Integer, Map<String, Double>> getCurrentModuleLoadMap() {
        return currentModuleLoadMap;
    }

    public void setCurrentModuleLoadMap(
            Map<Integer, Map<String, Double>> currentModuleLoadMap) {
        this.currentModuleLoadMap = currentModuleLoadMap;
    }

    public Map<Integer, Map<String, Integer>> getCurrentModuleInstanceNum() {
        return currentModuleInstanceNum;
    }

    public void setCurrentModuleInstanceNum(
            Map<Integer, Map<String, Integer>> currentModuleInstanceNum) {
        this.currentModuleInstanceNum = currentModuleInstanceNum;
    }
}
