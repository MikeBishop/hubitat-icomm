/*
* Hubitat-iCOMM Integration
* Copyright 2025 Mike Bishop,  All Rights Reserved
*
* Based on:
* - py-aosmith by bdr99, https://github.com/bdr99/py-aosmith/
* - Schluter Ditra driver by Marc Reyhner, https://github.com/marcre/hubitat-drivers
*
* This Hubitat driver is designed for use with a State/A.O. Smith iCOMM-enabled
* water heater.
*
* Preferences:
* Email Address = REQUIRED. The e-mail address for the iCOMM account
* Password = REQUIRED. Login password for the iCOMM account
* RefreshRate = REQUIRED; DEFAULT is 5 minutes. The rate at which the water heaters will be polled
* DebugLogs = OPTIONAL - DEFAULT = false. Should debug logging be enabled?
*/

import groovy.transform.Field

@Field static final String AOSMITH = "aosmith"
@Field static final String STATE = "state"
@Field static final String BASE_URI = "https://r2.wh8.co"
@Field static final String APP_VERSION = "14.1.0"
@Field static final String USER_AGENT = "okhttp/4.12.0"
// Service sometimes takes >10s (Hubitat default) to answer.
@Field static final int REQUEST_TIMEOUT_S = 30
// Access token (Keycloak JWT) lives 5 min. Re-login this long before expiry.
// Login also returns a refreshToken, but it's for a confidential Keycloak
// client ("icomm-server") whose secret only iCOMM's backend has, and the
// GraphQL API has no refresh call. So re-login is the only option.
@Field static final int TOKEN_EXPIRY_MARGIN_S = 30
// Service flaky. Report failure only after this many polls in a row fail.
@Field static final int FAILED_POLLS_BEFORE_STATUS = 3
// Attempts per request (and per poll login) before giving up.
@Field static final int MAX_ATTEMPTS = 3
@Field static final int RETRY_DELAY_S = 10

metadata{
    definition ( name: "iCOMM", namespace: "evequefou", author: "Mike Bishop", importUrl: "https://raw.githubusercontent.com/MikeBishop/hubitat-icomm/refs/heads/main/icomm-service.groovy" ) {
        // Attempting to indicate what capabilities the device should be capable of
        capability "Refresh"

        // Keep track of connection status
        attribute "Status", "string"
    }
    preferences{
        section{
            // Login information for the iCOMM account
            input( type: "string", name: "EmailAddress", title: "<font color='FF0000'><b>iCOMM account e-mail address</b></font>", required: true )
            input( type: "password", name: "Password", title: "<font color='FF0000'><b>iCOMM account password</b></font>", required: true )

            input( type: "enum", name: "Brand", title: "<b>Brand</b>", required: true, options: [ (AOSMITH): "A.O. Smith", (STATE): "State" ], defaultValue: "A.O. Smith" )

            // Enum to allow selecting the refresh rate that the device will be checked
            input( type: "enum", name: "RefreshRate", title: "<b>Refresh Rate</b>", required: false, multiple: false, options: [ "15 seconds", "30 seconds", "1 minute", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour", "3 hours", "Manual" ], defaultValue: "5 minutes" )

            // Enum to set the level of logging that will be used
            input( type: "bool", name: "DebugLogs", title: "<b>Enable Debug Logging?</b>", required: true, multiple: false, defaultValue: false )
        }
    }
}

// uninstalling device so make sure to clean up children
void uninstalled() {
	// Delete all children
	getChildDevices().each{
		deleteChildDevice( it.deviceNetworkId )
	}
    log.info("Uninstalled")
}

// updated is called whenever device parameters are saved
// Sets the current version of the driver, basic settings, and schedules
def updated() {

    // On update clear out any old session id in case login information changed
    state.remove("accessToken")
    state.remove("tokenExpiry")

    // Settings may have fixed the failure; start failure tracking fresh.
    // refreshToken: stored by a test build; not usable (confidential
    // Keycloak client), so just clear it.
    ["refreshToken", "loginRejected", "lastLoginError", "failedPolls", "pollPending", "requestsFailing"].each { state.remove(it) }

    // Check if the refresh rate is not set for some reason and putting it at the default
    if( RefreshRate == null ) {
        RefreshRate = "5 minutes"
    }

    // Random offsets so installs don't all hit the service at the same
    // moment (e.g. every :00/:05). Re-randomized on each save.
    def rng = new Random()
    def Second = rng.nextInt(60)
    def Minute = rng.nextInt(60)

    // Check what the refresh rate is set for then run it
    switch( RefreshRate ){
        case "15 seconds": // Schedule the refresh check for every 15 seconds
            schedule( "${ Second % 15 }/15 * * ? * *", "refresh" )
            break
        case "30 seconds": // Schedule the refresh check for every 30 seconds
            schedule( "${ Second % 30 }/30 * * ? * *", "refresh" )
            break
        case "1 minute": // Schedule the refresh check for every minute
            schedule( "${ Second } * * ? * *", "refresh" )
            break
        case "5 minutes": // Schedule the refresh check for every 5 minutes
            schedule( "${ Second } ${ Minute % 5 }/5 * ? * *", "refresh" )
            break
        case "10 minutes": // Schedule the refresh check for every 10 minutes
            schedule( "${ Second } ${ Minute % 10 }/10 * ? * *", "refresh" )
            break
        case "15 minutes": // Schedule the refresh check for every 15 minutes
            schedule( "${ Second } ${ Minute % 15 }/15 * ? * *", "refresh" )
            break
        case "30 minutes": // Schedule the refresh check for every 30 minutes
            schedule( "${ Second } ${ Minute % 30 }/30 * ? * *", "refresh" )
            break
        case "1 hour": // Schedule the refresh check for every hour
            schedule( "${ Second } ${ Minute } * ? * *", "refresh" )
            break
        case "3 hours": // Schedule the refresh check for every 3 hours
            schedule( "${ Second } ${ Minute } ${ rng.nextInt(3) }/3 ? * *", "refresh" )
            break
        default:
            unschedule( "refresh" )
            RefreshRate = "Manual"
            break
    }
    log.info( "Refresh rate: ${ RefreshRate }" )

    log.info("Updated")
}

def CanProceed() {
    if( EmailAddress == null || Password == null){
        UpsertAttribute( "Status", "Missing account email or password" )
        log.error( "Cannot update without username and password" )

        return false
    }

    return true
}

// refresh runs the device polling
def refresh() {

    if (!CanProceed()) {
        return
    }

    // Previous poll never reached Connected: count it failed.
    if (state.pollPending) {
        state.failedPolls = (state.failedPolls ?: 0) + 1
        if (state.failedPolls >= FAILED_POLLS_BEFORE_STATUS) {
            UpsertAttribute( "Status", "Update failed" )
        }
    }
    state.pollPending = true

    Poll(1)
}

def PollRetry(Map params) {
    Poll(params.attempt as int)
}

// One poll: login if needed, then fetch. Login retried here, fetch retried
// by sendGraphQLRequest.
def Poll(int attempt) {
    if( LoginIfNoToken() ) {
        if (state.accessToken != null) {
            runIn(5, "FetchDevices")
        }
        else if (state.loginRejected) {
            // Credentials rejected. Retrying won't help.
            RequestFailed("login rejected")
        }
        else if (attempt < MAX_ATTEMPTS) {
            debug("Login failed (${state.lastLoginError}); poll attempt ${attempt} of ${MAX_ATTEMPTS}, retrying in ${RETRY_DELAY_S}s")
            runIn(RETRY_DELAY_S, "PollRetry", [data: [attempt: attempt + 1], overwrite: false])
        }
        else {
            RequestFailed("login failed ${MAX_ATTEMPTS} times, last error: ${state.lastLoginError}")
        }
    }
    else {
        FetchDevices()
    }
}

def FetchDevices() {
    sendGraphQLRequest(GET_DEVICES_QUERY, [:], "ProcessGetDevicesResponse")
}

def LoginIfNoToken() {

    if (state.containsKey("accessToken")) {
        def expiry = state.tokenExpiry as Long
        if (expiry && now() < expiry - TOKEN_EXPIRY_MARGIN_S * 1000L) {
            if (DebugLogsEnabled()) log.trace("Valid session, no need to request new")
            return false
        }
        debug("Access token expired or expiring, logging in again")
        state.remove("accessToken")
        state.remove("tokenExpiry")
    }

    def credentials = ['email': EmailAddress, 'password': Password, 'locale': 'en'];
    def jsonString = groovy.json.JsonOutput.toJson(credentials);
    def urlEncoded = java.net.URLEncoder.encode(jsonString, "UTF-8");
    def passcode = new String(
        org.apache.commons.codec.binary.Base64.encodeBase64(urlEncoded.getBytes("UTF-8")),
        "UTF-8"
    );

    sendGraphQLRequest(LOGIN_QUERY, [passcode: passcode], "ProcessLoginResponse", false, 0)
    return true
}

def ProcessLoginResponse(response) {
    debug("Login raw data = \"${response.getData()}\"")

    def data = response.getData()
    def token = data?.data?.login?.user?.tokens?.accessToken

    if (token != null) {
        state.accessToken = token
        state.tokenExpiry = TokenExpiryMillis(token)
        state.loginRejected = false
        state.remove("lastLoginError")
        debug("Succesfully logged in to iCOMM API.")
        return
    }

    state.remove("accessToken")
    state.remove("tokenExpiry")

    // Bad email/password comes back as this GraphQL error code (as handled by
    // py-aosmith, node-aosmith, homebridge-aosmith-icomm).
    def invalidCredentials = data?.errors?.any { it?.extensions?.code == "INVALID_CREDENTIALS" }

    if (data?.data?.login == null && !invalidCredentials) {
        // No login result (other GraphQL errors, outage, rate limit):
        // server-side problem, not proof of bad credentials. Poll retries.
        state.lastLoginError = data?.errors ? "GraphQL errors: ${data.errors*.message}" : "empty login response"
        state.loginRejected = false
        debug("Login got no result: ${state.lastLoginError}")
        return
    }

    // INVALID_CREDENTIALS, or server answered login but issued no token.
    // User must act, so report at once; log error only on first rejection.
    if (!state.loginRejected) {
        log.error("Login rejected, no access token received. Check email and password.")
    }
    state.loginRejected = true
    state.lastLoginError = "login rejected"
    UpsertAttribute( "Status", "Login failed" )
}

// Token is JWT; read exp claim. Unparseable: assume 4 min.
def TokenExpiryMillis(token) {
    try {
        def payload = token.tokenize(".")[1]
        def json = new String(org.apache.commons.codec.binary.Base64.decodeBase64(payload), "UTF-8")
        def exp = new groovy.json.JsonSlurper().parseText(json)?.exp
        if (exp) return (exp as Long) * 1000L
    } catch (Exception e) {
        debug("Could not read token expiry: ${e}")
    }
    return now() + 240000L
}

def ProcessGetDevicesResponse(response) {
    debug("Got response ${response.getData()?.data?.devices}")

    def waterHeaters = response.getData()?.data?.devices.findAll {
        ["HeatPump", "NextGenHeatPump", "RE3Connected", "RE3Premium"].contains(it?.data?.__typename)
    };

    if (waterHeaters == null || waterHeaters.size() == 0) {
        log.warn("No compatible water heaters found.")
        UpsertAttribute( "Status", "No compatible water heaters found" )
        return
    }
    else {
        state.pollPending = false
        state.failedPolls = 0
        if (state.requestsFailing) {
            log.info("iCOMM requests recovered")
            state.requestsFailing = false
        }
        UpsertAttribute( "Status", "Connected" )
    }

    for (heater in waterHeaters) {
        ProcessDeviceUpdate(heater)
    }

    getChildDevices().each{ cd ->
        if (!waterHeaters.any { it.junctionId == cd.deviceNetworkId }) {
            log.info("Removing child device ${cd.deviceNetworkId}, ${cd.name}.")
            deleteChildDevice( cd.deviceNetworkId );
        }
    }
}

def ProcessDeviceUpdate(heater) {

    if (!getChildDevice(heater.junctionId)) {
        addChildDevice("iCOMMWaterHeater", heater.junctionId, [
            isComponent: true,
            name: heater.name ?: "${heater.install.location} Water Heater",
        ])
    }

    getChildDevice(heater.junctionId).ProcessUpdate(heater)
}

def ProcessModeChange(response) {
    ProcessChange(response, "updateMode");
}

def ProcessSetpointChange(response) {
    ProcessChange(response, "updateSetpoint")
}

def ProcessChange(response, fieldToCheck) {
    debug("State change response: ${response.getData()}")

    def data = response.getData()?.data;
    if (!data || data[fieldToCheck] != true) {
        log.error("Failed to change state: ${response.getData()}")
    }

    runIn(5, "FetchDevices")
}

// attempt: 1..MAX_ATTEMPTS, retried on failure. 0: single try, caller handles failure.
def sendGraphQLRequest(query, variables, handler, autologin = true, attempt = 1) {
    def headers = [
        "brand": Brand,
        "version": APP_VERSION,
        "User-Agent": USER_AGENT,
    ];

    if( state.accessToken != null ) {
        headers["Authorization"] = "Bearer ${state.accessToken}"
    }

    def body = [
        "query": query,
        "variables": variables
    ]

    def uri = "${BASE_URI}/graphql"

    try {
        httpPostJson([
            uri: uri,
            headers: headers,
            body: body,
            timeout: REQUEST_TIMEOUT_S
        ]) { response ->
            def status = response.getStatus();
            def data = response.getData();

            if (
                data?.errors?.extensions.any{ it.code == "UNAUTHORIZED_ERROR" } &&
                autologin
            ) {
                debug("Session expired, logging in again")
                ReloginAndRetry(query, variables, handler, attempt)
            } else if (status != 200) {
                RetryOrFail(query, variables, handler, autologin, attempt, "HTTP ${status}")
            } else if (data?.errors && query == GET_DEVICES_QUERY) {
                // Error-only response has no devices; handler would misreport.
                RetryOrFail(query, variables, handler, autologin, attempt, "GraphQL errors: ${data.errors*.message}")
            } else {
                this."$handler"(response)
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if( e.getStatusCode() == 401 && autologin ) {
            debug("Session expired, logging in again")
            ReloginAndRetry(query, variables, handler, attempt)
        }
        else {
            RetryOrFail(query, variables, handler, autologin, attempt, "HTTP ${e.getStatusCode()}: ${e.message}")
        }
    } catch (IOException | groovy.json.JsonException e) {
        // Timeout, no response, no route, HTML error page.
        RetryOrFail(query, variables, handler, autologin, attempt, "${e}")
    }
}

def ReloginAndRetry(query, variables, handler, attempt) {
    state.remove("accessToken")
    state.remove("tokenExpiry")
    LoginIfNoToken()

    if (state.accessToken != null) {
        runIn(5, "sendGraphQLRequest", [data: [query: query, variables: variables, handler: handler, autologin: false, attempt: attempt], overwrite: false])
    }
    else {
        RetryOrFail(query, variables, handler, true, attempt, "login failed: ${state.lastLoginError}")
    }
}

def RetryOrFail(query, variables, handler, autologin, attempt, reason) {
    if (attempt == 0) {
        // Only login uses attempt 0. Keep reason for Poll's failure message.
        // Server never answered, so this says nothing about the credentials.
        debug("GraphQL request failed: ${reason}")
        state.lastLoginError = reason
        state.loginRejected = false
    }
    else if (attempt < MAX_ATTEMPTS) {
        debug("GraphQL request failed: ${reason}; attempt ${attempt} of ${MAX_ATTEMPTS}, retrying in ${RETRY_DELAY_S}s")
        runIn(RETRY_DELAY_S, "sendGraphQLRequest", [data: [query: query, variables: variables, handler: handler, autologin: autologin, attempt: attempt + 1], overwrite: false])
    }
    else {
        RequestFailed(reason)
    }
}

// Warn once when requests start failing; info once on recovery.
def RequestFailed(reason) {
    if (!state.requestsFailing) {
        log.warn("iCOMM requests failing: ${reason}. Next log on recovery.")
        state.requestsFailing = true
    }
    else {
        debug("iCOMM request failed: ${reason}")
    }
}

def sendGraphQLRequest(Map params) {
    def query = params.query
    def variables = params.variables
    def handler = params.handler
    def autologin = params.containsKey("autologin") ? params.autologin : true
    def attempt = params.containsKey("attempt") ? params.attempt as int : 1

    sendGraphQLRequest(query, variables, handler, autologin, attempt)
}


// Update an attribute if it has changed
def UpsertAttribute( name, value, unit = null ) {

    if (device.currentValue(name) != value) {
        if (unit != null) {
            log.info("Event: ${name} = ${value}${unit}")
            sendEvent(name: name, value: value, unit: unit)
        } else {
            log.info("Event: ${name} = ${value}")
            sendEvent(name: name, value: value)
        }
    }
}

def DebugLogsEnabled() {
     return DebugLogs
}

def debug(String message) {
    if (DebugLogsEnabled()) {
        log.debug(message)
    }
}

@Field static final String LOGIN_QUERY = """
query login(\$passcode: String)
{
    login(passcode: \$passcode)
    {
        user
        {
            tokens
            {
                accessToken
                idToken
                refreshToken
            }
        }
    }
}
"""

@Field static final String GET_DEVICES_QUERY = """
query devices(\$forceUpdate: Boolean, \$junctionIds: [String]) {
    devices(forceUpdate: \$forceUpdate, junctionIds: \$junctionIds) {
        brand
        model
        deviceType
        dsn
        junctionId
        name
        serial
        location
        data {
            __typename
            temperatureSetpoint
            temperatureSetpointPending
            temperatureSetpointPrevious
            temperatureSetpointMaximum
            modes {
                mode
                controls
            }
            isOnline
            ... on HeatPump {
                firmwareVersion
                hotWaterStatus
                mode
                modePending
            }
            ... on NextGenHeatPump {
                firmwareVersion
                hotWaterStatus
                mode
                modePending
            }
            ... on RE3Connected {
                firmwareVersion
                hotWaterStatus
                mode
                modePending
            }
            ... on RE3Premium {
                firmwareVersion
                hotWaterStatus
                mode
                modePending
                hotWaterPlusLevel
            }
        }
    }
}
"""
