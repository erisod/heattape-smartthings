/**
 *  Heat Tape Weather Controller v0.2
 *
 *  Copyright 2015 Eric Mayers
 *  This is a major re-write of erobertshaw's V2.1 Smart Heat Tape controller.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "Heat Tape Weather Controller",
    namespace: "erisod",
    author: "Eric Mayers",
    description: "Based on weather data, this Smart App tracks snowfall and controls a set of heat-tape attached switches.",
    category: "Green Living",
    iconUrl: "http://cdn.device-icons.smartthings.com/Weather/weather6-icn.png",
    iconX2Url: "http://cdn.device-icons.smartthings.com/Weather/weather6-icn@2x.png",
    iconX3Url: "http://cdn.device-icons.smartthings.com/Weather/weather6-icn@2x.png") {
    appSetting "zipcode", "heattape"
}


def setupInitialConditions(){
    if (state.history == null) {
      def historyList = []
      state.history = historyList    
    } 
    
    if (state.start_snow == null) {
      state.start_snow = -1
    }
}


preferences {
  page(name: "setupPage")
  page(name: "setupTempPage")
  page(name: "listInfoPage")
}


def setupPage()
{
  setupInitialConditions()

  return dynamicPage(name: "setupPage", title: "Device Setup", nextPage:"setupTempPage", uninstall:false, install:false) {
    section("Device & Location") {
      input "heattape", "capability.switch", title: "Heat tape switches", required: true, multiple: true
      input "zipcode", "text", title: "Zipcode  or pws:code", required: true
    }

    // If we have configured devices, show the current state of them.
    if (heattape != null) {
      section("Heat Tape is : " + getHeatTapeState(), hidden:false, hideable:true) {
        heattape.each {
          paragraph("Unit : " + it.displayName + " : " + it.currentValue("switch"))
        }
      }
    }
    
    // If we have temperature and snowfall history, display it, and tracked depth.
    if (state.history.size() > 0) {
      section("Temperature/Snowfall History", hidden:false, hideable:true) {
        paragraph "Snow Depth " + getSnowDepth() + "mm"
        state.history.each {
          def displayDate = new Date(it.ts).format("yyyy-MM-dd h:mm a", location.timeZone)
          paragraph displayDate + " : " + it.snow_mm + "mm " + it.temp_c + "c"   
        }
      }
    }  
  }
}


def setupTempPage()
{
  return dynamicPage(name: "setupTempPage", title: "Temperature Setup", uninstall:true, install:true) {
    section("Temp range") {
      paragraph "When the temperature is between the min and max values, and there is recent precipitation " +
        "the heat tape switches will be turned on.  In Temp only mode snowfall is ignored.  Current Temp: " + getTemp() + " c."
      input "minTemp", "float", title: "Min Temp (c)", required: true, defaultValue: -5.0
      input "maxTemp", "float", title: "Max Temp (c)", required: true, defaultValue: 5.0
    }
    
    section("Operation Mode") {
      paragraph "Auto tracks snowfall and melt rates.  Temp only uses only temperature range.  Force modes " +
        "simply turn the switches always on or always off (generally for testing)."
      input "op_mode", "enum", title: "Mode", options: ["Automatic","Temperature-Only","Force-On","Force-Off"], 
        defaultValue:"Automatic"  
    }
    
    if (state.start_snow == -1) {
      section("Tracked snow level") {
        paragraph "Indicate additional snow if present when first installing, in mm"
        input "start_snow", "float", title: "Starting Snow level (in mm)", required: true, defaultValue: 0.0
      }
    }
  }
}


def installed() {
	initialize()
}


def updated() {
	log.debug "Updated with settings: ${settings}"
    
	unsubscribe()
	initialize()
}


def initialize() {
    setupInitialConditions()
    startSnowHack()
    heatTapeController()
    runEvery1Hour(snowBookkeeper)
    runEvery1Hour(heatTapeController)
}


// Describe heat tape state.  Returns "off", "on", or "mixed".
def getHeatTapeState() {
   def units_on = 0
   def units_off = 0

   if (heattape) {
     heattape.each { 
       log.debug "Unit " + it.displayName + " is " + it.currentValue("switch")
       if (it.currentValue("switch") == "on") {
         units_on++
       } else {
         units_off++
       }
     }
   }

   if (units_on > 0 && units_off > 0) {
     return "mixed"
   } else if (units_on == 0 && units_off == 0) {
     return "N/A (no units)"
   } else if (units_on > 0) {
     return "on "
   } else {
     return "off"
   }
}

// When installing the app you may indicate existing snow on the ground.  The app can only learn
// about additional snowfall so this is necessary.  This function injects a "fake" historical record
// an hour ago to represent that snow.
def startSnowHack() {
  if (state.start_snow != start_snow) {
    // Construct history data
    def newHistory = [ts : now() - (1000 * 60), snow_mm : start_snow, temp_c : 0.0]
  
    // Push in new history data.  
    state.history.add(0, newHistory)
    
    // Track that we've recorded this.
    state.start_snow = start_snow
  }
}


// snowBookkeeper expects to be called hourly.  It probes and records relevant weather conditions.
def snowBookkeeper() {
  setupInitialConditions()

  def now = now()

  Map currentConditions = getWeatherFeature("conditions" , zipcode)
  if (null == currentConditions.current_observation) {
    log.error "Failed to fetch weather condition data."
    log.debug currentConditions
    return
  }

  float precip_mm = currentConditions.current_observation.precip_1hr_metric.toFloat()
  float temp_c = currentConditions.current_observation.temp_c.toFloat()

  // Make a guess at the amount of snow that has fallen.  We ignore rain.
  float snow_amount = 0
  if (temp_c < 2) {
    // TODO: Is snow mm equal to precip mm?  Does it matter?
    snow_amount = precip_mm.toFloat()
  }
  
  // Construct history data entry.
  def newHistory = [ts : now, snow_mm : snow_amount, temp_c : temp_c]
  
  // Push in new history data.
  state.history.add(0, newHistory)
  
  // Clean up old history data if we have too much.  We'll keep 14 days worth.
  def MAX_HISTORY = 24 * 14
  while (state.history.size() > MAX_HISTORY) {
    state.history.remove(MAX_HISTORY)
  }
}

def getTemp() {
  if (state.history.size() > 0) {
    return state.history[0].temp_c.toFloat()
  } else {
    // Indicate error with this weird value.
    return "-999.0"
  } 
}


// Calculate accumulated depth of snow based on data history.
def getSnowDepth() {
  float snow_depth=0.0
  
  state.history.each {
    // Add snow!
    if (it.snow_mm != null) {
      snow_depth += it.snow_mm.toFloat()
    }
    
    // Melt snow!
    if (it.temp_c > 0) {
      // Based on http://directives.sc.egov.usda.gov/OpenNonWebContent.aspx?content=17753.wba
      // "1.6 to 6.0 mm/degree-day C".  Lets assume minimal melting (but you may adjust this!)
      float mm_melt_per_degreeC_day = 1.6
      
      // Our data is in hours, convert.  
      float melt_mm = (mm_melt_per_degreeC_day / 24.0) * it.temp_c.toFloat()
      snow_depth -= melt_mm
    }
  }
  
  return snow_depth
}


// Dispatch to appropriate control method based on operation mode. 
def heatTapeController() {
  log.debug "heatTapeController.  mode: " + op_mode

  switch (op_mode) {
    case "Automatic":
      controlAuto()
      break
    case "Temperature-Only":
      controlTempOnly()
      break
    case "Force-On":
      sendHeatTapeCommand(1)
      break
    case "Force-Off":
      sendHeatTapeCommand(0)
      break
  }
}

// Determine if the temperature range is appropriate to turn on.
def inTempRange() {
  if ((getTemp() > minTemp.toFloat()) && (getTemp() < maxTemp.toFloat())) {
    return true
  } else {
    return false
  } 
}


// Automatic controller.
def controlAuto() {
  log.debug "getSnowDepth " + getSnowDepth()
  log.debug "inTempRange " + inTempRange()
  if ((getSnowDepth() > 0) && inTempRange()) {
    sendHeatTapeCommand(true)
  } else {
    sendHeatTapeCommand(false)
  }
}


// Temperature Only controller.  Ignores snowfall history.
def controlTempOnly() {
  if (inTempRange()) {
    sendHeatTapeCommand(true)
  } else {
    sendHeatTapeCommand(false)
  }
}


// Control for the heat tape switches.  True = on; False = off. 
def sendHeatTapeCommand(on){
  if (heattape) { 
    heattape.each {
      if (on) {
        log.debug "Turning Heat Tape On : " + it.displayName
        it.on()
      } else {
        log.debug "Turning Heat Tape Off : " + it.displayName
        it.off()
      }
    }
  }
}
