/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */

package com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric;

/**
 * Enum ConfigurationAutoPing represents various pieces of configuration of a device.
 *
 * @author Harry / Symphony Dev Team<br>
 * @since 1.0.0
 */
public enum ConfigurationAutoPing {
	AP_A_ADDRESS("APAAddress", "apAAddress", "AutoPing"),
	AP_A_FREQUENCY("APAFrequency", "apAFrequency", "AutoPing"),
	AP_A_FAIL_COUNT("APAFailCount", "apAFailCount", "AutoPing"),

	AP_A_MODE("APAMode", "apAMode", "AutoPing"),
	AP_A_ACTION("APAAction", "apAAction", "AutoPing"),
	AP_A_CYCLES("APACycles", "apACycles", "AutoPing"),
	AP_A_RESTART("APARestart", "apARestart", "AutoPing"),

	AP_B_ADDRESS("APBAddress", "apBAddress", "AutoPing"),
	AP_B_FREQUENCY("APBFrequency", "apBFrequency", "AutoPing"),
	AP_B_FAIL_COUNT("APBFailCount", "apBFailCount", "AutoPing"),
	;

	private final String name;
	private final String field;
	private final String group;

	/**
	 * Constructor for ConfigurationAutoPing.
	 *
	 * @param name The name representing the system information category.
	 * @param group The group associated with the category.
	 */
	ConfigurationAutoPing(String name, String field, String group) {
		this.name = name;
		this.field = field;
		this.group = group;
	}

	/**
	 * Retrieves {@link #name}
	 *
	 * @return value of {@link #name}
	 */
	public String getName() {
		return name;
	}

	/**
	 * Retrieves {@link #field}
	 *
	 * @return value of {@link #field}
	 */
	public String getField() {
		return field;
	}

	/**
	 * Retrieves {@link #group}
	 *
	 * @return value of {@link #group}
	 */
	public String getGroup() {
		return group;
	}
}
