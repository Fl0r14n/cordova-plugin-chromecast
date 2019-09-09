<h1 align="center">cordova-plugin-chromecast</h1>
<h3 align="center">Chromecast in Cordova</h3>

## Installation
Add the plugin with the command below in your cordova project directory.

```
cordova plugin add https://github.com/jellyfin/cordova-plugin-chromecast.git
```

## Usage

This project attempts to implement the official Google Cast SDK for Chrome within Cordova. We've made a lot of progress in making this possible, so check out the [offical documentation](https://developers.google.com/cast/docs/chrome_sender) for examples.

When you call `chrome.cast.requestSession()` a popup will be displayed to select a Chromecast. 

Calling `chrome.cast.requestSession()` when you have an active session will display a popup with the option to "Stop Casting".

**Specific to this plugin** (Not supported on desktop chrome)

To make your own custom route selector use this:
```
// This will begin an active scan for routes
chrome.cast.cordova.scanForRoutes(function (routes) {
  // Here is where you should update your route selector view with the current routes
  // This will called each time the routes change
});

// When the user selects a route
// stop the scan to save battery power
chrome.cast.cordova.stopScan();
// and use the selected route.id to join the route
chrome.cast.cordova.selectRoute(route.id, function (session) {
  // Save the session for your use
}, function (err) {

});

```

## Status

The project is now pretty much feature complete - the only things that will possibly break are missing parameters. We haven't done any checking for optional paramaters. When using the plugin make sure your constructors and function calls have every parameter you can find in the method declarations.

<h3 align="center">Plugin Development<h3>

* Link your local copy of the the plugin to a project for development and testing
  * With admin permission run `cordova plugin add --link <relative path to the plugin's root dir>`
* This links the plugin's **java** files directly to the Android platform.  So you can modify the files from Android studio and re-deploy from there.
* Unfortunately it does **not** link the js files.
* To update the js files you must run:
    * `cordova plugin remove <plugin-name>`
    * `cordova plugin add --link <relative path to the plugin's root dir>`
        * Don't forget the admin permission
    * Or, you can follow these [hot reloading js instructions](https://github.com/miloproductionsinc/cordova-testing#hot-reload-js)

## Formatting

* Run `npm run style` (from the plugin directory)
  * If you get `Error: Cannot find module '<project root>\node_modules\eslint\bin\eslint'`
    * Run `npm install`
  * If it finds any formatting errors you can try and automatically fix them with:
    * `node node_modules/eslint/bin/eslint <file-path> --fix`
  * Otherwise, please manually fix the error before committing

## Testing

**1)** 

Run `npm test` to ensure your code fits the styling.  It will also pick some errors.

**2)**

This plugin has [cordova-plugin-test-framework](https://github.com/apache/cordova-plugin-test-framework) tests.

To run these tests you can follow [these instructions](https://github.com/miloproductionsinc/cordova-testing).

NOTE: You must run these tests from a project with the package name `com.miloproductionsinc.plugin_tests` otherwise `SPEC_00310` will fail.  (It uses a custom receiver which are only allowed receive from one package name.)
  
  * You can temporarily rename the project you are testing from:
    * config.xml > `<widget id="com.miloproductionsinc.plugin_tests"`
  * Or clone this project https://github.com/miloproductionsinc/cordova-testing


## Contributing

* Make sure all tests pass
* Preferably, write a test for your contribution if applicable (for a bug fix, new feature, etc)
