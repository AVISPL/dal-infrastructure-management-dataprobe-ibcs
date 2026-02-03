/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.symphony.dal.infrastructure.management.dataprobe.ibcs;

import com.avispl.symphony.dal.util.ControllablePropertyFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.Serialisers.ControlObject;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.Serialisers.DeviceConfig;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.Serialisers.G2ConfigurationRequest;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.Serialisers.RebootRequest;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.DataprobeCommand;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.DataprobeConstant;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.LoginInfo;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.constants.Util;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric.AggregatedInformation;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric.ConfigurationAdvancedNetwork;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric.ConfigurationAutoping;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric.ConfigurationDevice;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric.ConfigurationNetwork;
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

	/** Set of group filter for {@code displayPropertyGroups}. */
	private static final Set<String> GROUP_FILTERS = Set.of(
			DataprobeConstant.ADVANCED_NETWORK,
			DataprobeConstant.AUTOPING,
			DataprobeConstant.CONFIGURATION,
			DataprobeConstant.OUTLET,
			DataprobeConstant.NETWORK,
			DataprobeConstant.GENERAL
	);

	/**
	 * Pending override state for an outlet within a TTL window.
	 *
	 * @param control  outlet control value
	 * @param status   outlet status label
	 * @param expiryMs expiry timestamp in milliseconds
	 */
	private record PendingOutletState(String control, String status, long expiryMs) {}

	/** Stores temporary outlet overrides after a control action to prevent polling from overwriting recent changes. */
	private final ConcurrentHashMap<String, PendingOutletState> pendingOutletState = new ConcurrentHashMap<>();

	/** TTL for pending outlet overrides after a control request. */
	private static final long OUTLET_PENDING_TTL_MS = 5000;

	/**
	 * Builds a unique key for an outlet within a device.
	 *
	 * @param deviceId the device identifier
	 * @param outletGroupName the outlet group name
	 * @return a stable key used for pending override tracking
	 */
	private static String outletPendingKey(String deviceId, String outletGroupName) {
		return deviceId + "|" + outletGroupName;
	}

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
	private long lastMonitoringCycleDuration;

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

		public DataprobeIBCSCloudDataLoader() {
			inProgress = true;
		}

		@Override
		public void run() {
			loop:
			while (inProgress) {
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

					while (nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
						try {
							TimeUnit.MILLISECONDS.sleep(1000);
						} catch (InterruptedException e) {
							logger.info(String.format("Sleep for 1 second was interrupted with error message: %s", e.getMessage()));
						}
					}
					long startCycle = System.currentTimeMillis();
					try {
						if (logger.isDebugEnabled()) {
							logger.debug("Fetching devices list");
						}
						populateListDevice();
					} catch (Exception e) {
						logger.error("Error occurred during device list retrieval: " + e.getMessage(), e);
					}
					nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + (getMonitoringRate() * 60000L);
					lastMonitoringCycleDuration = Math.max((System.currentTimeMillis() - startCycle) / 1000, 1L);
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

	/** Indicates whether groups are displayed; defaults is General. */
	private final Set<String> displayPropertyGroups = new HashSet<>(Set.of(DataprobeConstant.GENERAL));

	/**
	 * Returns a comma-separated list of property group names that are configured to be displayed.
	 *
	 * @return a comma-separated string of display property group names; may be empty if no groups are configured
	 */
	public String getDisplayPropertyGroups() {
		return this.displayPropertyGroups.stream()
				.sorted()
				.collect(Collectors.joining(DataprobeConstant.COMMA_SPACE));
	}

	/**
	 * Sets the display property groups based on a comma-separated list.
	 * <p>
	 * Trims values automatically. If All is present, SUPPORTED_GROUP_FILTERS are added.
	 * Invalid groups trigger a warning and only the default group applied. {@code null} or empty input is ignored.
	 * </p>
	 *`
	 * @param displayPropertyGroups comma-separated group names; may be {@code null} or empty
	 */
	public void setDisplayPropertyGroups(String displayPropertyGroups) {
		if (StringUtils.isNullOrEmpty(displayPropertyGroups, true)) {
			return;
		}
		Set<String> checkedGroups = Arrays.stream(displayPropertyGroups.split(DataprobeConstant.COMMA))
				.map(String::trim)
				.filter(p -> !p.isEmpty())
				.collect(Collectors.toSet());

		this.displayPropertyGroups.clear();

		if (checkedGroups.contains(DataprobeConstant.ALL)) {
			this.displayPropertyGroups.addAll(GROUP_FILTERS);
			return;
		}

		if (!CollectionUtils.containsAny(GROUP_FILTERS, checkedGroups)) {
			this.logger.warn("No valid display property groups found from input: '%s'".formatted(displayPropertyGroups));
		}
		this.displayPropertyGroups.add(DataprobeConstant.GENERAL);
		checkedGroups.stream().filter(GROUP_FILTERS::contains).forEach(this.displayPropertyGroups::add);
	}

	/**
	 * Stores the unique device types used for filtering results.
	 */
	private final Set<String> deviceTypeFilter = new HashSet<>();

	/**
	 * Retrieves the device type filters as a comma-separated string.
	 * * @return A string containing all active device type filters joined by commas.
	 */
	public String getDeviceTypeFilter() {
		return String.join(DataprobeConstant.COMMA, this.deviceTypeFilter);
	}

	/**
	 * Updates the filter set using a comma-separated string of device types.
	 * Each item is trimmed and stored uniquely.
	 * * @param filterByDeviceType The comma-separated string of device types to apply.
	 */
	public void setDeviceTypeFilter(String deviceTypeFilter) {
		this.deviceTypeFilter.clear();
		if (StringUtils.isNotNullOrEmpty(deviceTypeFilter)) {
			Arrays.asList(deviceTypeFilter.split(DataprobeConstant.COMMA)).forEach(item -> {
				this.deviceTypeFilter.add(item.trim());
			});
		}
	}

	/**
	 * The location criteria used for filtering.
	 */
	private String locationFilter = "";

	/**
	 * Retrieves the current location filter.
	 *
	 * @return The current location filter value.
	 */
	public String getLocationFilter() {
		return locationFilter;
	}

	/**
	 * Sets a new value for the location filter.
	 *
	 * @param locationFilter The new location string to use as a filter.
	 */
	public void setLocationFilter(String locationFilter) {
		this.locationFilter = locationFilter;
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
	public void controlProperty(ControllableProperty cp) throws Exception {
		reentrantLock.lock();
		try {
			if (localExtendedStatistics == null) {
				return;
			}
			String controlProperty = cp.getProperty();
			String deviceId = cp.getDeviceId();
			String value = String.valueOf(cp.getValue());

			String[] parts = controlProperty.split(DataprobeConstant.HASH);
			String key = controlProperty.contains(DataprobeConstant.HASH) ? parts[0] : controlProperty;

			AggregatedDevice device = aggregatedDeviceList.stream()
					.filter(d -> deviceId.equals(d.getDeviceId()))
					.findFirst()
					.orElse(null);

			if(device == null){
				throw new IllegalStateException(String.format("Unable to control property: %s as the device does not exist.", controlProperty));
			}

			switch (key){
				case DataprobeConstant.CONFIGURATION:
					G2ConfigurationRequest g2ConfigurationRequest =	handleDeviceConfigurationControl(controlProperty, deviceId, value);
					sendConfigurationToDevice(g2ConfigurationRequest);
					break;
				case DataprobeConstant.REBOOT:
					RebootRequest rebootRequest = new RebootRequest(loginInfo.getToken(), deviceId, DataprobeConstant.ONE);
					sendControlCommand(rebootRequest, DataprobeConstant.ERROR_CONTEXT_REBOOT);
					break;
				default:
					ControlObject controlObject = handleOutletAndGroupControl(controlProperty, deviceId, value);
					sendControlCommand(controlObject, DataprobeConstant.ERROR_CONTEXT_CONTROL);
					updateCachedMonitoringAfterControl(deviceId, controlProperty, value);
					if (isG2Device(device) && controlProperty.endsWith(DataprobeConstant.HASH + DataprobeConstant.CYCLE)) {
						try {
							TimeUnit.SECONDS.sleep(10);
						} catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							logger.warn("Cycle wait interrupted for device " + deviceId + ie);
						}
					}
					break;
			}
		} catch (JsonProcessingException e) {
			String message = String.format("Failed to control property '%s' on device '%s' with value '%s' due to JSON processing error.",
					cp.getProperty(), cp.getDeviceId(), cp.getValue());
			throw new RuntimeException(message, e);
		} finally {
			reentrantLock.unlock();
		}
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
		if (executorService == null || executorService.isTerminated() || executorService.isShutdown()) {
			if (logger.isDebugEnabled()) {
				logger.debug("Restarting executor service and initializing with the new data loader");
			}
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
		displayPropertyGroups.clear();
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
			String jsonPayload = Util.requestBody(loginInfo.getToken(), deviceTypeFilter, locationFilter, null);
			String result = this.doPost(DataprobeCommand.RETRIEVE_INFO, jsonPayload);

			if (logger.isDebugEnabled()) {
				logger.debug(String.format("Device list response received. length=%s", result == null ? -1 : result.length()));
			}

			JsonNode listResponse = objectMapper.readTree(result);

			if (!listResponse.has(DataprobeConstant.DEVICES) || listResponse.get(DataprobeConstant.DEVICES).isEmpty()) {
				if (logger.isDebugEnabled()) {
					logger.debug(String.format("No devices returned. endpoint=%s, deviceTypeFilter=%s, locationFilter=%s",
							DataprobeCommand.RETRIEVE_INFO, deviceTypeFilter, locationFilter));
				}
				return;
			}
				JsonNode data = listResponse.path(DataprobeConstant.DEVICES);
				if (data == null || !data.isArray() || data.isEmpty()) {
					return;
				}

				Map<String, Map<String, String>> nextDeviceCache = new HashMap<>();

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
					applyPendingOutletOverrides(deviceMACAddress, mappingValue);
					nextDeviceCache.put(deviceMACAddress, mappingValue);
				}
			synchronized (cachedMonitoringDevice) {
				cachedMonitoringDevice.clear();
				cachedMonitoringDevice.putAll(nextDeviceCache);
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

		if(isDisplayGroup(DataprobeConstant.AUTOPING)){
			mapAutopingStatus(cachedData, stats);
		}

		populateListConfigurationOfDevice(stats, deviceMAC, controls);

		mapOutletAndTriggerInfoGroup(cachedData, stats, controls);
		mapMonitorProperty(cachedData, stats, deviceMAC);
		mapControllableProperty(stats, controls, cachedData);

		aggregatedDevice.setProperties(stats);
		aggregatedDevice.setTimestamp(System.currentTimeMillis());
		aggregatedDevice.setDynamicStatistics(Collections.emptyMap());

		if (!configManagement) {
			controls.clear();
			controls.add(ControllablePropertyFactory.createText(DataprobeConstant.EMPTY, DataprobeConstant.EMPTY));
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
	private void mapOutletAndTriggerInfoGroup(Map<String, String> cachedData, Map<String, String> stats, List<AdvancedControllableProperty> controls) {
		if (cachedData == null || cachedData.isEmpty()) {
			return;
		}
		if(isDisplayGroup(DataprobeConstant.OUTLET)) {
			cachedData.forEach((key, rawStatus) -> {
				if (!key.endsWith(DataprobeConstant.HASH + DataprobeConstant.STATUS)) {
					return;
				}
				if (isAutopingKey(key)) {
					return;
				}

				String groupName = key.substring(0,
						key.indexOf(DataprobeConstant.HASH + DataprobeConstant.STATUS));

				String statusValue = Util.getDefaultValueForNullData(rawStatus).toLowerCase();

				String nameKey = groupName + DataprobeConstant.HASH + "Name";
				String realName = cachedData.get(nameKey);
				if (realName == null || realName.isEmpty()) {
					realName = groupName;
				}

				stats.put(nameKey, realName);
				stats.put(groupName + DataprobeConstant.HASH + DataprobeConstant.STATUS, Util.uppercaseFirstCharacter(statusValue));

				if ("inactive".equalsIgnoreCase(statusValue) || DataprobeConstant.CYCLE.equalsIgnoreCase(statusValue)) {
					return;
				}

				boolean isOn = DataprobeConstant.ON.equalsIgnoreCase(statusValue);
				String controlKey = groupName + DataprobeConstant.HASH + DataprobeConstant.CONTROL;
				String cycleKey = groupName + DataprobeConstant.HASH + DataprobeConstant.CYCLE;

				Util.addAdvancedControlProperties(controls, stats, ControllablePropertyFactory.createSwitch(controlKey, isOn ? 1 : 0), isOn ? DataprobeConstant.ONE : DataprobeConstant.ZERO);
				Util.addAdvancedControlProperties(controls, stats, ControllablePropertyFactory.createButton(cycleKey, DataprobeConstant.CYCLE, DataprobeConstant.CYCLING, 0L), DataprobeConstant.NONE);
			});
		}

			// Map TriggerInfo_*
			cachedData.forEach((k, v) -> {
				if (k.startsWith(DataprobeConstant.TRIGGER_INFO_GROUP)) {
					stats.put(k, Util.getDefaultValueForNullData(v));
				}
			});
	}

	/**
	 * Maps autoping status information from cached monitoring data
	 * into statistics and advanced controllable properties.
	 *
	 * @param cachedData the cached monitoring data for a device
	 * @param stats      the statistics map to populate
	 */
	private void mapAutopingStatus(Map<String, String> cachedData, Map<String, String> stats) {
		if (cachedData == null || cachedData.isEmpty()) {
			return;
		}
		cachedData.forEach((key, rawStatus) -> {
			if (!key.endsWith(DataprobeConstant.HASH + DataprobeConstant.STATUS)) {
				return;
			}
			if (!isAutopingKey(key)) {
				return;
			}

			String groupName = key.substring(0,
					key.indexOf(DataprobeConstant.HASH + DataprobeConstant.STATUS));

			String statusValue = Util.getDefaultValueForNullData(rawStatus).toLowerCase();

			String nameKey  = groupName + DataprobeConstant.HASH + "Name";
			String realName = cachedData.get(nameKey);
			if (realName == null || realName.isEmpty()) {
				realName = groupName;
			}

			stats.put(nameKey, realName);
			stats.put(groupName + DataprobeConstant.HASH + DataprobeConstant.STATUS, Util.uppercaseFirstCharacter(statusValue));
		});
	}

	/**
	 * Determines whether the given mapping key belongs to an Autoping group.
	 * "Autoping_1", "Autoping_2", etc.
	 *
	 * @param key the flattened mapping key to inspect (e.g. "AutoPing_1#Status")
	 * @return {@code true} if the key prefix contains the AutoPing marker, {@code false} otherwise
	 */
	private boolean isAutopingKey(String key) {
		String[] parts = key.split(DataprobeConstant.UNDER_SCORE, 2);
		return parts.length > 0 && parts[0].contains(DataprobeConstant.AUTOPING);
	}


	/**
	 * Retrieves and maps the configuration of a device into statistics and controllable properties.
	 * Calls the configuration API for the given device, flattens the returned sections
	 * (device, network, advancedNetwork, autoping), and updates the provided stats and controls.
	 *
	 * @param stats     the statistics map to populate with configuration values
	 * @param deviceMAC the MAC address of the device whose configuration is retrieved
	 * @param controls  the list of controllable properties to populate for configuration control
	 * @throws ResourceNotReachableException if the configuration cannot be retrieved or processed
	 */
	private void populateListConfigurationOfDevice(Map<String, String> stats, String deviceMAC, List<AdvancedControllableProperty> controls) {
		try {
			String jsonPayload = Util.requestBodyConfigurationDevice(loginInfo.getToken(), deviceMAC, null);
			String result = this.doPost(DataprobeCommand.CONFIG_GET, jsonPayload);
			JsonNode root = objectMapper.readTree(result);

			if (!DataprobeConstant.TRUE.equalsIgnoreCase(root.path("success").asText())) {
				return;
			}

			Map<String, String> mappingValue = new HashMap<>();

			if(isDisplayGroup(DataprobeConstant.NETWORK)){
				populateNetworkConfig(root, mappingValue);
				stats.putAll(mappingValue);
			}

			if(isDisplayGroup(DataprobeConstant.ADVANCED_NETWORK)){
				populateAdvancedNetworkConfig(root, mappingValue);
				stats.putAll(mappingValue);
			}

			if(isDisplayGroup(DataprobeConstant.AUTOPING)){
				populateAutoPingConfig(root, mappingValue);
				stats.putAll(mappingValue);
			}

			if(isDisplayGroup(DataprobeConstant.CONFIGURATION)){
				populateDeviceConfig(root, mappingValue);
				stats.putAll(mappingValue);
				mappingConfigurationProperty(mappingValue, stats, controls);
			}
		} catch (Exception e) {
			throw new ResourceNotReachableException("Unable to retrieve device configuration", e);
		}
	}

	/**
	 * Populates the mapping with flattened device configuration values
	 * from the {@code device} node of the given root.
	 *
	 * @param root         the root JSON node containing the device section
	 * @param mappingValue the map to populate with flattened device configuration
	 */
	private void populateDeviceConfig(JsonNode root, Map<String, String> mappingValue) {
		JsonNode deviceNode = root.path("device");
		flattenConfigObject(deviceNode, DataprobeConstant.CONFIGURATION, mappingValue);
	}

	/**
	 * Populates the mapping with flattened network configuration values.
	 * The {@code network} node is first remapped using {@link ConfigurationNetwork}
	 * and then flattened into the provided map.
	 *
	 * @param root         the root JSON node containing the network section
	 * @param mappingValue the map to populate with flattened network configuration
	 */
	private void populateNetworkConfig(JsonNode root, Map<String, String> mappingValue) {
		JsonNode networkNode = root.path("network");
		Util.mappingConfig(
				networkNode,
				ConfigurationNetwork.values(),
				ConfigurationNetwork::getField,
				ConfigurationNetwork::getName,
				(e, value) -> value
		);
		flattenConfigObject(networkNode, "Network", mappingValue);
	}

	/**
	 * Populates the mapping with flattened advanced network configuration values.
	 *
	 * @param root         the root JSON node containing the advanced network section
	 * @param mappingValue the map to populate with flattened advanced network configuration
	 */
	private void populateAdvancedNetworkConfig(JsonNode root, Map<String, String> mappingValue) {
		JsonNode advNetworkNode = root.path("advancedNetwork");
		Util.mappingConfig(
				advNetworkNode,
				ConfigurationAdvancedNetwork.values(),
				ConfigurationAdvancedNetwork::getField,
				ConfigurationAdvancedNetwork::getName,
				(advancedNetwork, value) -> {
					switch (advancedNetwork) {
						case DST_ENABLED:
						case ENABLE_TIME_SERVER:
						case CLOUD_ENABLED:
							return DataprobeConstant.ZERO.equals(value)
									? DataprobeConstant.FALSE
									: DataprobeConstant.TRUE;
						default:
							return value;
					}
				}
		);
		flattenConfigObject(advNetworkNode, "AdvancedNetwork", mappingValue);
	}

	/**
	 * Populates the mapping with flattened AutoPing configuration values.
	 * The {@code autoping} node is first remapped using {@link ConfigurationAutoping}
	 * and then flattened into the provided map.
	 *
	 * @param root         the root JSON node containing the AutoPing section
	 * @param mappingValue the map to populate with flattened AutoPing configuration
	 */
	private void populateAutoPingConfig(JsonNode root, Map<String, String> mappingValue) {
		JsonNode autopingNode = root.path("autoping");
		Util.mappingConfig(
				autopingNode,
				ConfigurationAutoping.values(),
				ConfigurationAutoping::getField,
				ConfigurationAutoping::getName,
				(e, value) -> value
		);
		flattenConfigObject(autopingNode, DataprobeConstant.AUTOPING, mappingValue);
	}

	/**
	 * Maps device configuration values into statistics and advanced controllable properties.
	 *
	 * @param mappingValue the flat mapping of configuration keys to values
	 * @param stats        the statistics map to update with configuration values
	 * @param controls     the list to populate with advanced controllable properties
	 */
	private void mappingConfigurationProperty(Map<String, String> mappingValue, Map<String, String> stats, List<AdvancedControllableProperty> controls) {
		for (ConfigurationDevice device : ConfigurationDevice.values()){
			String name = device.getGroup() + DataprobeConstant.HASH + device.getName();
			String value = mappingValue.get(name);
			switch (device){
				case DISABLE_OFF:
					Util.addAdvancedControlProperties(controls, stats, ControllablePropertyFactory.createSwitch(name, Integer.parseInt(value)), value );
					break;
				case UPGRADE_ENABLE:
					Util.addAdvancedControlProperties(controls, stats, ControllablePropertyFactory.createSwitch(device.getGroup() + DataprobeConstant.HASH + "UpgradeEnabled", Integer.parseInt(value)), value );
					stats.remove(name);
					mappingValue.remove(name);
					break;
				case INITIAL_STATE:
					List<String> listInitial = Arrays.asList("On", "Off", "Last");
					Util.addAdvancedControlProperties(controls, stats, ControllablePropertyFactory.createDropdown(name, listInitial, Util.uppercaseFirstCharacter(value)), Util.uppercaseFirstCharacter(value));
					break;
				case AUTO_LOGOUT:
					Util.addAdvancedControlProperties(controls, stats, ControllablePropertyFactory.createNumeric(name + "(minutes)", value), value);
					stats.remove(name);
					mappingValue.remove(name);
					break;
				case CYCLE_TIME:
					Util.addAdvancedControlProperties(controls, stats, ControllablePropertyFactory.createNumeric(name + "(s)", value), value);
					stats.remove(name);
					mappingValue.remove(name);
					break;
				case LOCATION:
					stats.remove(name);
					mappingValue.remove(name);
					stats.put("Configuration#LocationID", value);
					break;
				default:
					stats.putAll(mappingValue);
					break;
			}
		}
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
				String value = cachedValue.get(name);
				switch (item) {
					case NAME:
					case MODEL:
					case IS_DEVICE_ONLINE:
						continue;
					case MANAGE_LINK:
						stats.put(name, retrieveManageLink(deviceMAC));
						break;
					case LAST_CONTACT:
						DateTimeFormatter inputFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
						LocalDateTime dateTime = LocalDateTime.parse(value, inputFmt);
						String iso8601 = dateTime.toString();
						stats.put(name, iso8601);
						break;
					default:
						stats.put(name, Util.getDefaultValueForNullData(value));
						break;
				}
			}
	}

	/**
	 * Maps controllable properties to the provided stats and advancedControllableProperties lists.
	 *
	 * @param stats A map containing the statistics to be populated with controllable properties.
	 * @param control A list of AdvancedControllableProperty objects to be populated with controllable properties.
	 */
	private void mapControllableProperty(Map<String, String> stats, List<AdvancedControllableProperty> control, Map<String, String> cachedData) {
		String model = cachedData.get(AggregatedInformation.MODEL.getName());
		if(model != null && model.toUpperCase().contains("G2")){
			Util.addAdvancedControlProperties(control, stats, ControllablePropertyFactory.createButton(DataprobeConstant.REBOOT, DataprobeConstant.REBOOT, "Rebooting", 0), DataprobeConstant.NONE);
		} else {
			stats.put(DataprobeConstant.REBOOT, DataprobeConstant.NOT_AVAILABLE);
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
			dynamicStatistics.put(DataprobeConstant.MONITORING_CYCLE_DURATION, String.valueOf(lastMonitoringCycleDuration));
			stats.put(DataprobeConstant.ADAPTER_VERSION,
					Util.getDefaultValueForNullData(adapterProperties.getProperty("aggregator.version")));
			stats.put(DataprobeConstant.ADAPTER_BUILD_DATE,
					Util.getDefaultValueForNullData(adapterProperties.getProperty("aggregator.build.date")));
			long adapterUptime = System.currentTimeMillis() - adapterInitializationTimestamp;
			stats.put(DataprobeConstant.ADAPTER_UPTIME_MIN, String.valueOf(adapterUptime / (1000 * 60)));
			stats.put(DataprobeConstant.ADAPTER_UPTIME, Util.normalizeUptime(adapterUptime / 1000));
			stats.put(DataprobeConstant.SYSTEM_MONITORING_CYCLE, String.valueOf(getMonitoringRate() * 60));
			stats.put(DataprobeConstant.ACTIVE_PROPERTY_GROUPS, this.getDisplayPropertyGroups());
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
					if (Util.isBlank(outletName)) {
						return;
					}
					String groupOutletName = DataprobeConstant.OUTLET_GROUP + outletIndex;
					putStatusAndName(mappingValue, groupOutletName, outletName, entry.getValue());
				});
			}
		} else if (statusNode.isObject()) {
			// iBoot-G2: object
			final int[] outletIndex = {1};

			statusNode.fields().forEachRemaining(entry -> {
				String name = entry.getKey();
				if (Util.isBlank(name)) {
					return;
				}
				JsonNode valueNode = entry.getValue();

				if (isAutoPingName(name)) {
					// name: "AP-1" -> group: "AutoPing_1"
					String apId = extractAutoPingId(name); // "AP-1" -> "1"
					String groupOutletName = DataprobeConstant.AUTOPING + DataprobeConstant.UNDER_SCORE + apId;
					putStatusAndName(mappingValue, groupOutletName, name, valueNode);
				} else {
					String groupOutletName = DataprobeConstant.OUTLET_GROUP + outletIndex[0];
					putStatusAndName(mappingValue, groupOutletName, name, valueNode);
					outletIndex[0]++;
				}
			});
		} else {
			mappingValue.put("RawStatus", statusNode.toString());
		}
	}

	/**
	 * Puts the outlet or AutoPing status and display name into the flattened mapping.
	 *
	 * @param mappingValue the target map to update
	 * @param groupName    the logical group name (e.g. "Outlet_1", "AutoPing_1")
	 * @param displayName  the human-readable name to store (e.g. "Outlet-1", "AP-1")
	 * @param valueNode    the JSON node containing the status value
	 */
	private void putStatusAndName(Map<String, String> mappingValue, String groupName, String displayName, JsonNode valueNode) {
		String state = valueNode == null || valueNode.isNull() ? DataprobeConstant.EMPTY : valueNode.asText(DataprobeConstant.EMPTY);
		mappingValue.put(groupName + DataprobeConstant.HASH + DataprobeConstant.STATUS, state);
		mappingValue.put(groupName + DataprobeConstant.HASH + "Name", displayName);
	}

	/**
	 * Checks whether the given status key represents an AutoPing entry.
	 * Expected format is like "AP-1", "AP-2", etc.
	 *
	 * @param name the raw status key from the device response
	 * @return {@code true} if the name starts with an "AP" prefix, {@code false} otherwise
	 */
	private boolean isAutoPingName(String name) {
		String[] parts = name.split("-", 2);
		return parts.length > 0 && parts[0].contains("AP");
	}

	/**
	 * Extracts the AutoPing identifier from a status key.
	 * For example, "AP-1" becomes "1".
	 *
	 * @param name the raw AutoPing key (e.g. "AP-1")
	 * @return the extracted identifier part, or the original name if it cannot be parsed
	 */
	private String extractAutoPingId(String name) {
		String[] parts = name.split("-", 2);
		return parts.length == 2 ? parts[1] : name;
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
	 * Flattens the given JSON object node into a map of configuration values.
	 *
	 * @param node         the JSON object node to flatten
	 * @param group        the group prefix used for generated keys
	 * @param mappingValue the map to populate with flattened key/value pairs
	 */
	private void flattenConfigObject(JsonNode node, String group, Map<String, String> mappingValue) {
		if (node == null || node.isMissingNode() || !node.isObject()) {
			return;
		}
		node.fields().forEachRemaining(entry -> {
			String key = entry.getKey();
			JsonNode value = entry.getValue();
			String mapKey = group + DataprobeConstant.HASH + Util.uppercaseFirstCharacter(key);
			String rawValue = (value == null || value.isNull())
					? DataprobeConstant.EMPTY
					: value.asText(DataprobeConstant.EMPTY);
			if (DataprobeConstant.TRUE.equals(rawValue) || DataprobeConstant.FALSE.equals(rawValue) || DataprobeConstant.CONFIGURATION_LOCATION.equals(mapKey)){
				mappingValue.put(mapKey, rawValue);
				return;
			} else if(ConfigurationNetwork.IP_MODE.getName().equals(key)){
				mappingValue.put(mapKey, rawValue.toUpperCase());
				return;
			} else if(ConfigurationAutoping.AP_A_ACTION.getName().equals(key)){
				mappingValue.put(mapKey, Util.uppercaseFirstCharacterEachHyphenPart(rawValue));
				return;
			}
			mappingValue.put(mapKey, Util.uppercaseFirstCharacter(rawValue));
		});
	}

		/**
		 * Applies active pending outlet overrides (within TTL) to the given device mapping
		 * to prevent polling from overwriting recent control changes. Removes expired entries.
		 *
		 * @param deviceId     target device identifier
		 * @param mappingValue device properties to update in-place
		 */
	private void applyPendingOutletOverrides(String deviceId, Map<String, String> mappingValue) {
		for (String k : new ArrayList<>(mappingValue.keySet())) {
			int hashIdx = k.indexOf(DataprobeConstant.HASH);
			if (hashIdx <= 0) continue;

			String group = k.substring(0, hashIdx);
			if (!group.startsWith("Outlet_")) continue;

			PendingOutletState p = pendingOutletState.get(outletPendingKey(deviceId, group));
			if (p == null) continue;

			if (p.expiryMs > System.currentTimeMillis()) {
				mappingValue.put(group + "#Control", p.control);
				mappingValue.put(group + "#Status",  p.status);
			} else {
				pendingOutletState.remove(outletPendingKey(deviceId, group));
			}
		}
	}

	/**
	 * Updates the cached monitoring data after a control action
	 * so that subsequent statistics retrievals reflect the new state.
	 *
	 * @param deviceId        the target device MAC/ID
	 * @param controlProperty the property that was controlled (e.g. "Outlet_1#Control", "Outlet_1#Cycle")
	 * @param value           the value that was applied (e.g. "1", "0")
	 */
	private void updateCachedMonitoringAfterControl(String deviceId, String controlProperty, String value) {
		synchronized (cachedMonitoringDevice) {
			Map<String, String> cachedData = cachedMonitoringDevice.get(deviceId);
			if (cachedData == null) return;

			cachedData.put(controlProperty, value);

			if (controlProperty.endsWith(DataprobeConstant.HASH + DataprobeConstant.CONTROL)) {

				String groupName = controlProperty.substring(0, controlProperty.indexOf(DataprobeConstant.HASH + DataprobeConstant.CONTROL));
				String status = DataprobeConstant.ONE.equals(value) ? DataprobeConstant.ON : DataprobeConstant.OFF;
				long exp = System.currentTimeMillis() + OUTLET_PENDING_TTL_MS;

				pendingOutletState.put(outletPendingKey(deviceId, groupName),
						new PendingOutletState(value, status, exp));

				cachedData.put(groupName + DataprobeConstant.HASH + DataprobeConstant.STATUS, status);
				return;
			}

			String model = cachedData.getOrDefault("Model", "");
			if (model.contains("G2") && controlProperty.endsWith(DataprobeConstant.HASH + DataprobeConstant.CYCLE)) {
				String groupName = controlProperty.substring(0, controlProperty.indexOf(DataprobeConstant.HASH + DataprobeConstant.CYCLE));
				long exp = System.currentTimeMillis() + OUTLET_PENDING_TTL_MS;

				pendingOutletState.put(outletPendingKey(deviceId, groupName),
						new PendingOutletState(null, DataprobeConstant.CYCLE, exp));

				cachedData.remove(groupName + DataprobeConstant.HASH + DataprobeConstant.CONTROL);
				cachedData.remove(groupName + DataprobeConstant.HASH + DataprobeConstant.CYCLE);
				cachedData.put(groupName + DataprobeConstant.HASH + DataprobeConstant.STATUS, DataprobeConstant.CYCLE);
			}
		}
	}

	/**
	 * Handles the group control operation based on the given control property and value.
	 *
	 * @param controlProperty the control property string, containing the group information and action
	 * @param value the control value indicating the desired state (e.g., "1" for on, "0" for off)
	 * @return a {@link ControlObject} representing the group control operation to be executed
	 */
	private ControlObject handleOutletAndGroupControl(String controlProperty, String deviceMAC, String value) {
		int outletIndex = extractOutletIndex(controlProperty);
		String command = DataprobeConstant.ONE.equals(value) ? DataprobeConstant.ON.toLowerCase() : DataprobeConstant.OFF.toLowerCase();
		if (controlProperty.contains(DataprobeConstant.CYCLE)) {
			command = DataprobeConstant.CYCLE.toLowerCase();
		}
		return new ControlObject(this.loginInfo.getToken(), deviceMAC, new String[] { String.valueOf(outletIndex - 1) }, command);
	}

	private int extractOutletIndex(String controlProperty) {
		int startIndex = controlProperty.indexOf(DataprobeConstant.UNDER_SCORE);
		int endIndex = controlProperty.indexOf(DataprobeConstant.HASH);
		if (startIndex != -1 && endIndex != -1 && endIndex > startIndex + 1) {
			String numStr = controlProperty.substring(startIndex + 1, endIndex);
			return Integer.parseInt(numStr.trim());
		}
		throw new IllegalArgumentException(
				"Cannot extract outlet index from control property: " + controlProperty);
	}

	/**
	 * Builds a {@link G2ConfigurationRequest} for a single device configuration change
	 * based on the given control property and value.
	 *
	 * @param controlProperty the configuration control property (e.g. "Configuration#AutoLogout(s)")
	 * @param deviceMAC       the MAC address of the target device
	 * @param value           the new value to apply for the configuration field
	 * @throws IllegalArgumentException     if the control property cannot be mapped to a configuration field
	 */
	private G2ConfigurationRequest handleDeviceConfigurationControl(String controlProperty, String deviceMAC, String value) throws JsonProcessingException {
		ConfigurationDevice configField = ConfigurationDevice.detectControlProperty(controlProperty);
		if (configField == null) {
			throw new IllegalArgumentException("Unsupported device control property: " + controlProperty);
		}
		DeviceConfig deviceConfig = new DeviceConfig();

		switch (configField) {
			case LOCATION:
				deviceConfig.setLocation(value);
				break;
			case CYCLE_TIME:
				int cycleTime = parseIntOrThrow(value, "CycleTime");
				int cycleTimeClamped = clamp(cycleTime, 1, 999);

				deviceConfig.setCycleTime(String.valueOf(cycleTimeClamped));
				if (cycleTime != cycleTimeClamped) {
					throw new IllegalArgumentException(value + " is out of range. CycleTime must be between 1 and 999.");
				}
				break;
			case AUTO_LOGOUT:
				int autoLogout = parseIntOrThrow(value, "AutoLogout");
				int autoLogoutClamp  = clamp(autoLogout, 0, 99);

				deviceConfig.setAutoLogout(String.valueOf(autoLogoutClamp));

				if (autoLogout != autoLogoutClamp) {
					throw new IllegalArgumentException(value + " is out of range. AutoLogout must be between 0 and 99.");
				}
				break;
			case DISABLE_OFF:
				deviceConfig.setDisableOff(value);
				break;
			case INITIAL_STATE:
				deviceConfig.setInitialState(value);
				break;
			case UPGRADE_ENABLE:
				deviceConfig.setUpgradeEnable(value);
				break;
			default:
				throw new IllegalArgumentException("Unhandled configuration field: " + configField);
		}
		return new G2ConfigurationRequest(loginInfo.getToken(), deviceMAC, deviceConfig);
	}

	/**
	 * Parses the given string into an integer, throwing an {@link IllegalArgumentException} on invalid input.
	 *
	 * @param value     raw input value
	 * @param fieldName field name used in the error message
	 * @return parsed integer value
	 */
	private int parseIntOrThrow(String value, String fieldName) {
		try {
			return Integer.parseInt(value == null ? "" : value.trim());
		} catch (Exception e) {
			throw new IllegalArgumentException(fieldName + " must be a valid integer.", e);
		}
	}

	/**
	 * Clamps the given value to the provided inclusive range.
	 *
	 * @param value   value to clamp
	 * @param min minimum allowed value (inclusive)
	 * @param max maximum allowed value (inclusive)
	 * @return clamped value within {@code [min, max]}
	 */
	private int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	/**
	 * Sends a configuration update request to the device and validates the response.
	 * If the API reports a failure or an error occurs during the call,
	 * a {@link ResourceNotReachableException} is thrown.
	 *
	 * @param request the configuration request to be sent to the device
	 * @throws ResourceNotReachableException if the device configuration cannot be set
	 */
	private void sendConfigurationToDevice(G2ConfigurationRequest request) {
		try {
			String response = this.doPost(DataprobeCommand.CONFIG_SET, objectMapper.writeValueAsString(request));
			JsonNode deviceResponse = objectMapper.readTree(response);

			if (!deviceResponse.path("success").asText().equalsIgnoreCase(DataprobeConstant.TRUE)) {
				String message = deviceResponse.path("message").toString();
				throw new ResourceNotReachableException("Failed to set configuration: " + message);
			}
		} catch (Exception e) {
			throw new ResourceNotReachableException("Unable to set device configuration", e);
		}
	}

	/**
	 * Checks whether the given device is an iBoot-G2 model based on its model name.
	 *
	 * @param device the aggregated device to inspect
	 */
	private boolean isG2Device(AggregatedDevice device) {
		String model = device.getDeviceModel();
		return model != null && model.toUpperCase().contains("G2");
	}

	/**
	 * Sends a control command payload to the CONTROL endpoint and validates the response.
	 *
	 * @param payload      the request body object (ControlObject, RebootRequest, ...)
	 * @param errorContext short description used in error messages (e.g. "control device", "reboot device")
	 */
	private void sendControlCommand(Object payload, String errorContext) {
		try {
			String response = this.doPost(DataprobeCommand.CONTROL, objectMapper.writeValueAsString(payload));
			JsonNode deviceResponse = objectMapper.readTree(response);

			if (!deviceResponse.at(DataprobeConstant.RESPONSE_SUCCESS).asBoolean()) {
				String message = deviceResponse.at(DataprobeConstant.RESPONSE_MESSAGE).asText();
				throw new ResourceNotReachableException("Unable to " + errorContext + ": " + message);
			}
		} catch (Exception e) {
			throw new ResourceNotReachableException("Unable to " + errorContext, e);
		}
	}

	/**
	 * Checks whether the given group name is configured to be displayed.
	 *
	 * @param groupName the group name to check
	 * @return {@code true} if {@code displayPropertyGroups} is not empty and contains {@code groupName}; otherwise {@code false}
	 */
	private boolean isDisplayGroup(String groupName) {
		return !CollectionUtils.isEmpty(this.displayPropertyGroups) && this.displayPropertyGroups.contains(groupName);
	}
}