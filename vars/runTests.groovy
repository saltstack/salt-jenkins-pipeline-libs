def call(Map options) {

    def Boolean retry_failed_builds

    retry_failed_builds = options.get('retry_failed_builds', null)
    if ( retry_failed_builds == null ) {
        // It wasn't passed in but we default to true
        retry_failed_builds = true
    } else {
        // Let's remove retry_failed_builds from the options dictionary
        options.remove(retry_failed_builds)
    }

    if ( retry_failed_builds == true ) {
        echo "Retrying failed builds on particular conditions"
    }

    try {
        // Let's explicitly state that we're not currently retrying
        options['retrying'] = false
        runTestSuite(options)
    } catch (Exception e) {
        println "Exception caught: '${e}'"
        if ( "${e}" == "retry-build" && retry_failed_builds == true ) {
            // runTestSuite flagged the build to retry, let's retry
            println '\n\nRETRYING BUILD\n\n'
            currentBuild.result = 'NOT_BUILT'
            options['retrying'] = true
            runTestSuite(options)
        } else {
            throw e
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
