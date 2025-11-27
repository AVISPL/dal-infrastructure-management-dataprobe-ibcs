/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.symphony.dal.infrastructure.management.dataprobe.ibcs;

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
import javax.security.auth.login.FailedLoginException;

import com.avispl.symphony.api.dal.control.Controller;
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
 * <li>Outlet 1-N</li>
 * <li>Name</li>
 * <li>Status</li>
 * <li>Control</li>
 * <li>Cycle</li>
 *
 * <li></li>
 * <li>Name</li>
 * <li>Control</li>
 * <p>
 * Controlling:
 * <li>On/Off/Cycle Outlets config</li>
 * <li>Run config</li>
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
			if (response.has(DataprobeConstant.SUCCESS) && response.get(DataprobeConstant.SUCCESS).asText().equals(DataprobeConstant.TRUE)) {
				if (response.at(DataprobeConstant.RESPONSE_SUCCESS).asBoolean()) {
					String token = response.at("/token").asText();
					if (loginInfo == null) {
						loginInfo = new LoginInfo();
					}
					loginInfo.setToken(token);
				} else {
					loginInfo = null;
					throw new FailedLoginException(response.at(DataprobeConstant.RESPONSE_MESSAGE).asText());
				}
			} else {
				throw new FailedLoginException(response.get("message").asText());
			}
		} catch (Exception e) {
			throw new FailedLoginException("Auth error when get token api " + e);
		}
	}

	@Override
	public List<AggregatedDevice> retrieveMultipleStatistics() {
		return List.of();
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
		} catch (Exception e) {
			throw new ResourceNotReachableException("Failed to populate metadata information with deviceTypeFilter ",e);
		}
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
}