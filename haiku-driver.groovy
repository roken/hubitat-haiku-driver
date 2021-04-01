import groovy.transform.Field

metadata {
    definition(name: "Haiku Fan", namespace: "community", author: "Zack Brown") {
        capability "FanControl"
        capability "SwitchLevel"
        capability "Switch"
        capability "Light"
        capability "Refresh"

        command "reverseFan"
        command "toggleMotionFan"
        command "toggleMotionLight"
        command "enableMotionFan"
        command "enableMotionLight"
        command "disableMotionFan"
        command "disableMotionLight"

        attribute "fanDirection", "string"
        attribute "motionFan", "string"
        attribute "motionLight", "string"
    }
}

preferences {
    section("Device Selection") {
        input("deviceName", "text", title: "Device Name", description: "", required: true, defaultValue: "")
        input("deviceIp", "text", title: "Device IP Address", description: "", required: true, defaultValue: "")
        input("logEnable", "bool", title: "Enable debug logging", defaultValue: true)
    }
}

//
// Constants
//
// Number of light graduations Haiku supports.
@Field final int HAIKU_LIGHT_LEVELS = 16

// Ratio of light levels to percentage level. 1 Haiku light level every 6.25%
@Field final double HAIKU_LIGHT_SPREAD = (double)100/HAIKU_LIGHT_LEVELS


def installed() {
    log.debug "installed"
}

def updated() {
    log.debug "updated"
}

def setFanDirection(String direction) {
    sendCommand("FAN", "DIR", "SET;${direction}")
}

def reverseFan() {
    if (device.currentValue("fanDirection") == "FWD") {
        setFanDirection("REV")
    } else {
        setFanDirection("FWD")
    }
}

def setMotion(String device, String onOff) {
    sendCommand(device, "AUTO", "${onOff}")
}

def toggleMotionFan() {
    if (device.currentValue("motionFan") == "OFF") {
        enableMotionFan() 
    } else {
        disableMotionFan() 
    }
}

def enableMotionFan() {
    setMotion("FAN", "ON")
}

def disableMotionFan() {
    setMotion("FAN", "OFF")
}

def toggleMotionLight() {
    if (device.currentValue("motionLight") == "OFF") {
        enableMotionLight()    
    } else {
        disableMotionLight()
    }
}

def enableMotionLight() {
    setMotion("LIGHT", "ON")
}

def disableMotionLight() {
    setMotion("LIGHT", "OFF")
}

def refresh() {
    sendCommand("LIGHT", "LEVEL", "GET;ACTUAL")
    sendCommand("FAN", "DIR", "GET")
    
    // Get motion status
    //sendCommand("SNSROCC", "STATUS", "GET")
    //sendCommandRaw("<${settings.deviceName};GETALL>")
}

def parse(String description) {
    def map = parseLanMessage(description)
    def bytes = map["payload"].decodeHex()
    def response = new String(bytes)
    log.debug "parse response: ${response}"
    def values = response[1..-2].split(';')
    switch (values[1]) {
        case "LIGHT":
            switch (values[2]) {
                case "PWR":
                    return createEvent(name: "switch", value: values[3].toLowerCase())
                case "LEVEL":
                    def events = [];
                    if (values[4] == "0") {
                        events << createEvent(name: "switch", value: "off")
                    } else {
                        events << createEvent(name: "switch", value: "on")
                    }
                    int level = (int)Math.ceil(values[4].toInteger() * HAIKU_LIGHT_SPREAD)
                    events << createEvent(name: "level", value: level)
                    return events;
                case "AUTO":
                    return createEvent(name: "motionLight", value: values[3])
            }
            break
        case "FAN":
            switch (values[2]) {
                case "PWR":
                    refreshFanSpeed()
                    return createEvent(name: "speed", value: values[3].toLowerCase())
                case "SPD":
                    switch (values[4]) {
                        case "0":
                            return createEvent(name: "speed", value: "off")
                        case "1":
                            return createEvent(name: "speed", value: "low")
                        case "2":
                            return createEvent(name: "speed", value: "medium-low")
                        case "3":
                        case "4":
                            return createEvent(name: "speed", value: "medium")
                        case "5":
                        case "6":
                            return createEvent(name: "speed", value: "medium-high")
                        case "7":
                            return createEvent(name: "speed", value: "high")
                    }
                    break
                case "DIR":
                    refreshFanSpeed()
                    return createEvent(name: "fanDirection", value: values[3])
                case "AUTO":
                    return createEvent(name: "motionFan", value: values[3])
            }
            break
    }

}

def logsOff() {
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def on() {
    sendLightPowerCommand("ON")
}

def off() {
    sendLightPowerCommand("OFF")
}

def sendLightPowerCommand(String command) {
    sendCommand("LIGHT", "PWR", command)
}

def setLevel(level) {
    setLevel(level, 0)
}

def setLevel(level, duration) {
    sendLightLevelCommand(level)
}

def sendLightLevelCommand(level) {
    if (level > 100) {
        level = 100
    }
    if (level < 0) {
        level = 0
    }
    
    int haikuLevel = (int)Math.ceil(level / HAIKU_LIGHT_SPREAD)
    log.debug "level [${level}] haikuLevel [${haikuLevel}]"

    sendCommand("LIGHT", "LEVEL", "SET;${haikuLevel}")
}

def setSpeed(fanspeed){
    switch (fanspeed) {
        case "on":
            sendFanPowerCommand("ON")
            break
        case "off":
            sendFanPowerCommand("OFF")
            break
        case "low":
            sendFanSpeedCommand(1)
            break
        case "medium-low":
            sendFanSpeedCommand(2)
            break
        case "medium":
            sendFanSpeedCommand(4)
            break
        case "medium-high":
            sendFanSpeedCommand(6)
            break
        case "high":
            sendFanSpeedCommand(7)
            break
    }
}

def sendFanPowerCommand(String command) {
    sendCommand("FAN", "PWR", command)
}

def refreshFanSpeed() {
    sendCommand("FAN", "SPD", "GET;ACTUAL")
}

def sendFanSpeedCommand(int level) {
    sendCommand("FAN", "SPD", "SET;${level}")
}

def sendCommand(String haikuSubDevice, String haikuFunction, String command) {
    def haikuCommand = generateCommand(settings.deviceName, haikuSubDevice, haikuFunction, command)
    sendCommandRaw(haikuCommand)
}

def sendCommandRaw(String haikuCommand) {
    sendUDPRequest(settings.deviceIp, "31415", haikuCommand)
}

static def generateCommand(deviceName, haikuSubDevice, haikuFunction, command) {
    return "<${deviceName};${haikuSubDevice};${haikuFunction};${command}>"
}

def sendUDPRequest(address, port, payload) {
    def hubAction = new hubitat.device.HubAction(payload,
            hubitat.device.Protocol.LAN,
            [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
             destinationAddress: "${address}:${port}"])
    sendHubCommand(hubAction)
}
