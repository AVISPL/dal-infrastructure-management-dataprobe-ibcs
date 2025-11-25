/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */

package com.symphony.dal.infrastructure.management.dataprobe.ibcs;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;

/**
 * DataprobeIBCSCommunicatorTest
 *
 * @author Harry / Symphony Dev Team<br>
 * @since 1.0.0
 */
public class DataprobeIBCSCommunicatorTest {
	private DataprobeIBCSCommunicator communicator;

	@BeforeEach
	void setUp() throws Exception {
		communicator = new DataprobeIBCSCommunicator();
		communicator.setHost("*****");
		communicator.setProtocol("https");
		communicator.setLogin("*****");
		communicator.setPassword("*****");
		communicator.init();
		communicator.connect();
	}

	@AfterEach
	void destroy() throws Exception {
		communicator.disconnect();
		communicator.destroy();
	}

	@Test
	void testLoginSuccess() throws Exception {
		communicator.getMultipleStatistics();
	}

	@Test
	public void testGetMultipleStatistics() throws Exception {
		communicator.getMultipleStatistics();
		Map<String,String> stats = ((ExtendedStatistics) communicator.getMultipleStatistics().get(0)).getStatistics();
		System.out.println(stats);
	}

}
