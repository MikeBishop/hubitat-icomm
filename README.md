# iCOMM for Hubitat

Water heaters made by A.O. Smith / State use a connectivity service called
iCOMM. This can be accessed by their app, but the API can also be called
directly.

This driver implements a polling approach to retrieving data from the API and
exposing it as a device in Hubitat.

Based on:
- [py-aosmith](https://github.com/bdr99/py-aosmith/) by Brandon Rothweiler for
  the API
- [Schluter Ditra driver](https://github.com/marcre/hubitat-drivers) by Marc
  Reyhner for the driver framework

# Change Log

* [5/9/2025]   Initial release
* [6/10/2026]  Update to current app version
* [11/12/2026] More resilient to iCOMM service outages: longer request
  timeout, retries, re-login before token expiry, and Status reports
  "Update failed" only after 3 consecutive failed polls. Logs one warning
  when requests start failing (with the cause) and one on recovery.
  Recognizes rejected credentials (`INVALID_CREDENTIALS`) and reports them
  immediately. Poll schedule now uses a random offset within the interval;
  existing installs pick this up after clicking Save Preferences.
