/*
* iCOMM Water Heater
*
* Description:
* This Hubitat driver is designed for use with an iCOMM Water Heater.  This is
* the child device per water heater.  It takes no direct configuration
*
* Licensing:
* Copyright 2025 Mike Bishop
*
* Based on:
* - py-aosmith by bdr99, https://github.com/bdr99/py-aosmith/
* - Schluter Ditra driver by Marc Reyhner, https://github.com/marcre/hubitat-drivers
*/

import groovy.transform.Field

metadata{
    definition ( name: "iCOMMWaterHeater", namespace: "evequefou", author: "Mike Bishop", importUrl: "https://github.com/MikeBishop/hubitat-icomm/icomm-water-heater.groovy" ) {
        capability "Sensor"
        capability "Refresh"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatOperatingState"
        capability "ThermostatSetpoint"
        capability "SwitchLevel"
        attribute "Brand", "string"
        attribute "Model", "string"
        attribute "Device Type", "string"
        attribute "DSN", "string"
        attribute "Serial Number", "string"
        attribute "Install Location", "string"
        attribute "Maximum Temperature", "number"
        attribute "Previous Temperature", "number"
        attribute "Schedule Mode", "string"
        attribute "Online", "string"
        attribute "Firmware Version", "string"
        attribute "Mode", "string"

        command "setMode", [
            [
                name: "Mode",
                type: "ENUM",
                constraints: [
                    "HYBRID",
                    "HEAT_PUMP",
                    "ELECTRIC",
                    "VACATION"
                ]
            ],
            [
                name: "Number of days",
                type: "NUMBER",
                required: false,
                description: "Optional, not supported by all modes"
            ]
        ]
        command "setHeatingSetpoint", [
            [
                name: "Temperature",
                type: "NUMBER",
                required: true,
                description: "Temperature in ${location.temperatureScale}"
            ]
        ]
    }
}

def installed() {
    // Set static attributes at install time
    // Currently don't expose any static attributes; might later
}

def ProcessUpdate(heater) {
    if (getParent().DebugLogsEnabled()) log.debug("Thermostat raw data ${heater.toString()}")

    UpsertAttribute("Brand", heater.brand)
    UpsertAttribute("Model", heater.model)
    UpsertAttribute("Device Type", heater.deviceType)
    UpsertAttribute("DSN", heater.dsn)
    device.setName(heater.name)
    UpsertAttribute("Serial Number", heater.serial)
    UpsertAttribute("Install Location", heater?.install?.location)

    def setpoint = heater?.data?.temperatureSetpoint
    UpsertAttribute("thermostatSetpoint", setpoint, location.temperatureScale)
    UpsertAttribute("heatingSetpoint", setpoint, location.temperatureScale)
    UpsertAttribute(
        "Maximum Temperature",
        heater?.data?.temperatureSetpointMaximum,
        location.temperatureScale
    );
    UpsertAttribute(
        "Previous Temperature",
        heater?.data?.temperatureSetpointPrevious,
        location.temperatureScale
    );
    UpsertAttribute("Mode", heater?.data?.mode);
    UpsertAttribute("Online", heater?.data?.isOnline.toString());
    UpsertAttribute("Firmware Version", heater?.data?.firmwareVersion);

    def waterLevel = 100;
    def waterLevelVal = heater?.data?.hotWaterStatus;
    if( waterLevelVal instanceof String ){
        switch(waterLevelVal){
            case "LOW":
                waterLevel = 0;
                break;
            case "MEDIUM":
                waterLevel = 50;
                break;
            case "HIGH":
                waterLevel = 100;
                break;
        }
    }
    else if ( waterLevelVal instanceof Number ){
        // Reported number goes up as water is used; need to convert
        waterLevel = 100 - waterLevelVal;
    }
    UpsertAttribute("level", waterLevel, "%")

    state.supportedModes = heater?.data?.modes

    if (heater?.data?.modePending || heater?.data?.temperatureSetpointPending) {
        log.info("Pending changes on ${device.getDisplayName()}")
        runIn(5, "refresh")
    }
}

def setLevel(level) {
    log.warn("You cannot set the level on your water heater. It refills automatically.");
}

def setMode(mode, days = null) {
    log.info("setMode(${mode}) on ${device.getDisplayName()} invoked.")

    def targetMode = state.supportedModes.find { it.mode == mode }

    if (targetMode == null) {
        log.warn("setMode(${mode}) on ${device.getDisplayName()} is not supported.")
        return
    }

    if (targetMode.controls == "SELECT_DAYS") {
        // Number of days is required
        if( days == null ) {
            days = 100
        }
        else if ( days < 1 || days > 100 ) {
            log.warn("Mode ${mode} on ${device.getDisplayName()} only supports 1-100 days.")
            return
        }
    }
    else {
        // Number of days is not supported
        if( days != null ) {
            log.warn("Mode ${mode} on ${device.getDisplayName()} does not support setting the number of days.")
            days = null
        }
    }

    def modePayload = ["mode": targetMode.mode];
    if (days != null) {
        modePayload["days"] = days
    }

    getParent().sendGraphQLRequest(SET_MODE, [
        "junctionId": device.deviceNetworkId,
        "mode": modePayload
    ], "ProcessModeChange")
}

def refresh() {
    log.info("refresh() on ${device.getDisplayName()} invoked.")
    getParent().refresh()
}

def normalizeTemperature(temperature) {
    minimumTemperature = 95

    if (temperature == null) {
        return minimumTemperature;
    }

    if (temperature < minimumTemperature) {
        log.warn("${temperature} on ${device.getDisplayName()} is less than minimum temperature ${minimumTemperature}.  Will set to minimum temperature.")

        return minimumTemperature;
    }

    maximumTemperature = device.currentValue("Maximum Temperature")

    if (temperature > maximumTemperature) {
        log.warn("setHeatingSetpoint(${temperature}) on ${device.getDisplayName()} is greater than maximum temperature ${maximumTemperature}.  Will set to maximum temperature.")
        log.info("You may be able to increase the maximum temperature from the water heater's control panel.")

        return maximumTemperature;
    }

    return temperature;
}

def setHeatingSetpoint(temperature) {
    log.info("setHeatingSetpoint(${temperature}) on ${device.getDisplayName()} invoked.")

    temperature = normalizeTemperature(temperature)

    getParent().sendGraphQLRequest(
        SET_HEATING_SETPOINT,
        [
            "junctionId": device.deviceNetworkId,
            "value": temperature
        ],
        "ProcessSetpointChange"
    )
}


def UpsertAttribute( Variable, Value, Unit = null ){
    if( device.currentValue(Variable) != Value ){

        if( Unit != null ){
            log.info( "Event: ${ Variable } = ${ Value }${ Unit }" )
            sendEvent( name: "${ Variable }", value: Value, unit: Unit )
        } else {
            log.info( "Event: ${ Variable } = ${ Value }" )
            sendEvent( name: "${ Variable }", value: Value )
        }
    }
}

@Field static final String SET_MODE = "mutation updateMode(\$junctionId: String!, \$mode: ModeInput!) { updateMode(junctionId: \$junctionId, mode: \$mode) }";
@Field static final String SET_HEATING_SETPOINT = "mutation updateSetpoint(\$junctionId: String!, \$value: Int!) { updateSetpoint(junctionId: \$junctionId, value: \$value) }";
