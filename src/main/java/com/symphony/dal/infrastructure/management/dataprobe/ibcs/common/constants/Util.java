/*
 * Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.constants;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.DataprobeConstant;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.dal.util.StringUtils;

/**
 * Utility class for the adapter. Which includes helper methods.
 *
 * @author Harry / Symphony Dev Team
 * @since 1.0.0
 */
public class Util {

	private static final ZoneId ZONE_HCM = ZoneId.of("Asia/Ho_Chi_Minh");
	private static final DateTimeFormatter FORMATTER =
			DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZONE_HCM);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * Add addAdvancedControlProperties if advancedControllableProperties different empty
	 *
	 * @param advancedControllableProperties advancedControllableProperties is the list that store all controllable properties
	 * @param stats store all statistics
	 * @param property the property is item advancedControllableProperties
	 * @throws IllegalStateException when exception occur
	 */
	public static void addAdvancedControlProperties(List<AdvancedControllableProperty> advancedControllableProperties, Map<String, String> stats, AdvancedControllableProperty property, String value) {
		if (property != null) {
			advancedControllableProperties.removeIf(controllableProperty -> controllableProperty.getName().equals(property.getName()));

			String propertyValue = StringUtils.isNotNullOrEmpty(value) && !DataprobeConstant.NONE.equals(value) ? value : DataprobeConstant.EMPTY;
			stats.put(property.getName(), propertyValue);
			advancedControllableProperties.add(property);
		}
	}

	/**
	 * capitalize the first character of the string
	 *
	 * @param input input string
	 * @return string after fix
	 */
	public static String uppercaseFirstCharacter(String input) {
		if (input == null || input.isEmpty()) {
			return input;
		}
		char firstChar = input.charAt(0);
		return Character.toUpperCase(firstChar) + input.substring(1);
	}

	/**
	 * check value is null or empty
	 *
	 * @param value input value
	 * @return value after checking
	 */
	public static String getDefaultValueForNullData(String value) {
		return StringUtils.isNotNullOrEmpty(value) && !"null".equalsIgnoreCase(value) ? uppercaseFirstCharacter(value) : DataprobeConstant.NOT_AVAILABLE;
	}

	/**
	 * Builds the JSON request body for authentication.
	 * <p>
	 * Includes username and password, and optionally either a revoke token
	 * or a timeout object defined by interval and scale.
	 *
	 * @param user     the username
	 * @param pass     the password
	 * @param interval the timeout interval value (used if revoke is blank)
	 * @param scale    the timeout scale (used if revoke is blank)
	 * @param revoke   the revoke token, if provided
	 * @return the JSON string representing the authentication request body
	 * @throws JsonProcessingException if the JSON cannot be serialized
	 */
	public static String authBody(String user, String pass, String interval, String scale, String revoke)
			throws JsonProcessingException {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("username", user);
		root.put("password", pass);

		if (!isBlank(revoke)) {
			root.put("revoke", revoke);
		} else if (!isBlank(interval) && !isBlank(scale)) {
			ObjectNode timeout = MAPPER.createObjectNode();
			timeout.put("interval", interval);
			timeout.put("scale", scale);
			root.set("timeout", timeout);
		}
		return MAPPER.writeValueAsString(root);
	}

	/**
	 * Builds the JSON request body for retrieving device information.
	 * <p>
	 * Always includes the token, and conditionally includes either a MAC address
	 * or a list of types (and optionally a location) based on the provided values.
	 *
	 * @param token     the authentication token
	 * @param allTypes  the set of types to request; an empty string is used if null or empty
	 * @param location  the device location filter, may be blank
	 * @param mac       the device MAC address; if provided, it takes precedence over other filters
	 * @return the JSON string representing the request body
	 * @throws JsonProcessingException if the JSON cannot be serialized
	 */
	public static String requestBody(String token, Set<String> allTypes, String location, String mac) throws JsonProcessingException {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("token", token);

		if (!isBlank(mac)) {
			root.put("mac", mac);
			return MAPPER.writeValueAsString(root);
		}

		ArrayNode allArray = MAPPER.createArrayNode();
		if (allTypes == null || allTypes.isEmpty()) {
			allArray.add("");
		} else {
			for (String t : allTypes) {
				allArray.add(t == null ? DataprobeConstant.EMPTY : t);
			}
		}

		if (!isBlank(location)) {
			root.put("location", location);
			root.set("all", allArray);
			return MAPPER.writeValueAsString(root);
		}

		root.set("all", allArray);
		return MAPPER.writeValueAsString(root);
	}

	/**
	 * Builds the JSON request body for retrieving device information.
	 * <p>
	 * Always includes the token, and conditionally includes either a MAC address
	 * or a list of types (and optionally a location) based on the provided values.
	 *
	 * @param token     the authentication token
	 * @param mac       the device MAC address; if provided, it takes precedence over other filters
	 * @param tables    the set of types to request; an empty string is used if null or empty
	 * @return the JSON string representing the request body
	 * @throws JsonProcessingException if the JSON cannot be serialized
	 */
	public static String requestBodyConfigurationDevice(String token, String mac, Set<String> tables) throws JsonProcessingException {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("token", token);

		if (!isBlank(mac)) {
			root.put("mac", mac);
		}

		ArrayNode tableArray = MAPPER.createArrayNode();
		if (tables == null || tables.isEmpty()) {
			root.set("tables", tableArray);
		} else {
			for (String t : tables) {
				tableArray.add(t == null ? DataprobeConstant.EMPTY : t);
				root.set("tables", tableArray);
			}
		}
		return MAPPER.writeValueAsString(root);
	}

	/**
	 * Checks whether a string is null, empty, or contains only whitespace characters.
	 *
	 * @param s the string to check
	 * @return {@code true} if the string is null, empty, or whitespace-only; {@code false} otherwise
	 */
	public static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	/**
	 * Remaps the fields of a configuration JSON object using the provided enum definition.
	 *
	 * @param node             the JSON node to remap (must be an object)
	 * @param configValues     the enum values describing the configuration fields
	 * @param fieldExtractor   function to extract the original JSON field name from an enum value
	 * @param nameExtractor    function to extract the display name from an enum value
	 * @param valueTransformer function to transform the raw field value before storing
	 * @param <E>              the enum type used for configuration mapping
	 */
	public static  <E> void mappingConfig(JsonNode node, E[] configValues, Function<E, String> fieldExtractor, Function<E, String> nameExtractor,
			BiFunction<E, String, String> valueTransformer) {
		if (node == null || node.isNull() || !node.isObject()) {
			return;
		}

		ObjectNode objectNode = (ObjectNode) node;
		ObjectNode mapped = objectMapper.createObjectNode();

		for (E entry : configValues) {
			String jsonField   = fieldExtractor.apply(entry);
			String displayName = nameExtractor.apply(entry);

			JsonNode valueNode = objectNode.get(jsonField);
			String rawValue = (valueNode == null || valueNode.isNull())
					? DataprobeConstant.EMPTY
					: valueNode.asText(DataprobeConstant.EMPTY);

			String finalValue = valueTransformer.apply(entry, rawValue);
			mapped.put(displayName, finalValue);
		}

		objectNode.removeAll();
		objectNode.setAll(mapped);
	}

	/**
	 * Uptime is received in seconds, need to normalize it and make it human-readable, like
	 * 1 day(s) 5 hour(s) 12 minute(s) 55 minute(s)
	 * Incoming parameter is may have a decimal point, so in order to safely process this - it's rounded first.
	 * We don't need to add a segment of time if it's 0.
	 *
	 * @param uptimeSeconds value in seconds
	 * @return string value of format 'x day(s) x hour(s) x minute(s) x minute(s)'
	 */
	public static String normalizeUptime(long uptimeSeconds) {
		StringBuilder normalizedUptime = new StringBuilder();

		long seconds = uptimeSeconds % 60;
		long minutes = uptimeSeconds % 3600 / 60;
		long hours = uptimeSeconds % 86400 / 3600;
		long days = uptimeSeconds / 86400;

		if (days > 0) {
			normalizedUptime.append(days).append(" day(s) ");
		}
		if (hours > 0) {
			normalizedUptime.append(hours).append(" hour(s) ");
		}
		if (minutes > 0) {
			normalizedUptime.append(minutes).append(" minute(s) ");
		}
		if (seconds > 0) {
			normalizedUptime.append(seconds).append(" second(s)");
		}
		return normalizedUptime.toString().trim();
	}
}
