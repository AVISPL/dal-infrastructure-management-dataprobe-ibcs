/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */

package com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric;

import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.DataprobeConstant;

/**
 * Enum ConfigurationDevice represents various pieces of configuration of a device.
 *
 * @author Harry / Symphony Dev Team<br>
 * @since 1.0.0
 */
public enum ConfigurationDevice {
	LOCATION("Location", "location", "Configuration"),
	CYCLE_TIME("CycleTime", "cycleTime", "Configuration"),
	DISABLE_OFF("DisableOff", "disableOff", "Configuration"),
	INITIAL_STATE("InitialState", "initialState", "Configuration"),
	UPGRADE_ENABLE("UpgradeEnable", "upgradeEnable", "Configuration"),
	AUTO_LOGOUT("AutoLogout", "autoLogout", "Configuration"),

	EXP_1_CYCLE_TIME("Exp1CycleTime", "exp1CycleTime", "Configuration"),
	EXP_2_CYCLE_TIME("Exp2CycleTime", "exp2CycleTime", "Configuration"),
	DELAY_TIME("DelayTime", "delayTime", "Configuration"),
	EXP_1_INITIAL_STATE("Exp1InitialState", "exp1InitialState", "Configuration"),
	EXP_2_INITIAL_STATE("Exp2InitialState", "exp2InitialState", "Configuration"),
	OUTLET_NAME("OutletName", "outletName", "Configuration"),
	EXP_1_OUTLET_NAME("Exp1OutletName", "exp1OutletName", "Configuration"),
	EXP_2_OUTLET_NAME("Exp2OutletName", "exp2OutletName", "Configuration"),
	;

	private final String name;
	private final String field;
	private final String group;

	/**
	 * Constructor for ConfigurationDevice.
	 *
	 * @param name The name representing the system information category.
	 * @param group The group associated with the category.
	 */
	ConfigurationDevice(String name, String field, String group) {
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

	/**
	 * Resolve configuration field from a control property
	 */
	public static ConfigurationDevice detectControlProperty(String controlProperty) {
		if (controlProperty == null) {
			return null;
		}
		String[] parts = controlProperty.split(DataprobeConstant.HASH, 2);
		if (parts.length < 2) {
			return null;
		}
		String group = parts[0];
		String rawName = parts[1];

		int idx = rawName.indexOf(DataprobeConstant.OPEN_PARENTHESIS);
		String normalizedName = (idx != -1) ? rawName.substring(0, idx) : rawName;

		if (normalizedName.endsWith("Enabled")) {
			normalizedName = normalizedName.substring(0, normalizedName.length() - 1);
		}

		for (ConfigurationDevice device : values()) {
			if (device.group.equals(group) && device.name.equalsIgnoreCase(normalizedName)) {
				return device;
			}
		}
		return null;
	}
}
