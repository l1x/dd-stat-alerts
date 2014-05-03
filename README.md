# DataDog Stat Alerts

A Clojure library to monitor clusters and alert on outliers.

## Compiling

* [Install Lein](http://leiningen.org/#install)
* `cd dd-std-alerts && lein uberjar`

## Usage

There are two things this software does at the moment:

- downloads the data from DD in 1 minute chunks and saves it to disk
- process the data from disk with statistical engine

There are very simple rules with few (currently hardcoded) parameters. The node goes to
alarm if the current metric is higher than 1.3 * (std+mean) and goes to alerts if more than 2 times in
a row alarming. 

```bash
$ java -jar target/dd-std-alerts-0.0.1-standalone.jar -h
Usage: program-name [options] action

Options:
  -c, --config FILE  conf/app.edn  Configuration file
  -h, --help                       Print the help

Actions:
  print-config   Prints the config file
  get-data       Downloads and saves data
  analyze-data   Processes the data on disk
```
## Config file

```bash
$ cat conf/app.edn
{
  :api-key  "i3..............."
  :app-key  "2ah.................."
  :endpoint "https://app.datadoghq.com/api"
  :query    "system.cpu.user{datacenter:cluster}by{host}"
  :start    1398026400 ; 2014.04.20 13:40:00
  :end      1398043200 ; 2014.04.20 18:20:00
}
```

## TODO

* able to run multiple streams at once
* proper http client, with timeouts, connection pool
* live mode, processing data from now()
* optimize statistics for small number of nodes
* determine the best alarming parameters (per metric type?)
* wrap out the parameters per metric, so you can chose and adjust by time

## License

Copyright 2014 Istvan Szukacs and contributors

Licensed under the Apache License, Version 2.0 (the "License")

You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

