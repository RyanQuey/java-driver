# DataStax Java Driver OSGi Tests

This module contains OSGi tests for the driver.

It declares a typical "application" bundle containing a few services that rely 
on the driver, see `src/main`.

The integration tests in `src/tests` interrogate the application bundle services 
and check that they can operate normally. They exercise different provisioning
configurations to ensure that the driver is usable in most cases.

## Running the tests

In this module's root directory, simply run:

    mvn clean verify
    
Note that all other driver modules must have been previously compiled, that is,
their respective `target/classes` directory must be up-to-date and contain
not only the class files, but also an up-to-date OSGi manifest.

If that is not the case you should first run on the parent module's root 
directory:

    mvn clean package -DskipTests 
    
You can pass the following system properties to your tests:

1. `ccm.version`: the CCM version to use
2. `ccm.dse`: whether to use DSE
3. `osgi.debug`: whether to enable remote debugging of the OSGi container (see 
   below).
   
## Debugging OSGi tests

First, you can enable DEBUG logs for the Pax Exam framework by editing the
`src/tests/resources/logback-test.xml` file.

Alternatively, you can debug the remote OSGi container by passing the system 
property `-Dosgi.debug=true`. In this case the framework will prompt for a
remote debugger on port 5005.