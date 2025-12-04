/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.symphony.dal.infrastructure.management.dataprobe.ibcs;

import static com.avispl.symphony.dal.util.ControllablePropertyFactory.createButton;
import static com.avispl.symphony.dal.util.ControllablePropertyFactory.createSwitch;
import static com.avispl.symphony.dal.util.ControllablePropertyFactory.createText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.DataprobeCommand;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.DataprobeConstant;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.LoginInfo;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.constants.Util;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric.AggregatedInformation;
import javax.security.auth.login.FailedLoginException;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.error.ResourceNotReachableException;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.avispl.symphony.dal.util.StringUtils;

/**
 * /*
 * An implementation of DataprobeIBCSCommunicator to provide communication and interaction with Dataprobe IBCS
 * Supported features are:
 * <p>
 * Monitoring:
 * <li>IPAddress</li>
 * <li>LastContact</li>
 * <li>Location</li>
 * <li>MACAddress</li>
 * <li>ManageLink</li>
 * <li>TriggerInfo_[TriggerName]</li>
 *
 * <ul>Outlet_[index]</ul>
 * <li>Name</li>
 * <li>Control</li>
 * <li>Cycle</li>
 * <li>Status</li>
 * <p>
 * <ul>Configuration group</ul>
 * <li></li>
 * <li></li>
 *
 * @author Harry / Symphony Dev Team<br>
 * @since 1.0.0
 */
public class DataprobeIBCSCommunicator extends RestCommunicator implements Aggregator, Monitorable, Controller {
	/**
	 * ReentrantLock to prevent telnet session is closed when adapter is retrieving statistics from the device.
	 */
	private final ReentrantLock reentrantLock = new ReentrantLock();

	/**
	 * Store previous/current ExtendedStatistics
	 */
	private ExtendedStatistics localExtendedStatistics;

	/**
	 * Cached data
	 */
	private final Map<String, Map<String, String>> cachedMonitoringDevice = Collections.synchronizedMap(new HashMap<>());

	/**
	 * List of aggregated device
	 */
	private final List<AggregatedDevice> aggregatedDeviceList = Collections.synchronizedList(new ArrayList<>());

	/**
	 * How much time last monitoring cycle took to finish
	 */
	private Long lastMonitoringCycleDuration;

	/**
	 * Adapter metadata properties - adapter version and build date
	 */
	private Properties adapterProperties;

	/**
	 * isEmergencyDelivery to check if control flow is trigger
	 */
	private boolean isEmergencyDelivery;

	/**
	 * A mapper for reading and writing JSON using Jackson library.
	 * ObjectMapper provides functionality for converting between Java objects and JSON.
	 * It can be used to serialize objects to JSON format, and deserialize JSON data to objects.
	 */
	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * the login info
	 */
	private LoginInfo loginInfo;

	/**
	 * Executor that runs all the async operations, that is posting and
	 */
	private ExecutorService executorService;

	/**
	 * Device adapter instantiation timestamp.
	 */
	private long adapterInitializationTimestamp;

	/**
	 * Indicates whether a device is considered as paused.
	 * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
	 * collection unless the {@link DataprobeIBCSCommunicator#retrieveMultipleStatistics()} method is called which will change it
	 * to a correct value
	 */
	private volatile boolean devicePaused = true;

	/**
	 * We don't want the statistics to be collected constantly, because if there's not a big list of devices -
	 * new devices' statistics loop will be launched before the next monitoring iteration. To avoid that -
	 * this variable stores a timestamp which validates it, so when the devices' statistics is done collecting, variable
	 * is set to currentTime + 30s, at the same time, calling {@link #retrieveMultipleStatistics()} and updating the
	 */
	private long nextDevicesCollectionIterationTimestamp;

	/**
	 * This parameter holds timestamp of when we need to stop performing API calls
	 * It used when device stop retrieving statistic. Updated each time of called #retrieveMultipleStatistics
	 */
	private volatile long validRetrieveStatisticsTimestamp;

	/**
	 * Aggregator inactivity timeout. If the {@link DataprobeIBCSCommunicator#retrieveMultipleStatistics()}  method is not
	 * called during this period of time - device is considered to be paused, thus the Cloud API
	 * is not supposed to be called
	 */
	private static final long retrieveStatisticsTimeOut = 3 * 60 * 1000;

	/**
	 * A private field that represents an instance of the YealinkCloudLoader class, which is responsible for loading device data for YealinkCloud
	 */
	private DataprobeIBCSCloudDataLoader deviceDataLoader;

	/**
	 * Update the status of the device.
	 * The device is considered as paused if did not receive any retrieveMultipleStatistics()
	 * calls during {@link DataprobeIBCSCommunicator}
	 */
	private synchronized void updateAggregatorStatus() {
		devicePaused = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
	}

	/**
	 * Uptime time stamp to valid one
	 */
	private synchronized void updateValidRetrieveStatisticsTimestamp() {
		validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
		updateAggregatorStatus();
	}

	class DataprobeIBCSCloudDataLoader implements Runnable {
		private volatile boolean inProgress;
		private volatile boolean dataFetchCompleted = false;

		public DataprobeIBCSCloudDataLoader() {
			inProgress = true;
		}

		@Override
		public void run() {
			loop:
			while (inProgress) {
				long startCycle = System.currentTimeMillis();
				try {
					try {
						TimeUnit.MILLISECONDS.sleep(500);
					} catch (InterruptedException e) {
						logger.info(String.format("Sleep for 0.5 second was interrupted with error message: %s", e.getMessage()));
					}

					if (!inProgress) {
						break loop;
					}

					updateAggregatorStatus();
					if (devicePaused) {
						continue loop;
					}
					if (logger.isDebugEnabled()) {
						logger.debug("Fetching other than aggregated device list");
					}

					long currentTimestamp = System.currentTimeMillis();
					if (!dataFetchCompleted && nextDevicesCollectionIterationTimestamp <= currentTimestamp) {
						populateListDevice();
						dataFetchCompleted = true;
					}

					while (nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
						try {
							TimeUnit.MILLISECONDS.sleep(1000);
						} catch (InterruptedException e) {
							logger.info(String.format("Sleep for 1 second was interrupted with error message: %s", e.getMessage()));
						}
					}

					if (!inProgress) {
						break loop;
					}
					nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + 30000;
					lastMonitoringCycleDuration = (System.currentTimeMillis() - startCycle) / 1000;
					logger.debug("Finished collecting devices statistics cycle at " + new Date() + ", total duration: " + lastMonitoringCycleDuration);

					if (logger.isDebugEnabled()) {
						logger.debug("Finished collecting devices statistics cycle at " + new Date());
					}
				} catch (Exception e) {
					logger.error("Unexpected error occurred during main device collection cycle", e);
				}
			}
			logger.debug("Main device collection loop is completed, in progress marker: " + inProgress);
			// Finished collecting
		}

		/**
		 * Triggers main loop to stop
		 */
		public void stop() {
			inProgress = false;
		}
	}

	/*--------- <Configuration properties> ---------*/

	/**
	 * Enable/disable controllable properties on aggregated devices
	 * */
	private boolean configManagement = false;

	/**
	 * Retrieves {@link #configManagement}
	 *
	 * @return value of {@link #configManagement}
	 */
	public boolean isConfigManagement() {
		return configManagement;
	}

	/**
	 * Sets {@link #configManagement} value
	 *
	 * @param configManagement new value of {@link #configManagement}
	 */
	public void setConfigManagement(boolean configManagement) {
		this.configManagement = configManagement;
	}

	/*--------- </Configuration properties> ---------*/

	/**
	 * Constructs a new instance of DataprobeIBCSCommunicator.
	 */
	public DataprobeIBCSCommunicator() throws IOException {
		adapterProperties = new Properties();
		setBaseUri(DataprobeConstant.BASE_URL);
		adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
		this.setTrustAllCertificates(true);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void controlProperty(ControllableProperty cp) {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void controlProperties(List<ControllableProperty> controllableProperties) {
		if (CollectionUtils.isEmpty(controllableProperties)) {
			throw new IllegalArgumentException("ControllableProperties can not be null or empty");
		}
		for (ControllableProperty p : controllableProperties) {
			try {
				controlProperty(p);
			} catch (Exception e) {
				logger.error(String.format("Error when control property %s", p.getProperty()), e);
			}
		}
	}

	/**
	 * Authenticates the user by sending a login request and retrieves the token.
	 *
	 * @throws Exception if an error occurs during the login process.
	 * {@inheritDoc}
	 */
	@Override
	protected void authenticate() throws Exception {
		String jsonPayload = Util.authBody(this.getLogin(), this.getPassword(), DataprobeConstant.TIMEOUT_INTERVAL, DataprobeConstant.TIMEOUT_SCALE, null);
		try {
			String result = this.doPost(DataprobeCommand.API_LOGIN, jsonPayload);
			JsonNode response = objectMapper.readTree(result);

			String success = response.path(DataprobeConstant.SUCCESS).asText();
			if (DataprobeConstant.TRUE.equals(success)) {
				if (response.at(DataprobeConstant.RESPONSE_SUCCESS).asBoolean()) {
					String token = response.at("/token").asText();
					if (loginInfo == null) {
						loginInfo = new LoginInfo();
					}
					loginInfo.setToken(token);
				} else {
					loginInfo = null;
				}
			} else {
				throw new FailedLoginException("Cloud Service authentication failed, please check username and password.");
			}
		}catch (FailedLoginException e) {
			throw e;
		}catch (Exception e) {
			throw new ResourceNotReachableException("Unable to retrieve API token. " + e);
		}
	}

	@Override
	public List<AggregatedDevice> retrieveMultipleStatistics() {
		if (executorService == null) {
			executorService = Executors.newFixedThreadPool(1);
			executorService.submit(deviceDataLoader = new DataprobeIBCSCloudDataLoader());
		}
		nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();
		updateValidRetrieveStatisticsTimestamp();
		if (cachedMonitoringDevice.isEmpty()) {
			return Collections.emptyList();
		}
		return cloneAndPopulateAggregatedDeviceList();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<AggregatedDevice> retrieveMultipleStatistics(List<String> list) {
		return retrieveMultipleStatistics()
				.stream()
				.filter(aggregatedDevice -> list.contains(aggregatedDevice.getDeviceId()))
				.collect(Collectors.toList());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<Statistics> getMultipleStatistics() throws Exception {
		reentrantLock.lock();
		try {
			if (loginInfo == null) {
				loginInfo = new LoginInfo();
			}
			checkValidApiToken();
			Map<String, String> stats = new HashMap<>();
			Map<String, String> dynamicStatistics = new HashMap<>();
			ExtendedStatistics extendedStatistics = new ExtendedStatistics();

			if (!isEmergencyDelivery) {
				retrieveMetadata(stats, dynamicStatistics);

				extendedStatistics.setStatistics(stats);
				extendedStatistics.setDynamicStatistics(dynamicStatistics);
				localExtendedStatistics = extendedStatistics;
			}
			isEmergencyDelivery = false;
		} finally {
			reentrantLock.unlock();
		}
		return Collections.singletonList(localExtendedStatistics);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void internalInit() throws Exception {
		if (logger.isDebugEnabled()) {
			logger.debug("Internal init is called.");
		}
		adapterInitializationTimestamp = System.currentTimeMillis();
		executorService = Executors.newFixedThreadPool(1);
		executorService.submit(deviceDataLoader = new DataprobeIBCSCloudDataLoader());
		super.internalInit();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void internalDestroy() {
		if (logger.isDebugEnabled()) {
			logger.debug("Internal destroy is called.");
		}
		if (deviceDataLoader != null) {
			deviceDataLoader.stop();
			deviceDataLoader = null;
		}
		if (executorService != null) {
			executorService.shutdownNow();
			executorService = null;
		}
		if (localExtendedStatistics != null && localExtendedStatistics.getStatistics() != null && localExtendedStatistics.getControllableProperties() != null) {
			localExtendedStatistics.getStatistics().clear();
			localExtendedStatistics.getControllableProperties().clear();
		}
		loginInfo = null;
		nextDevicesCollectionIterationTimestamp = 0;
		aggregatedDeviceList.clear();
		cachedMonitoringDevice.clear();
		super.internalDestroy();
	}

	/**
	 * Check API token validation
	 * If the token expires, we send a request to get a new token
	 */
	private void checkValidApiToken() throws Exception {
		if (StringUtils.isNullOrEmpty(this.getLogin()) || StringUtils.isNullOrEmpty(this.getPassword())) {
			throw new FailedLoginException("Username or Password field is empty. Please check device credentials");
		}
		if (this.loginInfo.isTimeout() || this.loginInfo.getToken() == null) {
			authenticate();
		}
	}

	/**
	 * Populates aggregated device list by making a POST request to retrieve information from iBCS.
	 * The method clears the existing aggregated device list, processes the response, and updates the list accordingly.
	 * Any error during the process is logged.
	 */
	private void populateListDevice() {
		try {
			String jsonPayload = Util.requestBody(loginInfo.getToken(), null, null, null);
			String result = this.doPost(DataprobeCommand.RETRIEVE_INFO, jsonPayload);
			JsonNode listResponse = objectMapper.readTree(result);
			if(listResponse.has(DataprobeConstant.DEVICES) && !listResponse.get(DataprobeConstant.DEVICES).isEmpty()){
				JsonNode data = listResponse.path(DataprobeConstant.DEVICES);
				if (data == null || !data.isArray() || data.isEmpty()) {
					return;
				}
				synchronized (cachedMonitoringDevice) {
					cachedMonitoringDevice.clear();
				}

				for (JsonNode node : data) {
					String deviceMACAddress = node.get("mac").asText("");
					if (deviceMACAddress.isEmpty()) continue;
					Map<String, String> mappingValue = new HashMap<>();

					for (AggregatedInformation info : AggregatedInformation.values()) {
						String field = info.getField();
						if (!DataprobeConstant.EMPTY.equals(field) && node.has(field)) {
							mappingValue.put(info.getName(), node.path(field).asText(DataprobeConstant.NOT_AVAILABLE));
						}
					}
					parseStatusAndTrigger(node, mappingValue);
					putMapIntoCachedData(deviceMACAddress, mappingValue);
				}
			}
		} catch (Exception e) {
			throw new ResourceNotReachableException("Unable to retrieve names from response.", e);
		}
	}

	/**
	 * Clones and populates a new list of aggregated devices with mapped monitoring properties.
	 *
	 * @return A new list of {@link AggregatedDevice} objects with mapped monitoring properties.
	 */
	private List<AggregatedDevice> cloneAndPopulateAggregatedDeviceList() {
		List<AggregatedDevice> devices = new ArrayList<>();
		synchronized (cachedMonitoringDevice) {
			for (Map.Entry<String, Map<String, String>> entry : cachedMonitoringDevice.entrySet()) {
				String deviceMAC = entry.getKey();
				Map<String, String> cachedData = entry.getValue();
				AggregatedDevice aggregatedDevice = buildAggregatedDevice(deviceMAC, cachedData);
				devices.add(aggregatedDevice);
			}
		}
		synchronized (aggregatedDeviceList) {
			aggregatedDeviceList.clear();
			aggregatedDeviceList.addAll(devices);
			return new ArrayList<>(aggregatedDeviceList);
		}
	}

	/**
	 * Builds an {@link AggregatedDevice} instance from cached monitoring data for the given device.
	 *
	 * @param deviceMAC  the MAC address of the device
	 * @param cachedData the cached monitoring data for the device
	 * @return a populated {@link AggregatedDevice} with statistics and controllable properties
	 */
	private AggregatedDevice buildAggregatedDevice(String deviceMAC, Map<String, String> cachedData) {
		AggregatedDevice aggregatedDevice = new AggregatedDevice();

		String deviceStatus = cachedData.get(AggregatedInformation.IS_DEVICE_ONLINE.getName());
		String deviceName   = cachedData.get(AggregatedInformation.NAME.getName());
		String deviceModel  = cachedData.get(AggregatedInformation.MODEL.getName());

		aggregatedDevice.setDeviceId(deviceMAC);
		aggregatedDevice.setDeviceName(deviceName);
		aggregatedDevice.setDeviceOnline(DataprobeConstant.TRUE.equalsIgnoreCase(deviceStatus));
		aggregatedDevice.setDeviceModel(deviceModel);

		Map<String, String> stats = new HashMap<>();
		List<AdvancedControllableProperty> controls = new ArrayList<>();

		mapMonitorProperty(cachedData, stats, deviceMAC);
		mapOutletGroup(cachedData, stats, controls);

		aggregatedDevice.setProperties(stats);
		aggregatedDevice.setTimestamp(System.currentTimeMillis());
		aggregatedDevice.setDynamicStatistics(Collections.emptyMap());

		if (!configManagement) {
			controls.add(createText(DataprobeConstant.EMPTY, DataprobeConstant.EMPTY));
		}
		aggregatedDevice.setControllableProperties(controls);

		return aggregatedDevice;
	}

	/**
	 * Maps outlet status and trigger information from cached monitoring data
	 * into statistics and advanced controllable properties.
	 *
	 * @param cachedData the cached monitoring data for a device
	 * @param stats      the statistics map to populate
	 * @param controls   the list of advanced controllable properties to populate
	 */
	private void mapOutletGroup(Map<String, String> cachedData, Map<String, String> stats, List<AdvancedControllableProperty> controls) {
		if (cachedData == null || cachedData.isEmpty()) {
			return;
		}
		for (Map.Entry<String, String> entry : cachedData.entrySet()) {
			String key = entry.getKey();

			if (!key.endsWith(DataprobeConstant.HASH + DataprobeConstant.STATUS)) {
				continue;
			}

			String groupName = key.substring(0, key.indexOf(DataprobeConstant.HASH + DataprobeConstant.STATUS));
			String statusValue = entry.getValue().toLowerCase();

			String nameKey = groupName + "#Name";
			String realName = cachedData.get(nameKey);
			if (realName == null || realName.isEmpty()) {
				realName = groupName;
			}

			stats.put(groupName + "#Name", realName);
			stats.put(groupName + DataprobeConstant.HASH + DataprobeConstant.STATUS, Util.getDefaultValueForNullData(statusValue));

			if ("Inactive".equalsIgnoreCase(Util.getDefaultValueForNullData(statusValue))) {
				continue;
			}

			boolean isOn = DataprobeConstant.ON.equalsIgnoreCase(Util.getDefaultValueForNullData(statusValue));

			String controlKey = groupName + "#Control";
			String cycleKey   = groupName + "#Cycle";

			Util.addAdvancedControlProperties(controls, stats, createSwitch(controlKey, isOn ? 1 : 0), isOn ? "1" : "0" );
			Util.addAdvancedControlProperties(controls, stats, createButton(cycleKey, DataprobeConstant.CYCLE, DataprobeConstant.CYCLING, 0L), DataprobeConstant.NONE );
		}

		cachedData.forEach((k, v) -> {
			if (k.startsWith(DataprobeConstant.TRIGGER_INFO_GROUP)) {
				stats.put(k, Util.getDefaultValueForNullData(v));
			}
		});
	}

	/**
	 * Maps monitoring properties from cached values to statistics and advanced control properties.
	 *
	 * @param cachedValue The cached values map containing raw monitoring data.
	 * @param stats The statistics map to store mapped monitoring properties.
	 * @param deviceMAC The MAC address of device.
	 */
	private void mapMonitorProperty(Map<String, String> cachedValue, Map<String, String> stats, String deviceMAC) {
		if (cachedValue == null || cachedValue.isEmpty()) {
			return;
		}
			for (AggregatedInformation item : AggregatedInformation.values()) {
				String name = item.getGroup() + item.getName();
				switch (item) {
					case NAME:
					case MODEL:
					case IS_DEVICE_ONLINE:
						continue;
					case MANAGE_LINK:
						stats.put(name, retrieveManageLink(deviceMAC));
						break;
					default:
						String value = cachedValue.get(name);
						stats.put(name, Util.getDefaultValueForNullData(value));
						break;
				}
			}
	}

	/**
	 * Retrieves metadata information and updates the provided statistics and dynamic map.
	 *
	 * @param stats the map where statistics will be stored
	 * @param dynamicStatistics the map where dynamic statistics will be stored
	 */
	private void retrieveMetadata(Map<String, String> stats, Map<String, String> dynamicStatistics) {
		try {
			if (lastMonitoringCycleDuration != null) {
				dynamicStatistics.put(DataprobeConstant.MONITORING_CYCLE_DURATION, String.valueOf(lastMonitoringCycleDuration));
			}

			stats.put(DataprobeConstant.ADAPTER_VERSION,
					Util.getDefaultValueForNullData(adapterProperties.getProperty("aggregator.version")));
			stats.put(DataprobeConstant.ADAPTER_BUILD_DATE,
					Util.getDefaultValueForNullData(adapterProperties.getProperty("aggregator.build.date")));
			long adapterUptime = System.currentTimeMillis() - adapterInitializationTimestamp;
			stats.put(DataprobeConstant.ADAPTER_UPTIME_MIN, String.valueOf(adapterUptime / (1000 * 60)));
			stats.put(DataprobeConstant.ADAPTER_UPTIME, Util.normalizeUptime(adapterUptime / 1000));
			dynamicStatistics.put(DataprobeConstant.MONITORED_DEVICES_TOTAL, String.valueOf(aggregatedDeviceList.size()));
		} catch (Exception e) {
			logger.error("Failed to populate metadata information", e);
		}
	}

	/**
	 * Retrieves the management URL for the specified device from the remote API.
	 *
	 * @param deviceMAC the MAC address of the device
	 * @return the management link returned in the API response
	 * @throws ResourceNotReachableException if the manage link cannot be retrieved
	 */
	private String retrieveManageLink(String deviceMAC){
		try{
			String jsonPayload = String.format(DataprobeConstant.RETRIEVE_MANAGE_LINK, loginInfo.getToken(), deviceMAC);
			String result = this.doPost(DataprobeCommand.RETRIEVE_MANAGE_LINK, jsonPayload);
			JsonNode listResponse = objectMapper.readTree(result);
			return listResponse.get("message").asText();
		}catch (Exception e){
			throw new ResourceNotReachableException("Unable to retrieve device management link", e);
		}
	}

	/**
	 * Parses status and trigger information from the given device JSON node
	 * and flattens the values into the provided mapping.
	 *
	 * @param deviceNode   the JSON node containing device data
	 * @param mappingValue the map to populate with parsed status and trigger entries
	 */
	private void parseStatusAndTrigger(JsonNode deviceNode, Map<String, String> mappingValue) {
		parseStatus(deviceNode.path("status"), mappingValue);
		parseTriggerInfo(deviceNode.path("triggerInfo"), mappingValue);
	}

	/**
	 * Parses outlet status information from the given JSON node and flattens it
	 * into the provided mapping using indexed outlet group keys.
	 *
	 * @param statusNode   the JSON node containing outlet status data
	 * @param mappingValue the map to populate with outlet status and name entries
	 */
	private void parseStatus(JsonNode statusNode, Map<String, String> mappingValue) {
		if (statusNode == null || statusNode.isNull()) {
			return;
		}

		if (statusNode.isArray()) {
			// iBoot-PDU: array
			for (int i = 0; i < statusNode.size(); i++) {
				JsonNode item = statusNode.get(i);
				int outletIndex = i + 1;
				item.fields().forEachRemaining(entry -> {
					String outletName = entry.getKey();
					if (outletName == null || outletName.isEmpty()) {
						return;
					}
					String groupOutletName = DataprobeConstant.OUTLET_GROUP + outletIndex;
					String state = entry.getValue().asText(DataprobeConstant.EMPTY);
					mappingValue.put(groupOutletName + "#Status", state);
					mappingValue.put(groupOutletName + "#Name", outletName);
				});
			}
		} else if (statusNode.isObject()) {
			// iBoot-G2: object
			final int[] index = {1};
			statusNode.fields().forEachRemaining(entry -> {
				String outletName = entry.getKey();
				if (outletName == null || outletName.isEmpty()) {
					return;
				}
				String groupOutletName = DataprobeConstant.OUTLET_GROUP + index[0];
				String state = entry.getValue().asText(DataprobeConstant.EMPTY);
				mappingValue.put(groupOutletName + "#Status", state);
				mappingValue.put(groupOutletName + "#Name", outletName);
				index[0]++;
			});
		} else {
			mappingValue.put("RawStatus", statusNode.toString());
		}
	}

	/**
	 * Parses trigger information from the given JSON node and flattens it
	 * into the provided mapping using the trigger info group prefix.
	 *
	 * @param triggerNode  the JSON node containing trigger information
	 * @param mappingValue the map to populate with trigger key/value entries
	 */
	private void parseTriggerInfo(JsonNode triggerNode, Map<String, String> mappingValue) {
		if (triggerNode == null || triggerNode.isNull()) {
			return;
		}

		if (triggerNode.isObject()) {
			triggerNode.fields().forEachRemaining(entry -> {
				String key = entry.getKey();
				String value = entry.getValue().asText(DataprobeConstant.EMPTY);
				mappingValue.put(DataprobeConstant.TRIGGER_INFO_GROUP + key, value);
			});
		} else if (triggerNode.isArray()) {
			for (JsonNode item : triggerNode) {
				item.fields().forEachRemaining(entry -> {
					String key = entry.getKey();
					String value = entry.getValue().asText(DataprobeConstant.EMPTY);
					mappingValue.put(DataprobeConstant.TRIGGER_INFO_GROUP + key, value);
				});
			}
		}
	}

	/**
	 * Puts the provided mapping values into the cached monitoring data for the specified device ID.
	 *
	 * @param deviceMAC The MAC address of the device.
	 * @param mappingValue The mapping values to be added.
	 */
	private void putMapIntoCachedData(String deviceMAC, Map<String, String> mappingValue) {
		synchronized (cachedMonitoringDevice) {
			Map<String, String> map = new HashMap<>();
			if (cachedMonitoringDevice.get(deviceMAC) != null) {
				map = cachedMonitoringDevice.get(deviceMAC);
			}
			map.putAll(mappingValue);
			cachedMonitoringDevice.put(deviceMAC, map);
		}
	}
}